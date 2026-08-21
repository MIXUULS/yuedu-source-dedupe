package com.mina.yuedu.check;

import com.mina.yuedu.core.UrlNormalizer;
import com.mina.yuedu.model.SourceRecord;
import com.mina.yuedu.network.SystemProxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Check pipeline aligned with legado CheckSourceService semantics.
 * Phase-2: real HTTP for search/discovery, then opportunistic info/toc/content
 * URL chaining when response yields candidate links. JS-heavy sources are
 * marked js失效 when URL construction requires JS and no static URL remains.
 */
public final class CheckSourceEngine {
  public interface Listener {
    void onProgress(int done, int total, String currentName);
    void onItem(CheckSourceResult result);
    void onFinished(List<CheckSourceResult> results);
  }

  private final ExecutorService pool;
  /** 共享的 doCheck 执行池：避免每校验一个书源就新建一个线程池（万级书源时开销巨大）。 */
  private final ExecutorService doCheckPool;
  private final Set<Future<?>> runningChecks = Collections.synchronizedSet(new HashSet<>());
  private volatile boolean cancelled;

  public CheckSourceEngine(int concurrency) {
    int n = Math.max(1, Math.min(CheckSourceSettings.MAX_CONCURRENCY, concurrency));
    pool = Executors.newFixedThreadPool(n);
    doCheckPool = Executors.newFixedThreadPool(n);
  }

  public boolean isCancelled() { return cancelled; }

  public void cancel() {
    cancelled = true;
    synchronized (runningChecks) {
      for (Future<?> future : new ArrayList<>(runningChecks)) future.cancel(true);
      runningChecks.clear();
    }
    HttpProbe.cancelAll();
    // 不在这里 shutdownNow：队列任务需要启动后看到 cancelled 并递减 latch，
    // 否则 checkAll 会永久等待尚未开始的任务。
  }

