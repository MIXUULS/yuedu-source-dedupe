package com.mina.yuedu.core;

/**
 * 轻量文本工具。
 *
 * 存在的原因：{@code String.join} 与 {@code StringJoiner} 是 API 26 (Android 8.0) 才有的方法，
 * 而本项目 minSdk = 24。直接在代码里调用 {@code String.join} 在 Android 7.0/7.1 上会抛
 * {@code NoSuchMethodError}（属于 Error，catch Exception 捕不到，表现为直接闪退）。
 * 这里用 StringBuilder 实现等价语义，所有需要拼接的地方统一走这里。
 *
 * 纯逻辑，不依赖 Android，可单元测试。
 */
public final class TextKit {

  private TextKit() {}

  /** 按分隔符拼接，null 元素按空串处理；parts 为 null 时返回空串。 */
  public static String join(CharSequence delimiter, Iterable<?> parts) {
    if (parts == null) return "";
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Object part : parts) {
      if (!first) sb.append(delimiter);
      first = false;
      if (part != null) sb.append(part);
    }
    return sb.toString();
  }

  /** 按分隔符拼接数组，null 元素按空串处理；数组为 null 时返回空串。 */
  public static String join(CharSequence delimiter, Object[] parts) {
    if (parts == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) sb.append(delimiter);
      if (parts[i] != null) sb.append(parts[i]);
    }
    return sb.toString();
  }

  /**
   * 判断字符串是否为 null 或只含空白。
   *
   * 注意不能用 {@code Character.isWhitespace} 单打独斗：它不认不间断空格 U+00A0
   * 和 U+2007 / U+202F，而这几个字符在中文书名里极常见（从网页复制的名称常带 U+00A0）。
   * 本项目 NameCleaner / NameSimilarity 也显式处理了这几个码点，这里保持一致。
   */
  public static boolean isBlank(CharSequence s) {
    if (s == null) return true;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\u3000' || c == '\u00A0' || c == '\u2007' || c == '\u202F') continue;
      if (!Character.isWhitespace(c)) return false;
    }
    return true;
  }

  /** 超长文本截断，用于日志/提示等场景，避免一次性把几十 KB 塞进 UI。 */
  public static String abbreviate(String s, int max) {
    if (s == null) return null;
    if (max <= 0) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
