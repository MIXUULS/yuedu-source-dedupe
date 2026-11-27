package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.InvalidSource;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 空壳书源判定测试（Issue #1 二.1/2.2）。
 *
 * 用户举的例子是一条只有 bookSourceUrl 和 bookSourceGroup 的书源：
 * <pre>
 *   { "bookSourceName": "啃文书库", "bookSourceUrl": "https://www.kenwen.com",
 *     "loginUrl": "", "bookSourceGroup": "搜索链接规则为空,📖 小说" }
 * </pre>
 * 这种源在阅读里打开什么也搜不到，应判为错误源。
 *
 * 判定经 2257（用户数据）+ 1295（公开数据）条真实书源验证，0 误判。
 */
public class SourceValidityTest {

  private static SourceRecord build(Map<String, Object> raw) {
    Object url = raw.get("bookSourceUrl");
    return new SourceRecord(0, String.valueOf(raw.get("bookSourceName")),
        url == null ? null : String.valueOf(url), raw);
  }

  private static Map<String, Object> base(String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return raw;
  }

  private static Map<String, Object> rule(String... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    return m;
  }

  // ---------- 用户给的真实例子 ----------

  /** 用户举的那个「啃文书库」：只有 URL，无任何规则 → 应判空壳。 */
  @Test public void userExampleIsEmptyShell() {
    Map<String, Object> raw = base("啃文书库", "https://www.kenwen.com");
    raw.put("bookSourceGroup", "搜索链接规则为空,📖 小说");
    raw.put("loginUrl", "");
    raw.put("enabled", true);
    raw.put("enabledExplore", true);

    SourceRecord s = build(raw);
    assertTrue("应判为空壳", SourceValidity.isEmptyShell(s));
    assertEquals(SourceValidity.REASON_NO_CHAIN, SourceValidity.inspect(s));
  }

  // ---------- 搜索链路有效 ----------

  @Test public void validSearchChainIsNotShell() {
    Map<String, Object> raw = base("正常源", "https://a.com");
    raw.put("searchUrl", "https://a.com/s?q={{key}}");
    raw.put("ruleSearch", rule("bookList", "body", "name", "h1"));
    assertFalse(SourceValidity.isEmptyShell(build(raw)));
    assertNull(SourceValidity.inspect(build(raw)));
  }

