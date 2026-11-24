package com.mina.yuedu.check;

import com.mina.yuedu.network.SystemProxy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public final class HttpProbe {
  public static final class Response {
    public final int code;
    public final String body;
    public final String finalUrl;
    public final long millis;
    public Response(int code, String body, String finalUrl, long millis) {
      this.code = code; this.body = body; this.finalUrl = finalUrl; this.millis = millis;
    }
    public boolean httpOk() { return code >= 200 && code < 500; }
    public boolean hasBody() { return body != null && !body.trim().isEmpty(); }
  }

  private static final int MAX = 2 * 1024 * 1024;
  private static final Set<HttpURLConnection> ACTIVE = Collections.synchronizedSet(new HashSet<>());

  public static void cancelAll() {
    synchronized (ACTIVE) {
      for (HttpURLConnection connection : new ArrayList<>(ACTIVE)) connection.disconnect();
      ACTIVE.clear();
    }
  }

  public static Response fetch(AnalyzeUrlLite req, Map<String, String> sourceHeaders, int timeoutMs) throws Exception {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
    Exception directErr;
    try {
      // 先直连：国内/无需代理的源直连最快
      return doFetch(req, sourceHeaders, timeoutMs, null);
    } catch (Exception e) {
      if (Thread.currentThread().isInterrupted()) throw e;
      directErr = e;
    }
    // 直连失败后自动走系统代理（梯子/手动代理）：被墙源经代理可通，无需用户判断开不开代理
    Proxy sys = SystemProxy.get();
    if (sys == null) throw directErr;
    try {
      return doFetch(req, sourceHeaders, timeoutMs, sys);
    } catch (Exception e2) {
      throw directErr; // 报告直连时的原始错误（更接近根因）
    }
  }

  /** 使用指定代理发起请求（用于校验内容级回退：直连拿到内容不对时，用系统代理重试）。 */
  public static Response fetch(AnalyzeUrlLite req, Map<String, String> sourceHeaders, int timeoutMs, Proxy proxy) throws Exception {
    return doFetch(req, sourceHeaders, timeoutMs, proxy);
  }

  private static Response doFetch(AnalyzeUrlLite req, Map<String, String> sourceHeaders, int timeoutMs, Proxy proxy) throws Exception {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
    long start = System.currentTimeMillis();
    String url = req.url;
    int hops = 0;
    while (true) {
    HttpURLConnection c = proxy == null
        ? (HttpURLConnection) new URL(url).openConnection()
        : (HttpURLConnection) new URL(url).openConnection(proxy);
    ACTIVE.add(c);
    if (Thread.currentThread().isInterrupted()) {
      ACTIVE.remove(c);
      c.disconnect();
      throw new InterruptedIOException("cancelled");
    }
    try {
      c.setConnectTimeout(Math.min(timeoutMs, 15000));
      c.setReadTimeout(timeoutMs);
      c.setInstanceFollowRedirects(true);
      String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.ROOT);
      c.setRequestMethod(method);
      c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) YueduSourceDedupe/3.0-check");
      c.setRequestProperty("Accept", "*/*");
      c.setRequestProperty("Accept-Encoding", "gzip");
      if (sourceHeaders != null) {
        for (Map.Entry<String, String> e : sourceHeaders.entrySet()) {
          if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
        }
      }
      if (req.headers != null) {
        for (Map.Entry<String, String> e : req.headers.entrySet()) {
          if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
        }
      }
      if ("POST".equals(method) && req.body != null) {
        c.setDoOutput(true);
        if (c.getRequestProperty("Content-Type") == null) {
          c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
        c.getOutputStream().write(req.body.getBytes(StandardCharsets.UTF_8));
      }
      int code = c.getResponseCode();
      // JDK 不跟随跨协议重定向（http→https），只会拿到 301 短页导致误判"搜索失效"；
      // 与 FetchManager 一致，见到 3xx 带 Location 就手动再跳一次（最多 5 跳）
      if (code >= 300 && code < 400 && hops < 5) {
        String loc = c.getHeaderField("Location");
        if (loc != null && !loc.trim().isEmpty()) {
          url = new URL(new URL(url), loc.trim()).toString();
          hops++;
          continue; // finally 会断开旧连接
        }
      }
      InputStream raw = code >= 400 ? c.getErrorStream() : c.getInputStream();
      if (raw == null) {
        // 4xx/5xx 且服务器没给错误体时不能抛异常：状态码（含自定义可用码）要交给调用方判定
        if (code >= 400) {
          return new Response(code, "", url, System.currentTimeMillis() - start);
        }
        raw = c.getInputStream();
      }
      String enc = c.getContentEncoding();
      // 与 FetchManager 保持一致：仅当 Content-Encoding 声明 gzip 且内容确实是 gzip 魔数时才解压，
      // 防止 Android 透明解压后二次解压导致误判"搜索失效"。
      InputStream in = (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip"))
          ? wrapMaybeGzip(raw) : raw;
      String body = "";
      if (in != null) {
        try {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          byte[] buf = new byte[8192];
          int n; int total = 0;
          while ((n = in.read(buf)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
            total += n;
            // 超限直接判失败，避免截断页面导致误判（例如把完整页面切一半当有效）
            if (total > MAX) throw new IOException("response too large (" + total + ")");
            out.write(buf, 0, n);
          }
          body = decode(out.toByteArray(), c.getContentType());
        } finally {
          in.close();
        }
      }
      return new Response(code, body, url, System.currentTimeMillis() - start);
    } finally {
      ACTIVE.remove(c);
      c.disconnect();
    }
    }
  }

  /**
   * 按真实编码把响应字节解成字符串。
   *
   * 旧实现无条件用 UTF-8 解码，导致大量 GBK/GB2312 老站（国内书源里很常见）中文全部变成
   * 替换符，页面正文长度和关键词都判不出来，被误判成"搜索失效/正文失效"。
   *
   * 判定顺序：BOM → Content-Type charset → 页面 meta 嗅探 → 默认。
   * BOM 放在最前是因为它是字节级事实，服务器把 charset 声明写错的情况很常见。
   */
  public static String decode(byte[] data, String contentType) {
    if (data == null || data.length == 0) return "";
    Charset bom = sniffBom(data);
    Charset charset = bom != null ? bom : charsetFromContentType(contentType);
    if (charset == null) charset = sniffFromContent(data);
    if (charset == null) charset = StandardCharsets.UTF_8;
    String text = new String(data, charset);
    // UTF-8 BOM 会被解成开头一个 U+FEFF，留着会让 startsWith("{") 之类的判断全部失效
    if (bom == StandardCharsets.UTF_8 && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
      text = text.substring(1);
    }
    return text;
  }

  /** 从 Content-Type 头里取 charset 参数，例如 "text/html; charset=gbk"。 */
  private static Charset charsetFromContentType(String contentType) {
    if (contentType == null) return null;
    int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
    if (i < 0) return null;
    String cs = contentType.substring(i + "charset=".length()).trim();
    int semi = cs.indexOf(';');
    if (semi >= 0) cs = cs.substring(0, semi);
    cs = cs.trim().replace("\"", "").replace("'", "");
    return toCharset(cs);
  }

  /** BOM 是最可靠的编码标记，优先级高于任何声明。 */
  private static Charset sniffBom(byte[] d) {
    if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
      return StandardCharsets.UTF_8;
    }
    if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) return StandardCharsets.UTF_16BE;
    if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) return StandardCharsets.UTF_16LE;
    return null;
  }

  /** 纯 ASCII 内容任何单字节编码结果都一样，无需嗅探。 */
  private static boolean isMostlyAscii(byte[] d) {
    int limit = Math.min(d.length, 4096);
    for (int i = 0; i < limit; i++) if ((d[i] & 0xFF) > 0x7F) return false;
    return true;
  }

  /** 宽松地把名称解析成 Charset，无法识别时返回 null（不抛异常）。 */
  private static Charset toCharset(String name) {
    if (name == null) return null;
    String n = name.trim();
    if (n.isEmpty()) return null;
    // 常见的"非标准但被广泛使用"的别名
    String lower = n.toLowerCase(Locale.ROOT);
    if (lower.equals("gb2312") || lower.equals("gbk") || lower.equals("gb-2312")) {
      // GB18030 是 GBK/GB2312 的超集，用它解码老站不会丢字
      try { return Charset.forName("GB18030"); } catch (Exception ignored) {}
    }
    if (lower.equals("utf8")) return StandardCharsets.UTF_8;
    try { return Charset.forName(n); } catch (Exception ignored) { return null; }
  }

  /**
   * 内容嗅探器。Android 的 UniversalDetector 需要额外依赖，这里用 JDK 自带的
   * 页面 meta 扫描 + 严格 UTF-8 校验，覆盖书源校验的绝大多数场景且零依赖。
   */
  private static Charset sniffFromContent(byte[] data) {
    // 先看 HTML 头部声明的 meta charset（只看前 4KB，声明一定在头部）
    int limit = Math.min(data.length, 4096);
    String head = new String(data, 0, limit, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
    int m = head.indexOf("charset");
    while (m >= 0) {
      int eq = head.indexOf('=', m);
      if (eq > 0) {
        int end = eq + 1;
        while (end < head.length()) {
          char ch = head.charAt(end);
          if (ch == '"' || ch == '\'' || ch == '>' || ch == ';' || ch == ' ') break;
          end++;
        }
        Charset cs = toCharset(head.substring(eq + 1, end));
        if (cs != null) return cs;
      }
      m = head.indexOf("charset", m + 7);
    }
    // 没有声明：能按严格 UTF-8 解通就当 UTF-8，否则按 GB18030（国内老站默认）
    return isValidUtf8(data) ? StandardCharsets.UTF_8 : gb18030OrNull();
  }

  private static Charset gb18030OrNull() {
    try { return Charset.forName("GB18030"); } catch (Exception e) { return null; }
  }

  /** 严格校验 UTF-8 合法性（错误的 UTF-8 序列在 GBK 站点很常见）。 */
  private static boolean isValidUtf8(byte[] d) {
    try {
      java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
      dec.decode(java.nio.ByteBuffer.wrap(d, 0, Math.min(d.length, 65536)));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static InputStream wrapMaybeGzip(InputStream raw) throws IOException {
    // 仅当确实是 gzip 魔数时再包一层，防止已透明解压后再次解压失败
    PushbackInputStream pb = new PushbackInputStream(raw, 2);
    int b1 = pb.read(); int b2 = pb.read();
    if (b1 < 0) return pb;
    if (b2 < 0) { pb.unread(b1); return pb; }
    pb.unread(new byte[]{(byte)b1,(byte)b2});
    if (b1 == 0x1f && b2 == 0x8b) return new GZIPInputStream(pb);
    return pb;
  }

  public static Map<String, String> parseSourceHeader(String headerField) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    if (headerField == null || headerField.trim().isEmpty()) return map;
    String h = headerField.trim();
    if (h.startsWith("{") && h.endsWith("}")) {
      int i = 0;
      while (i < h.length()) {
        int k1 = h.indexOf('"', i);
        if (k1 < 0) break;
        int k2 = h.indexOf('"', k1 + 1);
        if (k2 < 0) break;
        String key = h.substring(k1 + 1, k2);
        int colon = h.indexOf(':', k2 + 1);
        if (colon < 0) break;
        int v1 = h.indexOf('"', colon + 1);
        if (v1 < 0) { i = colon + 1; continue; }
        int v2 = v1 + 1;
        StringBuilder sb = new StringBuilder();
        while (v2 < h.length()) {
          char ch = h.charAt(v2++);
          if (ch == '\\' && v2 < h.length()) { sb.append(h.charAt(v2++)); continue; }
          if (ch == '"') break;
          sb.append(ch);
        }
        map.put(key, sb.toString());
        i = v2;
      }
      return map;
    }
    String[] lines = h.split("\n");
    for (String line : lines) {
      int c = line.indexOf(':');
      if (c > 0) map.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
    }
    return map;
  }
}