  public void checkAll(List<SourceRecord> sources, CheckSourceSettings settings, Listener listener) {
    settings.normalize();
    List<CheckSourceResult> out = Collections.synchronizedList(new ArrayList<>());
    AtomicDone done = new AtomicDone();
    int total = sources.size();
    if (total == 0) {
      listener.onFinished(out);
      pool.shutdownNow();
      return;
    }
    CountDownLatch latch = new CountDownLatch(total);
    for (SourceRecord s : sources) {
      pool.execute(() -> {
        try {
          if (cancelled) return;
          CheckSourceResult r = checkOne(s, settings);
          if (!cancelled && r != null) {
            out.add(r);
            listener.onItem(r);
          }
        } finally {
          int d = done.inc();
          if (!cancelled) listener.onProgress(d, total, s.getName());
          latch.countDown();
        }
      });
    }
    try {
      if (cancelled) {
        // 取消后阻塞中的 HTTP 读至多再等一个 stepTimeout(≤30s)+余量，避免"正在停止"无限等待
        latch.await(45, TimeUnit.SECONDS);
      } else {
        latch.await();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      pool.shutdownNow();
      doCheckPool.shutdownNow();
      HttpProbe.cancelAll();
    }
    listener.onFinished(new ArrayList<>(out));
  }

  private CheckSourceResult checkOne(SourceRecord source, CheckSourceSettings settings) {
    long start = System.currentTimeMillis();
    SourceKind kind = SourceKind.of(source);
    if (!settings.allows(kind)) {
      return new CheckSourceResult(source, CheckSourceResult.Status.SKIPPED, new ArrayList<String>(),
          "跳过类型", 0, kind);
    }
    List<String> groups = new ArrayList<>();
    Future<List<String>> future = null;
    try {
      if (cancelled) return null;
      future = doCheckPool.submit(() -> doCheck(source, settings, kind));
      runningChecks.add(future);
      if (cancelled) {
        future.cancel(true);
        return null;
      }
      try {
        groups.addAll(future.get(settings.timeoutMillis(), TimeUnit.MILLISECONDS));
      } catch (TimeoutException te) {
        future.cancel(true);
        groups.add("校验超时");
        return new CheckSourceResult(source, CheckSourceResult.Status.TIMEOUT, groups, "timeout",
            System.currentTimeMillis() - start, kind);
      } catch (CancellationException ce) {
        return null;
      }
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      long cost = System.currentTimeMillis() - start;
      Set<String> succeededSteps = computeSucceededSteps(groups, settings, kind);
      if (groups.isEmpty()) {
        return new CheckSourceResult(source, CheckSourceResult.Status.SUCCESS, groups, "校验成功", cost, kind, succeededSteps);
      }
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          String.join(",", groups), cost, kind, succeededSteps);
    } catch (Exception e) {
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      groups.add("网站失效");
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          e.getMessage() == null ? "error" : e.getMessage(),
          System.currentTimeMillis() - start, kind, Collections.<String>emptySet());
    } finally {
      if (future != null) runningChecks.remove(future);
    }
  }

  /** 根据失败原因列表计算该源通过的校验步骤。 */
  private static Set<String> computeSucceededSteps(List<String> groups, CheckSourceSettings settings, SourceKind kind) {
    Set<String> s = new LinkedHashSet<>();
    if (settings.checkSearch && !containsAny(groups, "搜索失效", "js失效")) s.add("搜索");
    if (settings.checkDiscovery && !containsAny(groups, "发现失效", "js失效")) s.add("发现");
    if (settings.checkInfo && !containsAny(groups, "详情失效")) s.add("详情");
    if (settings.checkCategory && !containsAny(groups, "目录失效")) s.add("目录");
    if (settings.checkContent && !containsAny(groups, kind.contentFailGroup())) s.add("正文");
    return s;
  }
  private static boolean containsAny(List<String> list, String... keys) {
    for (String item : list) for (String k : keys) if (item.equals(k)) return true;
    return false;
  }

  /**
   * 合并同域校验结果：对同域名分组，取每个校验步骤的并集。
   * 例如 A1 搜索成功、A2 发现成功 → 合并后该域名搜索+发现都成功。
   */
  public static Map<String, MergeResult> mergeByDomain(List<CheckSourceResult> results) {
    Map<String, List<CheckSourceResult>> byDomain = new LinkedHashMap<>();
    for (CheckSourceResult r : results) {
      if (r.status == CheckSourceResult.Status.SKIPPED) continue;
      String domain = extractDomain(r.source.getUrl());
      byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(r);
    }
    Map<String, MergeResult> merged = new LinkedHashMap<>();
    for (Map.Entry<String, List<CheckSourceResult>> e : byDomain.entrySet()) {
      Set<String> allSucceeded = new LinkedHashSet<>();
      for (CheckSourceResult r : e.getValue()) allSucceeded.addAll(r.succeededSteps);
      merged.put(e.getKey(), new MergeResult(e.getKey(), e.getValue(), allSucceeded));
    }
    return merged;
  }
  public static final class MergeResult {
    public final String domain;
    public final List<CheckSourceResult> sources;
    public final Set<String> mergedSucceededSteps;
    public MergeResult(String domain, List<CheckSourceResult> sources, Set<String> mergedSucceededSteps) {
      this.domain = domain; this.sources = sources;
      this.mergedSucceededSteps = mergedSucceededSteps;
    }
  }
  private static String extractDomain(String url) {
    try { return new java.net.URL(url).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); }
    catch (Exception e) { return url; }
  }

  /** 内容级代理回退：直连返回了 HTTP 200-499 但内容不正确（被墙/污染），自动用系统代理重试一次。 */
  private static HttpProbe.Response retryWithProxy(HttpProbe.Response r, AnalyzeUrlLite req, Map<String, String> headers, int timeoutMs) {
    if (r.httpOk() && SystemProxy.get() != null) {
      try { return HttpProbe.fetch(req, headers, timeoutMs, SystemProxy.get()); }
      catch (Exception ignored) {}
    }
    return r;
  }

  private List<String> doCheck(SourceRecord source, CheckSourceSettings settings, SourceKind kind) {
    List<String> bad = new ArrayList<>();
    String base = null;
    try {
      // bookSourceUrl 的 #后缀是书源身份标签，不参与实际 HTTP 请求。
      base = UrlNormalizer.requestUrl(source.getUrl());
    } catch (Exception e) {
      bad.add("网站失效");
      return bad;
    }

    Map<String, String> headers = HttpProbe.parseSourceHeader(source.rawString("header"));
    int stepTimeout = (int) Math.min(Math.max(settings.timeoutMillis() / 3L, 8000L), 30000L);

    // base host probe
    try {
      AnalyzeUrlLite baseReq = AnalyzeUrlLite.parse(base, base, settings.keyword, 1);
      HttpProbe.Response br = HttpProbe.fetch(baseReq, headers, Math.min(stepTimeout, 10000));
      if (!settings.isHttpOk(br.code)) bad.add("网站失效");
    } catch (Exception e) {
      bad.add("网站失效");
    }
    if (Thread.currentThread().isInterrupted()) return bad;

    String bookUrl = null;
    String tocUrl = null;
    String chapterUrl = null;

    if (settings.checkSearch) {
      String searchUrl = source.rawString("searchUrl");
      Object ruleSearch = source.getRaw().get("ruleSearch");
      if (searchUrl == null || searchUrl.trim().isEmpty()) {
        bad.add("搜索链接规则为空");
      } else {
        AnalyzeUrlLite req = AnalyzeUrlLite.parse(searchUrl, base, settings.keyword, 1);
        if (req.usesJs && (req.url == null || req.url.trim().isEmpty() || req.url.equals(base))) {
          bad.add("js失效");
        } else if (!ResultInspect.hasRule(ruleSearch) && !req.usesJs) {
          // still try network if URL exists
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
            // 内容级代理回退：直连内容不正确（被墙/污染），自动走系统代理重试
            if (!hit.ok) {
              HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
              if (r2 != r) { r = r2; hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base); }
            }
            if (!settings.isHttpOk(r.code) || !hit.ok) bad.add("搜索失效");
            else if (!hit.bookUrls.isEmpty()) bookUrl = hit.bookUrls.get(0);
          } catch (Exception e) {
            bad.add("搜索失效");
          }
        } else {
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
            // 内容级代理回退
            if (!hit.ok) {
              HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
              if (r2 != r) { r = r2; hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base); }
            }
            if (!settings.isHttpOk(r.code) || !hit.ok) bad.add("搜索失效");
            else if (!hit.bookUrls.isEmpty()) bookUrl = hit.bookUrls.get(0);
          } catch (Exception e) {
            bad.add(req.usesJs ? "js失效" : "搜索失效");
          }
        }
      }
    }
    if (Thread.currentThread().isInterrupted()) return bad;

    if (settings.checkDiscovery && !settings.quickMode) {
      String explore = source.rawString("exploreUrl");
      Object ruleExplore = source.getRaw().get("ruleExplore");
      if (explore != null && !explore.trim().isEmpty()) {
        // exploreUrl may be multi-line kinds; take first non-empty line / first kind url
        String first = firstExploreUrl(explore);
        AnalyzeUrlLite req = AnalyzeUrlLite.parse(first, base, settings.keyword, 1);
        if (req.usesJs && (req.url == null || req.url.isEmpty())) {
          bad.add("js失效");
        } else {
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
            // 内容级代理回退
            if (!hit.ok) {
              HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
              if (r2 != r) { r = r2; hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base); }
            }
            boolean ruleOk = ResultInspect.hasRule(ruleExplore);
            if (!settings.isHttpOk(r.code) || !hit.ok) {
              // 有 ruleExplore 规则时网络探测失败不判死（规则可自行提取正文）；
              // 仅当既无规则又探测失败才报"发现失效"。
              if (!ruleOk) bad.add("发现失效");
            } else if (bookUrl == null && !hit.bookUrls.isEmpty()) {
              bookUrl = hit.bookUrls.get(0);
            }
          } catch (Exception e) {
            bad.add(req.usesJs ? "js失效" : "发现失效");
          }
        }
      }
      // empty explore is allowed (many sources have no discovery)
    }
    if (Thread.currentThread().isInterrupted()) return bad;

    Map<String, Object> ruleInfo = source.ruleMap("ruleBookInfo");
    Map<String, Object> ruleToc = source.ruleMap("ruleToc");
    Map<String, Object> ruleContent = source.ruleMap("ruleContent");

    if (settings.checkInfo) {
      if (!ResultInspect.hasRule(ruleInfo) && bookUrl == null) {
        bad.add("详情失效");
      } else if (bookUrl != null) {
        try {
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(bookUrl, base, settings.keyword, 1);
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          ResultInspect.Hit hit = ResultInspect.inspectBookPage(r.body);
          // 内容级代理回退
          if (!hit.ok) {
            HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
            if (r2 != r) { r = r2; hit = ResultInspect.inspectBookPage(r.body); }
          }
          if (!settings.isHttpOk(r.code) || !hit.ok) bad.add("详情失效");
          else {
            // prefer tocUrl from response, else book url as toc base
            if (!hit.bookUrls.isEmpty()) {
              // may contain toc/chapter candidates
              tocUrl = hit.bookUrls.get(0);
            }
            String initToc = ResultInspect.ruleString(ruleInfo, "tocUrl");
            if (initToc != null && !initToc.trim().isEmpty() && !initToc.contains("@js") && !initToc.contains("<js>")) {
              AnalyzeUrlLite tocReq = AnalyzeUrlLite.parse(initToc, r.finalUrl != null ? r.finalUrl : bookUrl, settings.keyword, 1);
              tocUrl = tocReq.url;
            } else if (tocUrl == null) {
              tocUrl = r.finalUrl != null ? r.finalUrl : bookUrl;
            }
          }
        } catch (Exception e) {
          bad.add("详情失效");
        }
      }
      // if only rule present and no bookUrl from search/discovery, keep soft-pass on rule existence
    }
    if (Thread.currentThread().isInterrupted()) return bad;

    if (settings.checkCategory && !settings.quickMode) {
      if (!ResultInspect.hasRule(ruleToc) && tocUrl == null) {
        bad.add("目录失效");
      } else if (tocUrl != null) {
        try {
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(tocUrl, base, settings.keyword, 1);
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : tocUrl);
          // 内容级代理回退
          if (!hit.ok) {
            HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
            if (r2 != r) { r = r2; hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : tocUrl); }
          }
          if (!settings.isHttpOk(r.code) || !hit.ok) bad.add("目录失效");
          else if (!hit.bookUrls.isEmpty()) chapterUrl = hit.bookUrls.get(0);
        } catch (Exception e) {
          bad.add("目录失效");
        }
      }
    }
    if (Thread.currentThread().isInterrupted()) return bad;

    if (settings.checkContent && !settings.quickMode) {
      String contentFail = kind.contentFailGroup();
      if (!ResultInspect.hasRule(ruleContent) && chapterUrl == null) {
        bad.add(contentFail);
      } else if (chapterUrl != null) {
        try {
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(chapterUrl, base, settings.keyword, 1);
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          ResultInspect.Hit hit = ResultInspect.inspectContent(r.body, kind);
          // 内容级代理回退
          if (!hit.ok) {
            HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
            if (r2 != r) { r = r2; hit = ResultInspect.inspectContent(r.body, kind); }
          }
          if (!settings.isHttpOk(r.code) || !hit.ok) bad.add(contentFail);
        } catch (Exception e) {
          bad.add(contentFail);
        }
      }
    }

    // de-dup groups while preserving order
    List<String> uniq = new ArrayList<>();
    for (String g : bad) if (!uniq.contains(g)) uniq.add(g);
    return uniq;
  }

  private static String firstExploreUrl(String explore) {
    String s = explore.trim();
    // formats: "name::url\n..." or JSON or plain url
    String[] lines = s.split("\\r?\\n");
    for (String line : lines) {
      String t = line.trim();
      if (t.isEmpty()) continue;
      int sep = t.indexOf("::");
      if (sep >= 0 && sep + 2 < t.length()) return t.substring(sep + 2).trim();
      if (t.startsWith("http") || t.contains("{{") || t.contains("{{") || t.contains("/")) return t;
    }
    return s;
  }

  private static final class AtomicDone {
    private int v;
    synchronized int inc() { return ++v; }
  }
}
