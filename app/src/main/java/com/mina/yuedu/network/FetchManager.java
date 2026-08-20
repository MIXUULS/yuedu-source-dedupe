package com.mina.yuedu.network;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
public final class FetchManager {
  public static final class QueueState {
    private final Deque<List<String>> domainBuckets; private final int limit, total;
    private List<String> currentBucket; private int bucketIndex;
    private int inFlight, completed, succeeded, failed, sources; private boolean cancelled, keepLoaded;
    private String currentUrl; private long downloadedBytes, totalBytes = -1;
    private long lastSpeedTime, lastSpeedBytes; private long speedBytesPerSec;
    public QueueState(Deque<List<String>> buckets, int limit){
      this.domainBuckets=buckets;
      this.limit=limit;
      int t=0; for(List<String> b:buckets) t+=b.size();
      total=t;
    }
    public synchronized String takeNext(){
      if(cancelled||inFlight>=limit) return null;
      while(currentBucket==null||bucketIndex>=currentBucket.size()){
        if(domainBuckets.isEmpty()){ currentBucket=null; return null; }
        currentBucket=domainBuckets.removeFirst(); bucketIndex=0;
      }
      inFlight++; return currentBucket.get(bucketIndex++);
    }
    public synchronized void complete(boolean ok, int count){ if(inFlight>0) inFlight--; completed++; if(ok) succeeded++; else failed++; sources+=Math.max(0,count); }
    public synchronized void cancel(boolean keep){ cancelled=true; keepLoaded=keep; domainBuckets.clear(); currentBucket=null; }
    public synchronized boolean shouldKeepLoaded(){ return keepLoaded; }
    public synchronized boolean isCancelled(){ return cancelled; }
    public synchronized boolean isDone(){ return completed>=total||(cancelled&&inFlight==0); }
    public synchronized void setDownloading(String url, long total){ currentUrl=url; downloadedBytes=0; totalBytes=total; }
    public synchronized void addDownloaded(long n){ if(currentUrl!=null) downloadedBytes+=n; }
    public synchronized void clearDownloading(){ currentUrl=null; downloadedBytes=0; totalBytes=-1; }
    public synchronized FetchProgress progress(){
      long now = System.currentTimeMillis();
      if (currentUrl != null) {
        if (lastSpeedTime != 0 && now > lastSpeedTime) {
          long dt = now - lastSpeedTime;
          long db = downloadedBytes - lastSpeedBytes;
          if (dt > 0 && db >= 0) {
            long inst = db * 1000L / dt;
            speedBytesPerSec = speedBytesPerSec == 0 ? inst : (speedBytesPerSec + inst) / 2; // 简单平滑
          }
        }
        lastSpeedTime = now; lastSpeedBytes = downloadedBytes;
      } else {
        speedBytesPerSec = 0; lastSpeedTime = 0; lastSpeedBytes = 0;
      }
      return new FetchProgress(total, completed, succeeded, failed, sources, !isDone(), cancelled,
          currentUrl, downloadedBytes, totalBytes, inFlight, speedBytesPerSec);
    }
  }
  private static final String PROXY_HOST = "127.0.0.1";
  private static final int PROXY_PORT = 3388;
  private static final long PROGRESS_REPORT_BYTES = 262144; // 每约 256KB 上报一次下载进度
  /** 串行解析队列：下载保持并发，但大 JSON 解析同一时刻只有一个，
   *  避免多个大包同时解析导致内存峰值爆炸（OOM / 系统 o-stop 杀进程）。 */
  private final ExecutorService parseQueue = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "source-parse");
    t.setDaemon(true);
    return t;
  });
  private final Set<HttpURLConnection> active=Collections.synchronizedSet(new HashSet<>());
  private volatile QueueState state; private volatile FetchListener listener; private final AtomicInteger workers=new AtomicInteger();
  private final int maxBytes = 64 * 1024 * 1024; // 万级大合集书源包可达 30-50MB，上限放宽到 64MB
  public synchronized void start(List<String> urls, int concurrency, FetchListener l){
    if(state!=null && !state.isDone()) throw new IllegalStateException("task running");
    // 按域名分组：同一域名串行（worker 独占一个域名桶），不同域名并发，避免被服务器封
    Map<String, List<String>> buckets = new LinkedHashMap<>();
    for (String url : urls) {
      String domain = extractDomain(url);
      buckets.computeIfAbsent(domain, k -> new ArrayList<>()).add(url);
    }
    state=new QueueState(new ArrayDeque<>(buckets.values()), concurrency);
    listener=l; listener.onProgress(state.progress());
    workers.set(concurrency);
    for(int i=0;i<concurrency;i++) new Thread(this::work, "source-fetch-"+i).start();
  }
  public void cancel(boolean keep){ QueueState s=state; if(s==null) return; s.cancel(keep); synchronized(active){ for(HttpURLConnection c:new ArrayList<>(active)) c.disconnect(); } }
  private void work(){
    try{
      while(true){
        String url=state.takeNext(); if(url==null) break;
        state.setDownloading(url, -1);
        try{
          String body=fetch(url);
          // 内容级回退：直连拿到了非 JSON 内容（DNS 污染/被墙错误页，HTTP 200 但内容错误）时，
          // 自动用系统代理重试一次——无需用户判断哪些源需要梯子。
          Proxy sysProxy = SystemProxy.get();
          if (sysProxy != null) {
            String t = body == null ? "" : body.trim();
            if (!t.startsWith("{") && !t.startsWith("[")) {
              try {
                body = doFetch(url, sysProxy);
              } catch (Exception ignored) {
                // 代理也失败则保留直连内容（可能是 YCK HTML 页面，走后续提取逻辑）
              }
            }
          }
          final String finalBody = body;
          // 解析提交到串行队列：下载并发、解析串行，控制内存峰值
          parseQueue.execute(() -> {
            try {
              SourceParser.ParseResult parsed = SourceParser.parseArray(finalBody, 0);
              state.complete(true, parsed.getRecords().size());
              listener.onItem(url, finalBody, parsed);
            } catch (Exception e) {
              state.complete(false, 0);
              String msg = e.getMessage();
              if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
              listener.onFailure(url, e.getClass().getSimpleName() + ":" + msg);
            } finally {
              state.clearDownloading();
              listener.onProgress(state.progress());
            }
          });
        }catch(Exception e){
          state.complete(false, 0);
          state.clearDownloading();
          String msg = e.getMessage();
          if(msg==null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
          listener.onFailure(url, e.getClass().getSimpleName()+":"+msg);
          listener.onProgress(state.progress());
        }
      }
    } finally {
      if(workers.decrementAndGet()==0){
        // 等串行解析队列把已下载的内容全部处理完，再通知完成（否则结果会遗漏）
        parseQueue.shutdown();
        try { parseQueue.awaitTermination(10, TimeUnit.MINUTES); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        listener.onFinished(state.isCancelled(), state.shouldKeepLoaded());
      }
    }
  }
  private String fetch(String url) throws Exception {
    Exception directErr = null;
    try {
      return doFetch(url, Proxy.NO_PROXY);
    } catch (Exception e) {
      directErr = e;
      if (state != null && state.isCancelled()) throw e;
    }
    // 依次尝试：系统代理（梯子/手动代理）→ 本机回退代理
    Proxy sys = SystemProxy.get();
    if (sys != null) {
      try {
        return doFetch(url, sys);
      } catch (Exception e2) {
        if (directErr != null) throw directErr;
        throw e2;
      }
    }
    try {
      return doFetch(url, new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT)));
    } catch (Exception e2) {
      if (directErr != null) throw directErr;
      throw e2;
    }
  }
  private String doFetch(String url, Proxy proxy) throws Exception {
    HttpURLConnection c = open(url, proxy);
    active.add(c);
    try{
      int code = c.getResponseCode();
      // some stacks surface 3xx even with follow-redirects; follow a few hops manually
      int hops = 0;
      while (code >= 300 && code < 400 && hops < 5) {
        String loc = c.getHeaderField("Location");
        if (loc == null || loc.trim().isEmpty()) break;
        URL next = new URL(c.getURL(), loc);
        active.remove(c); c.disconnect();
        c = open(next.toString(), proxy);
        active.add(c);
        code = c.getResponseCode();
        hops++;
      }
      long contentLen = c.getContentLengthLong();
      if (contentLen > 0) state.setDownloading(url, contentLen);
      if(code < 200 || code >= 300) throw new IOException("HTTP "+code);
      InputStream raw = c.getErrorStream()!=null && code>=400 ? c.getErrorStream() : c.getInputStream();
      if (raw == null) throw new IOException("empty body");
      InputStream in = wrapMaybeGzip(raw, c.getContentEncoding());
      try{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[8192]; int n; int total=0; long lastReport=0;
        while((n=in.read(b))>=0){
          total+=n;
          if(total>maxBytes) throw new IOException("response too large ("+total+")");
          out.write(b,0,n);
          state.addDownloaded(n);
          // 大文件下载中实时上报进度，避免界面长时间"不动"
          if(total - lastReport >= PROGRESS_REPORT_BYTES){ lastReport=total; listener.onProgress(state.progress()); }
        }
        if(total==0) throw new IOException("empty body");
        return out.toString("UTF-8");
      } finally { in.close(); }
    } finally { active.remove(c); c.disconnect(); }
  }
  private HttpURLConnection open(String url, Proxy proxy) throws Exception {
    HttpURLConnection c=(HttpURLConnection) new URL(url).openConnection(proxy);
    c.setConnectTimeout(15000);
    c.setReadTimeout(90000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", "YueduSourceDedupe/3.0");
    c.setRequestProperty("Accept", "application/json,text/plain,*/*");
    // 不主动声明 gzip：对齐 2.3.10，避免 Android 透明解压与二次 GZIP 冲突
    return c;
  }
  private static String extractDomain(String url) {
    try {
      String host = new URL(url).getHost();
      if (host != null) return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    } catch (Exception ignored) {}
    return url;
  }

  private InputStream wrapMaybeGzip(InputStream raw, String enc) throws IOException {
    boolean claimGzip = enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip");
    if (!claimGzip) return raw;
    // 仅当确实是 gzip 魔数时再包一层，防止已透明解压后再次解压失败
    PushbackInputStream pb = new PushbackInputStream(raw, 2);
    int b1 = pb.read(); int b2 = pb.read();
    if (b1 < 0) return pb;
    if (b2 < 0) { pb.unread(b1); return pb; }
    pb.unread(new byte[]{(byte)b1,(byte)b2});
    if (b1 == 0x1f && b2 == 0x8b) return new GZIPInputStream(pb);
    return pb;
  }
}
