package com.mina.yuedu.ui;

import android.webkit.JavascriptInterface;
import java.util.List;

public final class YckBridge {
  public interface Collector { String collect(String url); }
  public interface BatchCollector { String collectAll(String urls); }
  private final Collector collector;
  private final BatchCollector batchCollector;

  public YckBridge(Collector collector, BatchCollector batchCollector) {
    this.collector = collector;
    this.batchCollector = batchCollector;
  }

  public YckBridge(Collector collector) {
    this(collector, null);
  }

  @JavascriptInterface public String addToDedupe(String url) {
    return collector == null ? "invalid" : collector.collect(url);
  }

  /** compatibility alias */
  @JavascriptInterface public String collect(String url) { return addToDedupe(url); }

  /** 批量收集，urls 为 JSON 字符串数组 ["url1","url2",...]，返回 "added:N" 或 "invalid"。 */
  @JavascriptInterface public String collectAll(String urls) {
    return batchCollector == null ? "invalid" : batchCollector.collectAll(urls);
  }
}
