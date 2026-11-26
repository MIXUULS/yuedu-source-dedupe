package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;

import java.net.URI;
import java.util.Map;

/**
 * 从书源的其他字段里恢复被遗漏的 URL。
 *
 * 解决 Issue #1 反馈的问题：部分书源的 {@code bookSourceUrl} 是空的，
 * 但 {@code searchUrl} 或 {@code ruleSearch.baseUrl} 里有完整地址。
 * 原来的实现直接判为「URL 为空」丢弃，等于把可用书源当成废品。
 *
 * 恢复顺序（先精确后宽松）：
 * <pre>
 *   1. bookSourceUrl 本身（trim 后非空）
 *   2. searchUrl 的 scheme://host
 *   3. ruleSearch.baseUrl / ruleExplore.baseUrl
 *   4. exploreUrl 的第一个地址
 * </pre>
 *
 * 只提取「scheme://host」部分，不带路径——因为该字段的语义是"这个源属于哪个站"，
 * 带路径反而会让同一站的不同源被当成不同源。
 */
public final class UrlRecovery {

  private UrlRecovery() {}

  /**
   * 尝试恢复书源的 URL。
   *
   * @return 恢复出的 {@code scheme://host}；无法恢复时返回 null
   */
  public static String recover(SourceRecord source) {
    if (source == null) return null;

    String direct = trimToNull(source.getUrl());
    if (direct != null) return direct;

    Map<String, Object> raw = source.getRaw();
    if (raw == null) return null;

    // 2. searchUrl
    String fromSearch = originOf(trimToNull(str(raw.get("searchUrl"))));
    if (fromSearch != null) return fromSearch;

    // 3. 规则里的 baseUrl
    String fromRuleSearch = originOf(baseUrlIn(raw.get("ruleSearch")));
    if (fromRuleSearch != null) return fromRuleSearch;
    String fromRuleExplore = originOf(baseUrlIn(raw.get("ruleExplore")));
    if (fromRuleExplore != null) return fromRuleExplore;

    // 4. exploreUrl（可能是多行，取第一个非空）
    String fromExplore = originOf(firstLine(trimToNull(str(raw.get("exploreUrl")))));
    if (fromExplore != null) return fromExplore;

    return null;
  }

  /** 该源是否属于「URL 为空但可恢复」的情况。 */
  public static boolean isRecoverable(SourceRecord source) {
    return trimToNull(source == null ? null : source.getUrl()) == null && recover(source) != null;
  }

  /** 从规则对象里取 baseUrl。 */
  private static String baseUrlIn(Object rule) {
    if (!(rule instanceof Map)) {
      // 规则也可能是字符串形式（部分书源用 JSON 字符串存规则）
      return null;
    }
    return trimToNull(str(((Map<?, ?>) rule).get("baseUrl")));
  }

  /** 提取 {@code scheme://host[:port]}。失败返回 null。 */
  static String originOf(String url) {
    if (url == null) return null;
    String s = url.trim();
    if (s.isEmpty()) return null;
    // 相对地址、模板占位符等无法确定主机，直接放弃
    if (!s.startsWith("http://") && !s.startsWith("https://")) {
      // 可能带 {{...}} 前缀，尝试从第一个 http 开始截取
      int idx = indexOfHttp(s);
      if (idx < 0) return null;
      s = s.substring(idx);
    }
    // 搜索地址常带模板占位符（{{key}} / {key} / ${key}），直接交给 URI 会抛异常。
    // 只取到第一个占位符之前的部分——主机一定在那之前。
    s = stripTemplate(s);
    try {
      URI u = new URI(s);
      String scheme = u.getScheme();
      if (scheme == null) return null;
      String host = hostOf(u, s);
      if (host == null || host.isEmpty()) return null;
      int port = u.getPort();
      String def = scheme.toLowerCase(java.util.Locale.ROOT);
      boolean defaultPort = (def.equals("http") && port == 80) || (def.equals("https") && port == 443);
      return scheme + "://" + host + (port >= 0 && !defaultPort ? ":" + port : "");
    } catch (Exception e) {
      // URI 解析仍失败时退一步：手工从 scheme:// 后截到第一个 / ? #
      return manualOrigin(s);
    }
  }

  /** 截掉模板占位符，保留主机部分。 */
  private static String stripTemplate(String s) {
    int cut = s.length();
    for (String marker : new String[]{"{{", "{", "${"}) {
      int i = s.indexOf(marker);
      if (i >= 0 && i < cut) cut = i;
    }
    return s.substring(0, cut);
  }

  /** 兜底：手工解析 scheme://host[:port]。 */
  private static String manualOrigin(String s) {
    int schemeEnd = s.indexOf("://");
    if (schemeEnd <= 0) return null;
    String scheme = s.substring(0, schemeEnd);
    String rest = s.substring(schemeEnd + 3);
    int end = rest.length();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '/' || c == '?' || c == '#') { end = i; break; }
    }
    String hostPort = rest.substring(0, end).trim();
    if (hostPort.isEmpty()) return null;
    // 去掉可能的 userInfo
    int at = hostPort.lastIndexOf('@');
    if (at >= 0) hostPort = hostPort.substring(at + 1);
    if (hostPort.isEmpty()) return null;
    return scheme + "://" + hostPort;
  }

  /**
   * 取主机名。与 UrlNormalizer 同理：中文域名与下划线主机名需要 URL 兜底，
   * 这里复用相同的三层策略以保持一致。
   */
  private static String hostOf(URI u, String raw) {
    String host = u.getHost();
    if (host != null && !host.trim().isEmpty()) return host.trim();
    try {
      java.net.IDN.toASCII(u.getRawAuthority());
    } catch (Exception ignored) {
      // 继续尝试 URL
    }
    try {
      String h = new java.net.URL(raw).getHost();
      if (h != null && !h.trim().isEmpty()) return h.trim();
    } catch (Exception ignored) {
      // ignore
    }
    return null;
  }

  private static int indexOfHttp(String s) {
    int a = s.indexOf("http://");
    int b = s.indexOf("https://");
    if (a < 0) return b;
    if (b < 0) return a;
    return Math.min(a, b);
  }

  private static String firstLine(String s) {
    if (s == null) return null;
    for (String line : s.split("\\r?\\n")) {
      String t = line.trim();
      if (!t.isEmpty()) return t;
    }
    return null;
  }

  private static String str(Object o) { return o == null ? null : String.valueOf(o); }

  private static String trimToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}