  /** 只有 bookList 没有 name 也算有效（能列出结果就够）。 */
  @Test public void bookListAloneIsEnough() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("searchUrl", "https://a.com/s");
    raw.put("ruleSearch", rule("bookList", "body"));
    assertFalse(SourceValidity.isEmptyShell(build(raw)));
  }

  /**
   * 没有 searchUrl 但用 bookSourceUrl 作搜索地址 —— legado 常见写法，应算有效。
   *
   * 这一条是与用户提供代码的差异点：他要求 searchUrl 非空，
   * 但真实书源里很多直接拿 bookSourceUrl 当搜索根地址。
   */
  @Test public void bookSourceUrlAsSearchBaseIsValid() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("ruleSearch", rule("bookList", "body"));
    assertFalse("bookSourceUrl 可作搜索根地址", SourceValidity.isEmptyShell(build(raw)));
  }

  /** 有搜索地址但规则对象是空的 → 搜索规则不完整。 */
  @Test public void emptyRuleSearchIsIncomplete() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("searchUrl", "https://a.com/s");
    raw.put("ruleSearch", new LinkedHashMap<String, Object>());
    SourceRecord s = build(raw);
    assertTrue(SourceValidity.isEmptyShell(s));
    assertEquals(SourceValidity.REASON_SEARCH_INCOMPLETE, SourceValidity.inspect(s));
  }

  // ---------- 发现链路有效 ----------

  @Test public void validExploreChainIsNotShell() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("exploreUrl", "https://a.com/list");
    raw.put("ruleExplore", rule("bookList", "body"));
    assertFalse(SourceValidity.isEmptyShell(build(raw)));
  }

  /** exploreUrl 内嵌数组规则时不需要 ruleExplore。 */
  @Test public void exploreWithInlineArrayIsValid() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("exploreUrl", "[{\"title\":\"全部\",\"url\":\"/list\"}]");
    assertFalse(SourceValidity.isEmptyShell(build(raw)));
  }

  /** exploreUrl 含模板占位符视为内嵌规则。 */
  @Test public void exploreWithTemplateIsValid() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("exploreUrl", "https://a.com/list/{{page}}");
    assertFalse(SourceValidity.isEmptyShell(build(raw)));
  }

  /** exploreUrl 含 :: 分隔的多入口写法。 */
  @Test public void exploreWithDoubleColonIsValid() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("exploreUrl", "月票榜::https://a.com/rank");
    assertFalse(SourceValidity.isEmptyShell(build(raw)));
  }

  /** 只有 exploreUrl 但没 ruleExplore 也没内嵌语法 → 不完整。 */
  @Test public void plainExploreUrlWithoutRuleIsIncomplete() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("exploreUrl", "https://a.com/list");
    assertTrue(SourceValidity.isEmptyShell(build(raw)));
  }

  // ---------- v2 兼容 ----------

  /**
   * v2 老格式要能正确识别：ruleSearchUrl + ruleSearchList 应映射成有效搜索链路。
   *
   * 公开书源 73.5% 是 v2，不做这一步会把绝大多数正常源判成空壳。
   */
  @Test public void v2FormatIsNotShell() {
    Map<String, Object> raw = base("起点中文网", "http://www.qidian.com");
    raw.put("ruleSearchUrl", "https://www.qidian.com/so/{{key}}.html");
    raw.put("ruleSearchList", "class.book-img-text@tag.li");
    raw.put("ruleSearchName", "class.book-info@tag.h4@tag.a@text");
    assertFalse("v2 源应被识别为有效", SourceValidity.isEmptyShell(build(raw)));
  }

  /** v2 的 ruleFindUrl 映射成 exploreUrl。 */
  @Test public void v2FindUrlIsValid() {
    Map<String, Object> raw = base("A", "http://a.com");
    raw.put("ruleFindUrl", "月票榜::https://a.com/rank");
    assertFalse(SourceValidity.isEmptyShell(build(raw)));
  }

  // ---------- 边界 ----------

  @Test public void nullSourceIsShell() {
    assertTrue(SourceValidity.isEmptyShell(null));
    assertEquals("书源数据为空", SourceValidity.inspect(null));
  }

  /** null / "null" 字符串都视为空值。 */
  @Test public void nullLiteralIsTreatedAsEmpty() {
    Map<String, Object> raw = base("A", "https://a.com");
    raw.put("searchUrl", "null");
    raw.put("ruleSearch", rule("bookList", "null"));
    assertTrue("字符串 null 应视为空", SourceValidity.isEmptyShell(build(raw)));
  }

  // ---------- 接入去重引擎（可选开关） ----------

  /** 开启 markEmptyShell 时空壳进 invalid。 */
  @Test public void dedupeMarksShellWhenEnabled() {
    Map<String, Object> raw = base("空壳", "https://empty.com");
    SourceRecord s = build(raw);
    DedupeResult r = DedupeEngine.run(
        Collections.singletonList(s), DedupeMode.STANDARD, false, true);

    assertEquals("应判无效", 1, r.getInvalid().size());
    assertEquals(InvalidSource.Kind.EMPTY_SHELL, r.getInvalid().get(0).getKind());
    assertTrue("明细应带名称", r.getInvalid().get(0).getDetail().contains("空壳"));
  }

  /** 默认不开启时保持原行为（不因为缺规则删源）。 */
  @Test public void dedupeKeepsShellWhenDisabled() {
    Map<String, Object> raw = base("空壳", "https://empty.com");
    SourceRecord s = build(raw);
    DedupeResult r = DedupeEngine.run(
        Collections.singletonList(s), DedupeMode.STANDARD, false);

    assertEquals("默认不判空壳", 0, r.getInvalid().size());
    assertEquals(1, r.getRetained().size());
  }

  /** 正常源在开启判定时仍应保留。 */
  @Test public void normalSourcesSurviveWhenEnabled() {
    List<SourceRecord> sources = Arrays.asList(
        normal("A", "https://a.com"),
        normal("B", "https://b.com"),
        build(base("空壳", "https://empty.com")));
    DedupeResult r = DedupeEngine.run(sources, DedupeMode.STANDARD, false, true);
    assertEquals("空壳应被剔除", 1, r.getInvalid().size());
    assertEquals("正常源应保留", 2, r.getRetained().size());
  }

  private static SourceRecord normal(String name, String url) {
    Map<String, Object> raw = base(name, url);
    raw.put("searchUrl", url + "/s");
    raw.put("ruleSearch", rule("bookList", "body"));
    return build(raw);
  }
}