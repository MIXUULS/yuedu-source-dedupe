package com.mina.yuedu.export;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 导出文件名生成。
 *
 * 从 MainActivity 抽出来的原因：文件名分散在 6 个调用点各自拼接，
 * 而分类名（"可用"/"非HTTP"）、用户名自定义标签都可能含
 * {@code / \ : * ? " < > |} 这些在多数文件系统上非法或被 SAF 拒绝的字符，
 * 一旦命中就是保存失败且提示不明确。统一在这里做清洗，规则可测。
 */
public final class ExportNaming {

  /** 文件名里不允许出现的字符（Windows/Android SAF 共有的非法集合）。 */
  private static final String ILLEGAL = "/\\:*?\"<>|";

  /** 单个文件名主体的长度上限，留出扩展名与路径的空间。 */
  private static final int MAX_BODY = 80;

  private ExportNaming() {}

  /** 今天的日期标记，形如 2026-11-24。 */
  public static String today() {
    return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
  }

  /**
   * 清洗文件主体：替换非法字符、压缩空白、截断。
   *
   * @param raw 原始文本，可为 null
   * @return 可安全用作文件名的文本（永不为 null）
   */
  public static String sanitize(String raw) {
    // null 也要走完整流程落到兜底名：早期直接 return "" 会让调用方拼出 ".json" 这种隐藏文件
    if (raw == null) return "export";
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (ILLEGAL.indexOf(c) >= 0) {
        sb.append('_');
      } else if (c == '\n' || c == '\r' || c == '\t') {
        sb.append(' ');
      } else if (Character.isISOControl(c)) {
        // 其它控制字符直接丢弃
      } else {
        sb.append(c);
      }
    }
    String cleaned = sb.toString().trim();
    // 连续下划线/空格压缩，避免 "a___b" / "a  b" 这类难看结果
    cleaned = cleaned.replaceAll("_{2,}", "_").replaceAll(" {2,}", " ");
    if (cleaned.length() > MAX_BODY) cleaned = cleaned.substring(0, MAX_BODY);
    // 全空、或只剩分隔符（如 "///" 清洗成 "_"、纯空格被 trim 成 ""）都要走兜底：
    // 否则会生成 " .json" 或 "___.json" 这种既难看又可能在部分文件系统上异常的名字。
    if (cleaned.isEmpty() || cleaned.replace("_", "").trim().isEmpty()) return "export";
    return cleaned;
  }

  /** 去重结果：去重_1200_2026-11-24.json */
  public static String dedupeJson(int count, String date) {
    return "去重_" + count + "_" + sanitize(date) + ".json";
  }

  /** 分类导出：去重_非HTTP_37_2026-11-24.json */
  public static String categoryJson(String category, int count, String date) {
    return "去重_" + sanitize(category) + "_" + count + "_" + sanitize(date) + ".json";
  }

  /** 校验结果 CSV：校验结果_1200_2026-11-24.csv */
  public static String checkCsv(int count, String date) {
    return "校验结果_" + count + "_" + sanitize(date) + ".csv";
  }

  /** 分享书源：分享书源_50_2026-11-24.json */
  public static String shareJson(int count, String date) {
    return "分享书源_" + count + "_" + sanitize(date) + ".json";
  }

  /** 设置备份：书源工具设置备份_2026-11-24.json */
  public static String settingsBackup(String date) {
    return "书源工具设置备份_" + sanitize(date) + ".json";
  }

  /** 变更报告：变更报告_2026-11-24.json */
  public static String changeReport(String date) {
    return "变更报告_" + sanitize(date) + ".json";
  }
}