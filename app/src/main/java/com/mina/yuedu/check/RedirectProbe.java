package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import com.mina.yuedu.network.SystemProxy;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发探测书源 URL 的最终重定向地址。
 *
 * 为什么要独立实现而不复用 {@link HttpProbe}：
 * <pre>
 *   HttpProbe 是给「校验」用的——它要解析书源 header、处理编码、读 body，
 *   单次成本高。重定向探测只关心一件事：这个 URL 最终落到哪个主机。
 *   用 HEAD 请求 + 不读 body，单次成本低一个数量级，万级书源才跑得动。
 * </pre>
 *
 * 并发与限流：
 * <ul>
 *   <li>默认 32 并发——再高容易被目标站判成攻击</li>
 *   <li>每条超时 8 秒，比校验的 30 秒短（只想看落点，不需要等内容）</li>
 *   <li>支持随时取消，取消后已探测的结果仍保留</li>
 * </ul>
 */
public final class RedirectProbe {

  /** 默认并发数。 */
  public static final int DEFAULT_CONCURRENCY = 32;
  /** 单条探测超时（毫秒）。 */
  private static final int PROBE_TIMEOUT_MS = 8000;

  /** 探测进度回调，供界面显示。 */
  public interface Listener {
    void onProgress(int done, int total);
    void onFinished(Map<String, String> resolved, boolean cancelled);
  }

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private volatile ExecutorService pool;

  /** 请求取消。已探测到的结果不会被丢弃。 */
  public void cancel() {
    cancelled.set(true);
    ExecutorService p = pool;
    if (p != null) p.shutdownNow();
  }

  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * 探测所有书源的最终地址。
   *
   * @param sources  待探测书源
   * @param listener 进度回调，可为 null
   * @return 原始 URL → 最终 URL（探测失败的不在结果里）
   */
  public Map<String, String> probeAll(List<SourceRecord> sources, Listener listener) {
    Map<String, String> resolved = new ConcurrentHashMap<>();
    if (sources == null || sources.isEmpty()) {
      if (listener != null) listener.onFinished(resolved, false);
      return resolved;
    }

    // 按主机去重：同一主机的多个书源只需要探测一次
    Map<String, String> hostToSample = new LinkedHashMap<>();
    for (SourceRecord s : sources) {
      if (s == null || s.getUrl() == null) continue;
      String url = stripIdentity(s.getUrl());
      if (url == null) continue;
      String host = RedirectCluster.hostOf(url);
      if (host == null || host.isEmpty()) continue;
      if (!hostToSample.containsKey(host)) hostToSample.put(host, url);
    }

    final int total = hostToSample.size();
    if (total == 0) {
      if (listener != null) listener.onFinished(resolved, false);
      return resolved;
    }

    final AtomicInteger done = new AtomicInteger();
    final CountDownLatch latch = new CountDownLatch(total);
    pool = Executors.newFixedThreadPool(Math.min(DEFAULT_CONCURRENCY, total), r -> {
      Thread t = new Thread(r, "redirect-probe");
      t.setDaemon(true);
      return t;
    });

    for (Map.Entry<String, String> e : hostToSample.entrySet()) {
      final String host = e.getKey();
      final String sampleUrl = e.getValue();
      pool.execute(() -> {
        try {
          if (cancelled.get()) return;
          String finalUrl = resolveFinalUrl(sampleUrl);
          if (finalUrl != null) {
            // 把同一主机的所有原始 URL 都映射过去，省得重复探测
            for (SourceRecord s : sources) {
              if (s == null || s.getUrl() == null) continue;
              String u = stripIdentity(s.getUrl());
              if (u == null) continue;
              if (host.equals(RedirectCluster.hostOf(u))) resolved.put(u, finalUrl);
            }
          }
        } catch (Exception ignored) {
          // 单条失败不影响整体
        } finally {
          int d = done.incrementAndGet();
          if (listener != null && !cancelled.get()) {
            listener.onProgress(d, total);
          }
          latch.countDown();
        }
      });
    }

    try {
      // 总预算：按并发批次估算，留足余量
      long budgetMs = (long) Math.ceil(total / (double) DEFAULT_CONCURRENCY)
          * (PROBE_TIMEOUT_MS + 2000L);
      budgetMs = Math.max(30_000L, Math.min(budgetMs, 15 * 60_000L));
      latch.await(budgetMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      pool.shutdownNow();
    }

    if (listener != null) listener.onFinished(resolved, cancelled.get());
    return resolved;
  }

  /**
   * 解析单个 URL 的最终落地地址。
   *
   * 先用 HEAD（便宜），部分服务器不支持 HEAD 时退回 GET 但立刻断开不读 body。
   */
  String resolveFinalUrl(String rawUrl) {
    String url = stripIdentity(rawUrl);
    if (url == null) return null;
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null;

    String head = tryFetch(url, true);
    if (head != null) return head;
    return tryFetch(url, false);
  }

  /**
   * 发一次请求只取最终地址，不读 body。
   *
   * @param useHead true 用 HEAD，false 用 GET（拿到响应头就断开）
   */
  private String tryFetch(String url, boolean useHead) {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(url).openConnection(resolveProxy());
      c.setConnectTimeout(PROBE_TIMEOUT_MS);
      c.setReadTimeout(PROBE_TIMEOUT_MS);
      c.setInstanceFollowRedirects(true);
      c.setRequestMethod(useHead ? "HEAD" : "GET");
      c.setRequestProperty("User-Agent",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
      c.setRequestProperty("Accept", "*/*");
      c.setRequestProperty("Connection", "close");

      int code = c.getResponseCode();
      // 4xx/5xx 也算「知道落点了」——最终 URL 可能已经变了
      String finalUrl = c.getURL() == null ? null : c.getURL().toString();
      if (code <= 0) return null;
      // 只有 HEAD 不被支持（405/501）时才需要退回 GET
      if (useHead && (code == 405 || code == 501 || code == 403)) {
        return null;   // 让调用方试 GET
      }
      return finalUrl;
    } catch (Exception e) {
      return null;
    } finally {
      if (c != null) {
        try {
          InputStream in = c.getErrorStream();
          if (in != null) in.close();
          InputStream es = c.getInputStream();
          if (es != null) es.close();
        } catch (Exception ignored) {
          // 只关心地址，流没关掉也无妨（连接已 Connection: close）
        }
        c.disconnect();
      }
    }
  }

  /** 有系统代理时走代理，与校验保持一致。 */
  private static Proxy resolveProxy() {
    try {
      Proxy p = SystemProxy.get();
      return p != null ? p : Proxy.NO_PROXY;
    } catch (Exception e) {
      return Proxy.NO_PROXY;
    }
  }

  /** 去掉 bookSourceUrl 的 #身份标签，它不是真实请求地址。 */
  static String stripIdentity(String url) {
    if (url == null) return null;
    String s = url.trim();
    if (s.isEmpty()) return null;
    int hash = s.indexOf('#');
    if (hash > 0) s = s.substring(0, hash);
    return s.trim().isEmpty() ? null : s.trim();
  }

  /**
   * 供测试用：把探测结果直接聚成组。
   */
  public static List<RedirectCluster.Group> probeAndCluster(
      List<SourceRecord> sources, Map<String, String> resolved) {
    if (resolved == null) resolved = Collections.emptyMap();
    List<RedirectCluster.Group> groups = RedirectCluster.cluster(sources, resolved);
    List<RedirectCluster.Group> out = new ArrayList<>(groups);
    out.sort((a, b) -> Integer.compare(b.size(), a.size()));
    return out;
  }
}
