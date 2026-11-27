package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;

import java.util.Locale;
import java.util.Map;

/**
 * 判断书源是不是「空壳」——没有任何能真正工作的入口。
 *
 * 用户 Issue #1 二.1/2.2 的需求：把「发现链路与搜索链路同时缺失」的源列为错误源。
 * 他给的例子是一条只有 tool 字段的书源：
 * <pre>
 *   { "bookSourceName": "啃文书库", "bookSourceUrl": "https://www.kenwen.com",
 *     "loginUrl": "", "bookSourceGroup": "搜索链接规则为空,📖 小说" }
 * </pre>
 * 这种源在阅读里打开什么也搜不到，导出去也没用。
 *
 * 判定逻辑参考用户提供的 SourceRuleInspector，并做两处修正：
 * <ol>
 *   <li><b>兼容 v2 老格式</b>：公开书源 73.5% 是 v2（ruleSearchUrl / ruleFindUrl），
 *       不先适配会把绝大多数正常源判成空壳</li>
 *   <li><b>搜索链路不强制要求 searchUrl</b>：legado 允许用 bookSourceUrl 作搜索地址，
 *       只要求 ruleSearch 里有 bookList 或 name 即可</li>
 * </ol>
 */
public final class SourceValidity {

  /** 空壳原因文案。 */
  public static final String REASON_NO_CHAIN = "缺乏核心解析规则（搜索与发现都不完整）";
  /** 有搜索地址但规则不全。 */
  public static final String REASON_SEARCH_INCOMPLETE = "搜索规则不完整";

  private SourceValidity() {}

  /**
   * 该书源是否是空壳。
   *
   * 注意：调用方应先用 {@link V2FieldAdapter#adapt} 适配，本方法内部也会做。
   */
  public static boolean isEmptyShell(SourceRecord source) {
    return inspect(source) != null;
  }

  /**
   * 返回空壳原因；不是空壳返回 null。
   *
   * 返回具体原因而不是布尔值，是为了在明细里告诉用户"为什么算无效"。
   */
  public static String inspect(SourceRecord rawSource) {
    if (rawSource == null) return "书源数据为空";
    SourceRecord s = V2FieldAdapter.adapt(rawSource);
    Map<String, Object> raw = s.getRaw();
    if (raw == null) return "书源数据为空";

    boolean searchOk = isSearchChainValid(raw);
    boolean exploreOk = isExploreChainValid(raw);

    if (searchOk || exploreOk) return null;   // 至少有一条链路能工作

    // 有搜索地址但规则不全 → 给更精确的原因
    if (hasText(raw.get("searchUrl"))) return REASON_SEARCH_INCOMPLETE;
    return REASON_NO_CHAIN;
  }

  /**
   * 搜索链路是否可用。
   *
   * 要有搜索地址（searchUrl 或 bookSourceUrl），且 ruleSearch 里有 bookList 或 name。
   * 不强制要求 searchUrl 独立存在——很多源直接用 bookSourceUrl 当搜索根地址。
   */
  static boolean isSearchChainValid(Map<String, Object> raw) {
    boolean hasUrl = hasText(raw.get("searchUrl")) || hasText(raw.get("bookSourceUrl"));
    if (!hasUrl) return false;
    Map<String, Object> rule = ruleOf(raw.get("ruleSearch"));
    if (rule == null) return false;
    // bookList 或 name 有一个就够——这两个是"能不能列出结果"的最小要求
    return hasText(rule.get("bookList")) || hasText(rule.get("name"));
  }

  /**
   * 发现链路是否可用。
   *
   * exploreUrl 里可能内嵌完整规则（数组 / JS / {{…}} 模板），这种情况不需要 ruleExplore。
   * 否则要求 ruleExplore 里有 bookList / name / bookUrl 之一。
   */
  static boolean isExploreChainValid(Map<String, Object> raw) {
    String explore = str(raw.get("exploreUrl")).trim();
    if (explore.isEmpty()) return false;

    // 内嵌规则：exploreUrl 自身就带规则定义
    String lower = explore.toLowerCase(Locale.ROOT);
    if (explore.startsWith("[") || lower.contains("<js>") || lower.contains("js:")
        || lower.contains("@js") || explore.contains("{{")
        || explore.contains("::") || explore.contains("##") || explore.contains("!!")) {
      return true;
    }

    // 普通 URL：需要 ruleExplore 支撑
    Map<String, Object> rule = ruleOf(raw.get("ruleExplore"));
    if (rule == null) return false;
    return hasText(rule.get("bookList")) || hasText(rule.get("name"))
        || hasText(rule.get("bookUrl"));
  }

  /** 把规则字段统一成 Map；字符串形式的规则（JSON 串）无法解析时返回 null。 */
  private static Map<String, Object> ruleOf(Object v) {
    if (v instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) v;
      return m.isEmpty() ? null : m;
    }
    return null;
  }

  private static boolean hasText(Object v) {
    if (v == null) return false;
    String s = String.valueOf(v).trim();
    return !s.isEmpty() && !"null".equalsIgnoreCase(s);
  }

  private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}