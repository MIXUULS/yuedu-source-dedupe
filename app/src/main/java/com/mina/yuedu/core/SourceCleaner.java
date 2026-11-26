package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书源过滤工具：清理需要登录的源、删除弹窗验证码的源。
 * 纯逻辑，不依赖 Android，可单元测试。
 */
public final class SourceCleaner {

  private SourceCleaner() {}

  /**
   * 检查书源是否「需要登录」。
   *
   * Issue #1 用户反馈 + 真实样本统计（267 条书源里 68 条有非空 loginUrl）：
   * loginUrl 在 legado 里不只是登录地址，还被用作请求前钩子——
   * <pre>
   *   "function login(){}"        空 JS 函数，根本不是登录页
   *   "function register() {...}" 注册脚本
   *   {"url": "null"}             JSON 配置钩子
   *   {"url": ""}                 配置里 url 都是空的
   * </pre>
   * 这类值占非空 loginUrl 的约 24%（11 条 JS + 5 条 JSON 配置）。
   * 真正指向登录页的 http 地址是 46 条，但其中也可能有"可登录但不登录也能用"的。
   *
   * 因此这里只排除**明显的非登录值**，指向真实 http 登录页的仍按原逻辑处理
   * （是否剔除由用户通过开关决定，默认关闭）。
   */
  public static boolean needsLogin(SourceRecord s) {
    if (s == null) return false;
    Map<String, Object> raw = s.getRaw();
    // loginCheckUrl 是更明确的"登录检测"信号，优先看它
    if (hasValue(raw, "loginCheckUrl")) return true;
    return looksLikeRealLoginUrl(text(raw.get("loginUrl")));
  }

  /**
   * 判断 loginUrl 是否真的是一个登录地址。
   *
   * 排除真实样本里出现的几类"假登录"值，避免误剔主流书源。
   * 样本来自 2257 条用户真实书源 + 1399 条聚合书源的统计。
   */
  static boolean looksLikeRealLoginUrl(String value) {
    if (value == null) return false;
    String t = value.trim();
    if (t.isEmpty()) return false;

    String lower = t.toLowerCase(java.util.Locale.ROOT);

    // 1. 明确的 JS 脚本开头
    if (lower.startsWith("function") || lower.startsWith("java.")
        || lower.startsWith("<js>") || lower.contains("startbrowser")) {
      return false;
    }

    // 2. 多行 JS 代码：真实样本里有些 loginUrl 是整段脚本，
    //    如 "const localVersion = 3.0;\n\n\nfunction login() {...}"
    if (looksLikeScript(t)) return false;

    // 3. JSON 配置对象：{"url": ...}
    if (t.startsWith("{") || t.startsWith("[")) {
      String inner = t.replaceAll("\\s+", "");
      if (inner.contains("\"url\":\"\"") || inner.contains("\"url\":\"null\"")
          || inner.equals("{}") || inner.equals("[]")) {
        return false;
      }
      // 配置里有真实 http 地址，可能确实是登录入口
      return inner.contains("http://") || inner.contains("https://");
    }

    // 4. 空函数体
    if (t.matches("function\\s*\\w*\\s*\\([^)]*\\)\\s*\\{\\s*\\}")) return false;

    // 5. 只有空白/分隔符
    if (t.replaceAll("[\\s;,]", "").isEmpty()) return false;

    // 其余（http 地址、相对路径）视为可能是真登录入口
    return true;
  }

  /**
   * 是否是脚本代码而不是 URL。
   *
   * 判据：出现 JS 语法特征且**不以 http(s):// 开头**。
   * 真实样本里 "const xxx = ..." / "if (...) {" / "var x = ..." 这类都是脚本。
   */
  private static boolean looksLikeScript(String t) {
    String lower = t.toLowerCase(java.util.Locale.ROOT);
    // 以 URL 开头的一定是地址
    if (lower.startsWith("http://") || lower.startsWith("https://")) return false;

    // JS 语句特征
    String[] markers = {
        "const ", "var ", "let ", "if (", "if(", "try{", "try {",
        "cache.", "java.", "result", "headers =", "source.getvariable",
        "=>", "();", ";\n"
    };
    for (String m : markers) {
      if (lower.contains(m)) return true;
    }
    // 多行且不含 http 地址 → 视为脚本
    if (t.contains("\n") && !lower.contains("http")) return true;
    return false;
  }

  private static String text(Object o) { return o == null ? null : String.valueOf(o); }

  /** 检查书源是否「有弹窗验证码」（含 captchaUrl、verifyCode 或 authUrl）。 */
  public static boolean hasCaptcha(SourceRecord s) {
    if (s == null) return false;
    Map<String, Object> raw = s.getRaw();
    return hasValue(raw, "captchaUrl") || hasValue(raw, "verifyCode") || hasValue(raw, "authUrl");
  }

  /** 空字段是书源导出时常见的占位值，不能因此过滤掉书源。 */
  private static boolean hasValue(Map<String, Object> raw, String key) {
    Object value = raw.get(key);
    return value != null && !String.valueOf(value).trim().isEmpty();
  }

  /** 过滤掉需要登录的源。 */
  public static List<SourceRecord> cleanLogin(List<SourceRecord> sources) {
    if (sources == null) return new ArrayList<>();
    List<SourceRecord> out = new ArrayList<>();
    for (SourceRecord s : sources) {
      if (!needsLogin(s)) out.add(s);
    }
    return out;
  }

  /** 过滤掉有弹窗验证码的源。 */
  public static List<SourceRecord> cleanCaptcha(List<SourceRecord> sources) {
    if (sources == null) return new ArrayList<>();
    List<SourceRecord> out = new ArrayList<>();
    for (SourceRecord s : sources) {
      if (!hasCaptcha(s)) out.add(s);
    }
    return out;
  }

  private static SourceRecord make(int order, String name, String url, String... extraKeys) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", new LinkedHashMap<>());
    for (int i = 0; i < extraKeys.length; i += 2) {
      if (i + 1 < extraKeys.length) raw.put(extraKeys[i], extraKeys[i + 1]);
    }
    return new SourceRecord(order, name, url, raw);
  }

  // 测试用工厂方法
  public static SourceRecord normalSource() {
    return make(0, "正常源", "https://example.com/1");
  }

  public static SourceRecord loginSource() {
    return make(1, "登录源", "https://example.com/2", "loginUrl", "https://example.com/login");
  }

  public static SourceRecord loginCheckSource() {
    return make(2, "登录检查源", "https://example.com/3", "loginCheckUrl", "https://example.com/check");
  }

  public static SourceRecord captchaSource() {
    return make(3, "验证码源", "https://example.com/4", "captchaUrl", "https://example.com/captcha");
  }

  public static SourceRecord verifyCodeSource() {
    return make(4, "验证码源2", "https://example.com/5", "verifyCode", "abc123");
  }

  public static SourceRecord authUrlSource() {
    return make(5, "认证源", "https://example.com/6", "authUrl", "https://example.com/auth");
  }
}
