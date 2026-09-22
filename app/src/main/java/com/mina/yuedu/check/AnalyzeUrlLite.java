package com.mina.yuedu.check;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AnalyzeUrlLite {
  public final String url;
  public final String method;
  public final String body;
  public final Map<String, String> headers;
  public final boolean usesJs;

  private AnalyzeUrlLite(String url, String method, String body, Map<String, String> headers, boolean usesJs) {
    this.url = url;
    this.method = method;
    this.body = body;
    this.headers = headers;
    this.usesJs = usesJs;
  }

  public static AnalyzeUrlLite parse(String raw, String baseUrl, String keyword, int page) {
    if (raw == null) raw = "";
    String s = raw.trim();
    String lowerAll = s.toLowerCase(Locale.ROOT);
    boolean usesJs = s.contains("<js>") || s.contains("</js>") || s.contains("@js:") || lowerAll.contains("javascript");
    String method = "GET";
    String body = null;
    Map<String, String> headers = new HashMap<>();

    String urlPart = s;
    String opt = null;
    // 与结尾 '}' 配对的顶层 '{' 才是选项起点。旧实现 lastIndexOf(",{") 会命中 body 内嵌套的 ,{，
    // 导致选项没被剥离、整段 JSON 被当作 URL 发请求。
    int brace = openingBraceOfTrailingObject(s);
    if (brace > 0 && s.charAt(brace - 1) == ',') {
      String maybeOpt = s.substring(brace).trim();
      String lowerOpt = maybeOpt.toLowerCase(Locale.ROOT);
      // 仅当 ,{...} 片段形如 legado 搜索选项（含 method/body/header 键）时才切分，
      // 避免把 URL 参数中出现的 ",{" 误当作 POST 选项而截断地址。
      if (maybeOpt.startsWith("{") && maybeOpt.endsWith("}")
          && (lowerOpt.contains("method") || lowerOpt.contains("body") || lowerOpt.contains("header"))) {
        urlPart = s.substring(0, brace - 1).trim();
        opt = maybeOpt;
      }
    }

    String keyEnc;
    try {
      keyEnc = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8.name());
    } catch (Exception e) {
      keyEnc = keyword == null ? "" : keyword;
    }
    String pageStr = String.valueOf(Math.max(1, page));
    urlPart = urlPart
        .replace("{{key}}", keyEnc)
        .replace("{{page}}", pageStr)
        .replace("{key}", keyEnc)
        .replace("{page}", pageStr)
        .replace("{{host}}", hostOf(baseUrl))
        .replace("{host}", hostOf(baseUrl));

    if (opt != null && opt.startsWith("{") && opt.endsWith("}")) {
      // method 只认 "method" 键的值：旧实现 contains("post") 对整个选项串做子串匹配，
      // body/URL 里出现 "post" 一词就会把 GET 误翻成 POST
      String methodVal = extractJsonString(opt, "method");
      if (methodVal != null && methodVal.toUpperCase(Locale.ROOT).contains("POST")) method = "POST";
      String bodyVal = extractJsonString(opt, "body");
      if (bodyVal != null) {
        body = bodyVal
            .replace("{{key}}", keyEnc)
            .replace("{key}", keyEnc)
            .replace("{{page}}", pageStr)
            .replace("{page}", pageStr);
        // legado 惯例：未声明 method 但有 body 时按 POST 提交
        if (methodVal == null) method = "POST";
      }
      String ua = extractJsonString(opt, "User-Agent");
      if (ua == null) ua = extractJsonString(opt, "userAgent");
      String cookie = extractJsonString(opt, "Cookie");
      String referer = extractJsonString(opt, "Referer");
      // 标准格式把请求头放在嵌套的 "headers":{...} 对象里，顶层键只取字符串值会把它整个丢掉
      String headersObj = extractJsonObject(opt, "headers");
      if (headersObj == null) headersObj = extractJsonObject(opt, "header");
      if (headersObj != null) {
        if (ua == null) ua = extractJsonString(headersObj, "User-Agent");
        if (ua == null) ua = extractJsonString(headersObj, "userAgent");
        if (cookie == null) cookie = extractJsonString(headersObj, "Cookie");
        if (referer == null) referer = extractJsonString(headersObj, "Referer");
      }
      if (ua != null) headers.put("User-Agent", ua);
      if (cookie != null) headers.put("Cookie", cookie);
      if (referer != null) headers.put("Referer", referer);
    }

    return new AnalyzeUrlLite(absolutize(urlPart, baseUrl), method, body, headers, usesJs);
  }

  private static String hostOf(String base) {
    try { return new URL(base).getHost(); } catch (Exception e) { return ""; }
  }

  private static String absolutize(String url, String base) {
    if (url == null || url.isEmpty()) return base;
    String u = url.trim();
    if (u.startsWith("http://") || u.startsWith("https://")) return u;
    try { return new URL(new URL(base), u).toString(); } catch (Exception e) { return u; }
  }

  private static String extractJsonString(String json, String key) {
    String dq = "\"" + key + "\"";
    String sq = "'" + key + "'";
    for (String p : new String[] {dq, sq}) {
      int i = json.indexOf(p);
      if (i < 0) continue;
      int colon = json.indexOf(':', i + p.length());
      if (colon < 0) continue;
      int j = colon + 1;
      while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
      if (j >= json.length()) continue;
      char q = json.charAt(j);
      if (q != '"' && q != '\'') continue;
      int k = j + 1;
      StringBuilder sb = new StringBuilder();
      while (k < json.length()) {
        char c = json.charAt(k++);
        if (c == '\\' && k < json.length()) { sb.append(json.charAt(k++)); continue; }
        if (c == q) return sb.toString();
        sb.append(c);
      }
    }
    return null;
  }

  /** 返回与字符串末尾 '}'（忽略尾部空白）配对的顶层 '{' 的下标；末尾不是平衡的顶层对象时返回 -1。
   *  字符串感知（含 \\" 转义），body 值内嵌套的花括号不会被误认为对象边界。 */
  private static int openingBraceOfTrailingObject(String s) {
    int depth = 0, startAt = -1, endAt = -1;
    boolean inStr = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inStr) {
        if (c == '\\') i++;
        else if (c == '"') inStr = false;
        continue;
      }
      if (c == '"') inStr = true;
      else if (c == '{') { if (depth == 0) startAt = i; depth++; }
      else if (c == '}') {
        depth--;
        if (depth < 0) return -1; // 顶层多出闭括号
        if (depth == 0) endAt = i;
      }
    }
    if (depth != 0 || startAt < 0 || endAt < 0) return -1;
    for (int i = endAt + 1; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) return -1;
    return startAt;
  }

  /** 提取 key 对应的嵌套 JSON 对象子串（平衡花括号、字符串感知）；不存在或值不是对象时返回 null。 */
  private static String extractJsonObject(String json, String key) {
    String dq = "\"" + key + "\"";
    String sq = "'" + key + "'";
    for (String p : new String[] {dq, sq}) {
      int i = json.indexOf(p);
      if (i < 0) continue;
      int colon = json.indexOf(':', i + p.length());
      if (colon < 0) continue;
      int j = colon + 1;
      while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
      if (j >= json.length() || json.charAt(j) != '{') continue;
      int depth = 0;
      boolean inStr = false;
      for (int k = j; k < json.length(); k++) {
        char c = json.charAt(k);
        if (inStr) {
          if (c == '\\') k++;
          else if (c == '"') inStr = false;
          continue;
        }
        if (c == '"') inStr = true;
        else if (c == '{') depth++;
        else if (c == '}') { depth--; if (depth == 0) return json.substring(j, k + 1); }
      }
    }
    return null;
  }
}
