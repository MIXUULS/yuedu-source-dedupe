package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 legado 2.x 老格式书源适配成 3.x 字段。
 *
 * 背景（2026-11-26 用 1399 条真实书源统计发现）：
 * <pre>
 *   仅 v2 老格式（ruleSearchUrl / ruleFindUrl / ruleBookName）  1028 条（73.5%）
 *   仅 v3 新格式（searchUrl / ruleSearch / ruleBookInfo）          371 条
 * </pre>
 * 也就是说**绝大多数书源是老格式**，而应用只认 v3 字段，导致：
 *  - 校验时判「搜索链接规则为空」直接失败
 *  - RuleCompleteness 提示「搜索链接/搜索规则/详情规则…缺失」
 *  - 若接入空壳判定，这 73.5% 会被全部误判为空壳
 *
 * 字段对应关系（v2 → v3）：
 * <pre>
 *   ruleSearchUrl       → searchUrl
 *   ruleSearchList      → ruleSearch.bookList
 *   ruleSearchName      → ruleSearch.name
 *   ruleSearchAuthor    → ruleSearch.author
 *   ruleSearchKind      → ruleSearch.kind
 *   ruleSearchLastChapter → ruleSearch.lastChapter
 *   ruleSearchIntroduce → ruleSearch.intro
 *   ruleSearchCoverUrl  → ruleSearch.coverUrl
 *   ruleSearchNoteUrl   → ruleSearch.bookUrl
 *   ruleFindUrl         → exploreUrl
 *   ruleBookName        → ruleBookInfo.name
 *   ruleBookAuthor      → ruleBookInfo.author
 *   ruleIntroduce       → ruleBookInfo.intro
 *   ruleCoverUrl        → ruleBookInfo.coverUrl
 *   ruleChapterList     → ruleToc.chapterList
 *   ruleChapterName     → ruleToc.chapterName
 *   ruleContentUrl      → ruleToc.chapterUrl
 *   ruleBookContent     → ruleContent.content
 *   enable              → enabled
 * </pre>
 */
public final class V2FieldAdapter {

  private V2FieldAdapter() {}

  /** 该源是否是 v2 老格式（有 v2 专有字段且缺 v3 对应字段）。 */
  public static boolean isV2(SourceRecord s) {
    if (s == null) return false;
    Map<String, Object> raw = s.getRaw();
    if (raw == null) return false;
    return hasV2Marker(raw) && !hasV3Marker(raw);
  }

  private static boolean hasV2Marker(Map<String, Object> raw) {
    return has(raw, "ruleSearchUrl") || has(raw, "ruleFindUrl")
        || has(raw, "ruleBookName") || has(raw, "ruleChapterList")
        || has(raw, "ruleBookContent");
  }

  private static boolean hasV3Marker(Map<String, Object> raw) {
    return has(raw, "searchUrl") || has(raw, "exploreUrl")
        || has(raw, "ruleSearch") || has(raw, "ruleBookInfo")
        || has(raw, "ruleToc") || has(raw, "ruleContent");
  }

  /**
   * 把 v2 字段补成 v3 字段，返回新 map（不修改入参）。
   *
   * 只在 v3 对应字段缺失时补，已有 v3 值一律不动——避免覆盖用户手改过的源。
   */
  public static Map<String, Object> toV3(Map<String, Object> raw) {
    if (raw == null) return null;
    Map<String, Object> out = new LinkedHashMap<>(raw);

    // 搜索
    copyIfAbsent(out, raw, "ruleSearchUrl", "searchUrl");
    Map<String, Object> ruleSearch = buildRule(raw, "ruleSearch",
        new String[][]{
            {"ruleSearchList", "bookList"},
            {"ruleSearchName", "name"},
            {"ruleSearchAuthor", "author"},
            {"ruleSearchKind", "kind"},
            {"ruleSearchLastChapter", "lastChapter"},
            {"ruleSearchIntroduce", "intro"},
            {"ruleSearchCoverUrl", "coverUrl"},
            {"ruleSearchNoteUrl", "bookUrl"},
        });
    if (ruleSearch != null && !out.containsKey("ruleSearch")) out.put("ruleSearch", ruleSearch);

    // 发现
    copyIfAbsent(out, raw, "ruleFindUrl", "exploreUrl");

    // 详情
    Map<String, Object> ruleInfo = buildRule(raw, "ruleBookInfo",
        new String[][]{
            {"ruleBookName", "name"},
            {"ruleBookAuthor", "author"},
            {"ruleIntroduce", "intro"},
            {"ruleCoverUrl", "coverUrl"},
            {"ruleChapterUrl", "tocUrl"},
        });
    if (ruleInfo != null && !out.containsKey("ruleBookInfo")) out.put("ruleBookInfo", ruleInfo);

    // 目录
    Map<String, Object> ruleToc = buildRule(raw, "ruleToc",
        new String[][]{
            {"ruleChapterList", "chapterList"},
            {"ruleChapterName", "chapterName"},
            {"ruleContentUrl", "chapterUrl"},
        });
    if (ruleToc != null && !out.containsKey("ruleToc")) out.put("ruleToc", ruleToc);

    // 正文
    String content = str(raw.get("ruleBookContent"));
    if (content != null && !out.containsKey("ruleContent")) {
      Map<String, Object> rc = new LinkedHashMap<>();
      rc.put("content", content);
      out.put("ruleContent", rc);
    }

    // enable → enabled
    if (!out.containsKey("enabled") && raw.containsKey("enable")) {
      out.put("enabled", raw.get("enable"));
    }
    return out;
  }

  /** 便捷入口：返回适配后的 SourceRecord；非 v2 时原样返回。 */
  public static SourceRecord adapt(SourceRecord s) {
    if (s == null || !isV2(s)) return s;
    return new SourceRecord(s.getOrder(), s.getName(), s.getUrl(), toV3(s.getRaw()));
  }

  /**
   * 按 v2 前缀构造 v3 规则对象；一个都没命中时返回 null。
   */
  private static Map<String, Object> buildRule(
      Map<String, Object> raw, String v3Key, String[][] mapping) {
    Map<String, Object> rule = new LinkedHashMap<>();
    for (String[] pair : mapping) {
      String v2 = pair[0], v3 = pair[1];
      String v = str(raw.get(v2));
      if (v != null && !v.trim().isEmpty()) rule.put(v3, v);
    }
    return rule.isEmpty() ? null : rule;
  }

  private static void copyIfAbsent(Map<String, Object> out, Map<String, Object> raw,
                                   String v2Key, String v3Key) {
    if (out.containsKey(v3Key)) return;
    String v = str(raw.get(v2Key));
    if (v != null && !v.trim().isEmpty()) out.put(v3Key, v);
  }

  private static boolean has(Map<String, Object> raw, String key) {
    Object v = raw.get(key);
    return v != null && !String.valueOf(v).trim().isEmpty();
  }

  private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}