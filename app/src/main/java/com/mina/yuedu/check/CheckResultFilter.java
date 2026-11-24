package com.mina.yuedu.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 校验结果的搜索过滤（按名称或 URL 子串匹配）。
 *
 * 从 MainActivity 抽出来的原因：它和"过滤结果是否失效"这条状态逻辑强相关
 * （历史出过 filteredCheckResults 不刷新、明细操作旧一轮数据的 bug），
 * 而过滤本身是纯函数，能直接单测。
 */
public final class CheckResultFilter {

  private CheckResultFilter() {}

  /**
   * @param results 全量校验结果
   * @param keyword 已 trim 的关键词
   * @return 过滤后的列表；关键词为空时返回 {@code null}，调用方用 null 表示"未启用过滤"。
   *         保留这个 null 语义是因为界面要区分「没有过滤」和「过滤后没匹配到任何结果」。
   */
  public static List<CheckSourceResult> filter(List<CheckSourceResult> results, String keyword) {
    if (keyword == null || keyword.isEmpty()) return null;
    List<CheckSourceResult> out = new ArrayList<>();
    if (results == null) return out;
    String kw = keyword.toLowerCase(Locale.CHINA);
    for (CheckSourceResult r : results) {
      if (r == null || r.source == null) continue;
      String name = r.source.getName();
      String url = r.source.getUrl();
      boolean nameHit = name != null && name.toLowerCase(Locale.CHINA).contains(kw);
      boolean urlHit = url != null && url.toLowerCase(Locale.CHINA).contains(kw);
      if (nameHit || urlHit) out.add(r);
    }
    return out;
  }
}