package com.mina.yuedu.core;
import com.mina.yuedu.model.DedupeMode;
import java.net.*;
import java.util.*;
public final class UrlNormalizer {
  private static final Set<String> TRACK = new HashSet<>(Arrays.asList(
      "utm_source","utm_medium","utm_campaign","utm_term","utm_content","from","ref","spm","fbclid","gclid"));
  private UrlNormalizer(){}
  public static String completeKey(String raw) throws URISyntaxException { return canonical(raw, false, false); }
  public static String standardKey(String raw) throws URISyntaxException { return canonical(raw, false, true); }
  public static String hostKey(String raw) throws URISyntaxException { return canonical(raw, true, false); }
  public static String key(String raw, DedupeMode mode) throws URISyntaxException {
    if (mode == DedupeMode.AGGRESSIVE) return hostKey(raw);
    if (mode == DedupeMode.STANDARD) return standardKey(raw);
    if (mode == DedupeMode.STRICT) return strictKey(raw);
    return completeKey(raw);
  }

  /**
   * 严格模式的 key：在完整 URL 基础上，额外把 www / 手机站归一化到裸域。
   *
   * Issue #1 三.3 的建议：很多书源同时存在 {@code www.site.com}、{@code m.site.com}
   * 和 {@code site.com} 三个版本，内容完全一样，只是入口不同。
   *
   * 只在严格模式启用——标准模式保持"不同主机就是不同源"的保守语义，
   * 避免把确实内容不同的站（如 PC 版与移动版布局差异很大）误合并。
   */
  public static String strictKey(String raw) throws URISyntaxException {
    String[] parts = splitIdentity(raw);
    URI u = new URI(parts[0]);
    validate(raw, u);
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    String host = normalizeMobileHost(hostOf(raw, u).toLowerCase(Locale.ROOT));
    int port = u.getPort();
    boolean def = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
    String hp = host + (port >= 0 && !def ? ":" + port : "");
    String path = u.getRawPath();
    if (path == null || path.isEmpty()) path = "/";
    if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
    String q = u.getRawQuery();
    String key = scheme + "://" + hp + path + (q == null || q.isEmpty() ? "" : "?" + q);
    if (parts[1] != null && !parts[1].isEmpty()) key += "#" + parts[1];
    return key;
  }

  /** 常见子域前缀：去掉后视为同一个站。 */
  private static final String[] MOBILE_PREFIXES = {"www.", "m.", "wap.", "mobile."};

  /**
   * 把 www / 手机站前缀归一化掉。
   *
   * 只在有裸域对照时才剥——例如 {@code www.a.com} 剥成 {@code a.com}，
   * 但 {@code www.com} 这种本身是裸域的不动（剥完剩 "com" 会撞车）。
   */
  static String normalizeMobileHost(String host) {
    if (host == null || host.isEmpty()) return host;
    // IPv4 / IPv6 不处理
    if (host.matches("[0-9.]+") || host.contains(":")) return host;

    for (String prefix : MOBILE_PREFIXES) {
      if (!host.startsWith(prefix)) continue;
      String rest = host.substring(prefix.length());
      // 剥完必须还剩至少两段（如 a.com），否则说明原 host 本身就很短，不动
      if (rest.indexOf('.') <= 0) return host;
      // 剥完不能只剩顶级域
      if (rest.lastIndexOf('.') == rest.length() - 1) return host;
      return rest;
    }
    return host;
  }

  /**
   * bookSourceUrl 中的 #后缀是阅读用来区分同站不同源的身份标签，
   * 并不是 HTTP 请求的一部分。校验网络时只使用井号前的传输地址。
   */
  public static String requestUrl(String raw) throws URISyntaxException {
    String[] parts = splitIdentity(raw);
    URI u = new URI(parts[0]);
    validate(raw, u);
    return parts[0];
  }

