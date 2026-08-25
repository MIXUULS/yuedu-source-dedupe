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

  /** 检查书源是否「需要登录」（含 loginUrl 或 loginCheckUrl）。 */
  public static boolean needsLogin(SourceRecord s) {
    if (s == null) return false;
    Map<String, Object> raw = s.getRaw();
    return hasValue(raw, "loginUrl") || hasValue(raw, "loginCheckUrl");
  }

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
