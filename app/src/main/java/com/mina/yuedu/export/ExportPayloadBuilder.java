package com.mina.yuedu.export;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.core.MiniJson;
import com.mina.yuedu.core.TextKit;
import com.mina.yuedu.model.SourceRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出书源时改写 {@code bookSourceGroup} / {@code bookSourceComment} 的纯逻辑。
 *
 * 从 MainActivity 抽出来的原因：这段是导出流程里最容易出错的部分（历史上出过
 * "日期标记被分类标签覆盖"和"旧标记层层堆叠"两次 bug），而它本身不依赖任何 Android API，
 * 完全可以单测。抽出来后 Activity 只剩一行调用，回归测试能直接守住行为。
 *
 * 分组规则：
 * <pre>
 *   最终 bookSourceGroup = 原分组 + 分类标签（重复则不加） + ✔日期标记
 * </pre>
 *
 * 校验备注规则：
 * <pre>
 *   bookSourceComment 追加 "类型:失败步骤"，已包含则不重复追加
 * </pre>
 */
public final class ExportPayloadBuilder {

  /** 旧格式日期标记：形如 "✔2026-8-23小说（120）"，导出前要先清掉避免层层堆叠。 */
  private static final String OLD_MARK_REGEX = "(?:,)?✔\\d{4}-\\d{1,2}-\\d{1,2}[^，^,]*（\\d+）";

  private ExportPayloadBuilder() {}

  /**
   * 构建可直接序列化为 JSON 的书源数组。
   *
   * @param list         待导出的书源
   * @param checkResults 校验结果（用于附加失败步骤备注），可为 null
   * @param dateStamp    日期标记，形如 "2026-11-24"；由调用方注入以便测试
   * @return 可序列化的 Map 列表；list 为空时返回空列表
   */
  public static List<Object> build(List<SourceRecord> list,
                                   List<CheckSourceResult> checkResults,
                                   String dateStamp) {
    if (list == null || list.isEmpty()) return Collections.emptyList();
    String stamp = dateStamp == null ? "" : dateStamp;

    // 按类型统计数量，用于生成分类标记（每个类型一个，如 "✔2026-11-24小说（120）"）
    Map<SourceKind, Integer> kindCounts = new LinkedHashMap<>();
    for (SourceRecord s : list) {
      SourceKind k = SourceKind.of(s);
      kindCounts.put(k, kindCounts.getOrDefault(k, 0) + 1);
    }
    Map<SourceKind, String> kindMarks = new LinkedHashMap<>();
    for (Map.Entry<SourceKind, Integer> e : kindCounts.entrySet()) {
      kindMarks.put(e.getKey(), "✔" + stamp + e.getKey().label + "（" + e.getValue() + "）");
    }

    // 校验结果按 URL 建索引。原来是"每条书源遍历整个 checkResults"的 O(n×m)，
    // 万级导出时是上亿次字符串比较，会让保存/导入界面卡死。
    Map<String, CheckSourceResult> checkByUrl = new HashMap<>();
    if (checkResults != null) {
      for (CheckSourceResult cr : checkResults) {
        if (cr != null && cr.source != null && cr.source.getUrl() != null) {
          checkByUrl.put(cr.source.getUrl(), cr);
        }
      }
    }

    List<Object> payload = new ArrayList<>(list.size());
    for (SourceRecord s : list) {
      Map<String, Object> m = new LinkedHashMap<>(s.getRaw());
      m.put("bookSourceName", s.getName());
      SourceKind kind = SourceKind.of(s);
      String mark = kindMarks.getOrDefault(kind, "✔" + stamp + "书源（" + list.size() + "）");

      m.put("bookSourceGroup", buildGroup(m.get("bookSourceGroup"), kind, mark));
      applyCheckComment(m, checkByUrl.get(s.getUrl()));
      payload.add(m);
    }
    return payload;
  }

  /**
   * 组装 bookSourceGroup。
   *
   * 必须基于同一份 parts 一次性写入：历史实现里有两处各自 put("bookSourceGroup", …)，
   * 第二处基于不含标记的旧串重建，会把刚写入的日期标记覆盖掉。
   */
  static String buildGroup(Object rawGroup, SourceKind kind, String mark) {
    String gs = rawGroup == null ? "" : String.valueOf(rawGroup);
    gs = gs.replaceAll(OLD_MARK_REGEX, "");
    if (gs.startsWith(",")) gs = gs.substring(1);

    List<String> parts = new ArrayList<>(Arrays.asList(gs.isEmpty() ? new String[0] : gs.split(",")));
    String kindLabel = kind == null ? null : kind.label;
    if (kindLabel != null && !kindLabel.isEmpty()) {
      boolean hasKind = false;
      for (String part : parts) {
        if (part.trim().equals(kindLabel)) { hasKind = true; break; }
      }
      if (!hasKind) parts.add(kindLabel);
    }
    parts.add(mark);
    return TextKit.join(",", parts).replace(",,", ",");
  }

  /** 把校验失败步骤追加到 bookSourceComment，已经包含同一段说明时不重复追加。 */
  static void applyCheckComment(Map<String, Object> m, CheckSourceResult cr) {
    if (cr == null || cr.groups == null || cr.groups.isEmpty()) return;
    String prefix = (cr.kind == null ? SourceKind.NOVEL : cr.kind).label;
    String cg = prefix + ":" + TextKit.join(",", cr.groups);
    Object comment = m.get("bookSourceComment");
    String c = comment == null ? "" : String.valueOf(comment);
    if (!c.contains(cg)) m.put("bookSourceComment", c.isEmpty() ? cg : c + " | " + cg);
  }

  /** 序列化为 JSON；payload 为空时返回 null（调用方据此提示"没有可保存的数据"）。 */
  public static String toJson(List<SourceRecord> list,
                              List<CheckSourceResult> checkResults,
                              String dateStamp) {
    List<Object> payload = build(list, checkResults, dateStamp);
    if (payload.isEmpty()) return null;
    // 预分配容量：按平均 2KB/条估算、上限 32MB，避免万级导出时 StringBuilder 反复扩容 OOM
    int cap = (int) Math.min((long) payload.size() * 2048L, 32L * 1024L * 1024L);
    return MiniJson.stringify(payload, cap);
  }
}