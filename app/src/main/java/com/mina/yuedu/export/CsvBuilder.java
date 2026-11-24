package com.mina.yuedu.export;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;

import java.util.List;

/**
 * 校验结果导出为 CSV 的纯逻辑。
 *
 * 从 MainActivity 抽出来的原因：CSV 的转义规则（逗号 / 引号 / 换行 / 回车）和 BOM 都是有标准可验证的，
 * 但埋在 Activity 里没法单测。
 *
 * 两个历史缺陷在这里一并修掉：
 *  1. 原来只对 {@code ,} {@code "} {@code \n} 加引号，漏了 {@code \r}。
 *     含回车的字段（比如从网页复制的失败原因）会把 CSV 行拆断，Excel 里整行错位。
 *  2. 原来没有 UTF-8 BOM。Excel 在 Windows 上按系统默认编码（简中是 GBK）打开无 BOM 的 UTF-8 文件，
 *     中文全是乱码——而这个功能的卖点就是"可在电脑上打开筛选"。
 */
public final class CsvBuilder {

  /** UTF-8 BOM：Excel 靠它识别文件是 UTF-8，缺了就会按本地编码解析导致中文乱码。 */
  public static final String UTF8_BOM = "\uFEFF";

  private static final String HEADER = "名称,网址,结果,说明,耗时(ms),类型";
  private static final String NEWLINE = "\n";

  private CsvBuilder() {}

  /**
   * @param list 已按期望顺序排好的校验结果（调用方负责排序）
   * @return 带 BOM 的完整 CSV 文本；list 为空时返回 null，调用方据此提示"没有可导出的数据"
   */
  public static String build(List<CheckSourceResult> list) {
    if (list == null || list.isEmpty()) return null;
    StringBuilder sb = new StringBuilder(UTF8_BOM);
    sb.append(HEADER).append(NEWLINE);
    for (CheckSourceResult r : list) {
      if (r == null || r.source == null) continue;
      String name = r.source.getName();
      String url = r.source.getUrl();
      String status = statusLabel(r.status);
      String msg = r.message == null ? "" : r.message;
      String kind = r.kind == null ? SourceKind.NOVEL.label : r.kind.label;
      sb.append(escape(name)).append(',');
      sb.append(escape(url)).append(',');
      sb.append(status).append(',');
      sb.append(escape(msg)).append(',');
      sb.append(r.respondTimeMs).append(',');
      sb.append(kind).append(NEWLINE);
    }
    return sb.toString();
  }

  static String statusLabel(CheckSourceResult.Status st) {
    if (st == CheckSourceResult.Status.SUCCESS) return "可用";
    if (st == CheckSourceResult.Status.TIMEOUT) return "超时";
    return "失败";
  }

  /** 按 RFC 4180 处理：含逗号、双引号、CR、LF 时整体加引号，内部双引号翻倍。 */
  public static String escape(String s) {
    if (s == null) return "";
    if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}