  private static String canonical(String raw, boolean hostOnly, boolean stripTrack) throws URISyntaxException {
    String[] parts = splitIdentity(raw);
    URI u = new URI(parts[0]);
    validate(raw, u);
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    String host = hostOf(raw, u).toLowerCase(Locale.ROOT);
    if (hostOnly) host = host.replaceFirst("^www\\.", "");
    int port = u.getPort();
    boolean def = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
    String hp = host + (port >= 0 && !def ? ":" + port : "");
    if (hostOnly) return hp;
    String path = u.getRawPath();
    if (path == null || path.isEmpty()) path = "/";
    if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
    String q = u.getRawQuery();
    if (stripTrack && q != null && !q.isEmpty()) q = stripTracking(q);
    String key = scheme + "://" + hp + path + (q == null || q.isEmpty() ? "" : "?" + q);
    // 标准/严格模式保留非空 #标签，避免把 #简体、#🎃 等不同书源误合并。
    if (parts[1] != null && !parts[1].isEmpty()) key += "#" + parts[1];
    return key;
  }

  /**
   * 取主机名。
   *
   * {@code URI.getHost()} 只认 RFC 3986 的 reg-name，遇到中文域名（例子.com）或带下划线的主机名
   * （a_b.com）会返回 null，原先直接拿它做非空校验，导致这类书源被整条判成"非法 URL"丢弃。
   * 这里按 URI → IDN 转 ASCII → URL 的顺序兜底，任一层拿到主机名即可。
   */
  private static String hostOf(String raw, URI u) throws URISyntaxException {
    String host = u.getHost();
    if (host != null && !host.trim().isEmpty()) return host.trim();
    // 中文域名：先 IDN 转 punycode 再交给 URI
    try {
      String ascii = java.net.IDN.toASCII(rawHostCandidate(u)).toLowerCase(Locale.ROOT);
      URI again = new URI(u.getScheme() + "://" + ascii + "/");
      String h = again.getHost();
      if (h != null && !h.trim().isEmpty()) return h.trim();
    } catch (Exception ignored) {
      // 非法 IDN（含乱码/超长标签）继续尝试下一层
    }
    // 最后用 URL 兜底：它能处理带下划线的主机名
    try {
      String h = new java.net.URL(u.getScheme() + "://" + rawHostCandidate(u) + "/").getHost();
      if (h != null && !h.trim().isEmpty()) return h.trim();
    } catch (Exception ignored) {
      // ignore
    }
    throw new URISyntaxException(raw, "missing host");
  }

  /** 从 URI 的 authority 里手工剥离端口，得到主机名原文（忽略 userInfo）。 */
  private static String rawHostCandidate(URI u) {
    String auth = u.getRawAuthority();
    if (auth == null) return "";
    int at = auth.lastIndexOf('@');
    if (at >= 0) auth = auth.substring(at + 1);
    if (auth.startsWith("[")) { // IPv6 字面量
      int close = auth.indexOf(']');
      if (close > 0) return auth.substring(0, close + 1);
    }
    int colon = auth.indexOf(':');
    return colon >= 0 ? auth.substring(0, colon) : auth;
  }

  private static String[] splitIdentity(String raw) throws URISyntaxException {
    if (raw == null) throw new URISyntaxException("null", "missing URL");
    String s = raw.trim();
    if (s.isEmpty()) throw new URISyntaxException(raw, "empty URL");
    int hash = s.indexOf('#');
    String request = (hash < 0 ? s : s.substring(0, hash)).trim();
    String identity = hash < 0 ? null : s.substring(hash + 1).trim();
    if (request.isEmpty()) throw new URISyntaxException(raw, "missing request URL");
    return new String[]{request, identity};
  }

  private static void validate(String raw, URI u) throws URISyntaxException {
    // 只看 scheme：主机名的校验交给 hostOf()，因为它对中文域名/下划线主机名需要多层兜底，
    // 这里再用 URI.getHost() 判空会把那类合法书源直接挡掉。
    if (u.getScheme() == null) throw new URISyntaxException(raw, "missing scheme");
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) throw new URISyntaxException(raw, "unsupported scheme");
  }

  private static String stripTracking(String q) {
    List<String> kept = new ArrayList<>();
    for (String part : q.split("&")) {
      if (part.isEmpty()) continue;
      int i = part.indexOf('=');
      String k = i < 0 ? part : part.substring(0, i);
      try { k = URLDecoder.decode(k, "UTF-8"); } catch (Exception ignored) {}
      if (TRACK.contains(k.toLowerCase(Locale.ROOT))) continue;
      kept.add(part);
    }
    Collections.sort(kept);
    return TextKit.join("&", kept);
  }
}
