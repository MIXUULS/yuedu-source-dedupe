package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * v2 老格式书源适配测试。
 *
 * 数据来源：1399 条真实书源统计
 * <pre>
 *   仅 v2 老格式（ruleSearchUrl / ruleFindUrl / ruleBookName）  1028 条（73.5%）
 *   仅 v3 新格式（searchUrl / ruleSearch / ruleBookInfo）          371 条
 * </pre>
 * 应用原本只认 v3 字段，这 1028 条会被判「搜索链接规则为空」而校验失败。
 */
public class V2FieldAdapterTest {

  /** 构造一个真实形态的 v2 书源（取自「起点中文网」精简版）。 */
  private static SourceRecord v2Source() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "起点中文网");
    raw.put("bookSourceUrl", "http://www.qidian.com");
    raw.put("bookSourceType", "TEXT");
    raw.put("enable", true);
    raw.put("ruleSearchUrl", "https://www.qidian.com/so/{{key}}.html");
    raw.put("ruleSearchList", "class.book-img-text@tag.li");
    raw.put("ruleSearchName", "class.book-info@tag.h4@tag.a@text");
    raw.put("ruleSearchAuthor", "class.book-info@tag.p.0@tag.a@text");
    raw.put("ruleSearchNoteUrl", "class.book-info@tag.h4@tag.a@href");
    raw.put("ruleFindUrl", "月票榜::https://www.qidian.com/rank/yuepiao&&推荐榜::https://www.qidian.com/rank/recom");
    raw.put("ruleBookName", "class.book-info@tag.h1@tag.em@text");
    raw.put("ruleBookAuthor", "class.book-info@tag.h1@tag.a@text");
    raw.put("ruleIntroduce", "class.intro@text");
    raw.put("ruleChapterList", "class.volume-wrap@tag.li");
    raw.put("ruleChapterName", "tag.a@text");
    raw.put("ruleContentUrl", "tag.a@href");
    raw.put("ruleBookContent", "class.read-content@tag.p@text");
    return new SourceRecord(0, "起点中文网", "http://www.qidian.com", raw);
  }

  private static SourceRecord v3Source() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "新格式源");
    raw.put("bookSourceUrl", "https://new.com");
    raw.put("searchUrl", "https://new.com/s?q={{key}}");
    Map<String, Object> rs = new LinkedHashMap<>();
    rs.put("bookList", "body");
    raw.put("ruleSearch", rs);
    return new SourceRecord(1, "新格式源", "https://new.com", raw);
  }

  // ---------- 识别 ----------

  @Test public void detectsV2Format() {
    assertTrue(V2FieldAdapter.isV2(v2Source()));
  }

  @Test public void v3IsNotV2() {
    assertFalse(V2FieldAdapter.isV2(v3Source()));
  }

  @Test public void nullSafe() {
    assertFalse(V2FieldAdapter.isV2(null));
    assertNull(V2FieldAdapter.adapt(null));
    assertNull(V2FieldAdapter.toV3(null));
  }

  // ---------- 字段映射 ----------

  @Test public void mapsSearchFields() {
    Map<String, Object> v3 = V2FieldAdapter.toV3(v2Source().getRaw());
    assertEquals("https://www.qidian.com/so/{{key}}.html", v3.get("searchUrl"));

    @SuppressWarnings("unchecked")
    Map<String, Object> rs = (Map<String, Object>) v3.get("ruleSearch");
    assertNotNull("应构造出 ruleSearch", rs);
    assertEquals("class.book-img-text@tag.li", rs.get("bookList"));
    assertEquals("class.book-info@tag.h4@tag.a@text", rs.get("name"));
    assertEquals("class.book-info@tag.p.0@tag.a@text", rs.get("author"));
    assertEquals("class.book-info@tag.h4@tag.a@href", rs.get("bookUrl"));
  }

  @Test public void mapsExploreFields() {
    Map<String, Object> v3 = V2FieldAdapter.toV3(v2Source().getRaw());
    assertNotNull("ruleFindUrl 应映射为 exploreUrl", v3.get("exploreUrl"));
    assertTrue(String.valueOf(v3.get("exploreUrl")).contains("月票榜"));
  }

  @Test public void mapsBookInfoFields() {
    Map<String, Object> v3 = V2FieldAdapter.toV3(v2Source().getRaw());
    @SuppressWarnings("unchecked")
    Map<String, Object> info = (Map<String, Object>) v3.get("ruleBookInfo");
    assertNotNull(info);
    assertEquals("class.book-info@tag.h1@tag.em@text", info.get("name"));
    assertEquals("class.book-info@tag.h1@tag.a@text", info.get("author"));
    assertEquals("class.intro@text", info.get("intro"));
  }

  @Test public void mapsTocFields() {
    Map<String, Object> v3 = V2FieldAdapter.toV3(v2Source().getRaw());
    @SuppressWarnings("unchecked")
    Map<String, Object> toc = (Map<String, Object>) v3.get("ruleToc");
    assertNotNull(toc);
    assertEquals("class.volume-wrap@tag.li", toc.get("chapterList"));
    assertEquals("tag.a@text", toc.get("chapterName"));
    assertEquals("tag.a@href", toc.get("chapterUrl"));
  }

  @Test public void mapsContentField() {
    Map<String, Object> v3 = V2FieldAdapter.toV3(v2Source().getRaw());
    @SuppressWarnings("unchecked")
    Map<String, Object> content = (Map<String, Object>) v3.get("ruleContent");
    assertNotNull(content);
    assertEquals("class.read-content@tag.p@text", content.get("content"));
  }

  @Test public void mapsEnableToEnabled() {
    Map<String, Object> v3 = V2FieldAdapter.toV3(v2Source().getRaw());
    assertEquals(Boolean.TRUE, v3.get("enabled"));
  }

  // ---------- 不覆盖已有 v3 值 ----------

  /** 混合格式时，已有 v3 字段一律不动（避免覆盖用户手改过的源）。 */
  @Test public void doesNotOverwriteExistingV3Fields() {
    Map<String, Object> raw = new LinkedHashMap<>(v2Source().getRaw());
    raw.put("searchUrl", "https://custom.example.com/search");
    Map<String, Object> v3 = V2FieldAdapter.toV3(raw);
    assertEquals("已有 v3 searchUrl 不应被覆盖",
        "https://custom.example.com/search", v3.get("searchUrl"));
  }

  /** 不修改入参 map。 */
  @Test public void doesNotMutateInput() {
    SourceRecord s = v2Source();
    int before = s.getRaw().size();
    V2FieldAdapter.toV3(s.getRaw());
    assertEquals("原 map 不应被改动", before, s.getRaw().size());
    assertNull("原 map 不应被加入 v3 字段", s.getRaw().get("searchUrl"));
  }

  /** v3 源原样返回。 */
  @Test public void v3SourceUnchanged() {
    SourceRecord s = v3Source();
    assertSame(s, V2FieldAdapter.adapt(s));
  }

  // ---------- 接入去重引擎的端到端 ----------

  /**
   * 核心回归：v2 书源不应被判"规则缺失"。
   *
   * RuleCompleteness 内部会自动适配 v2，所以这里验证最终结果为空。
   * （改之前这条会返回 ["搜索链接","搜索规则","详情规则","目录规则","正文规则"]）
   */
  @Test public void v2SourcePassesRuleCompleteness() {
    java.util.List<String> missing = RuleCompleteness.missingParts(v2Source());
    assertTrue("v2 书源适配后不应报缺失，实际: " + missing, missing.isEmpty());
    assertTrue("应判定为规则完整", RuleCompleteness.isComplete(v2Source()));
  }

  /** 真正缺规则的源仍要报缺失（不能因为适配把什么都放行）。 */
  @Test public void trulyIncompleteSourceStillReported() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "空壳");
    raw.put("bookSourceUrl", "https://empty.com");
    SourceRecord s = new SourceRecord(0, "空壳", "https://empty.com", raw);

    java.util.List<String> missing = RuleCompleteness.missingParts(s);
    assertFalse("真正缺规则的必须报出来", missing.isEmpty());
    assertTrue(missing.contains("搜索规则"));
  }

  /** v2 书源去重结果正常。 */
  @Test public void v2SourceDedupesNormally() {
    DedupeResult r = DedupeEngine.run(
        Collections.singletonList(v2Source()), DedupeMode.STANDARD, false);
    assertEquals("不应判无效", 0, r.getInvalid().size());
    assertEquals(1, r.getRetained().size());
  }

  /** v2 与 v3 同站应能合并（适配后 URL 参与 key）。 */
  @Test public void v2AndV3SameHostMerge() {
    Map<String, Object> v2raw = new LinkedHashMap<>(v2Source().getRaw());
    v2raw.put("bookSourceUrl", "https://same.com");
    SourceRecord a = new SourceRecord(0, "v2源", "https://same.com", v2raw);

    Map<String, Object> v3raw = new LinkedHashMap<>();
    v3raw.put("bookSourceName", "v3源");
    v3raw.put("bookSourceUrl", "https://same.com");
    v3raw.put("searchUrl", "https://same.com/s");
    SourceRecord b = new SourceRecord(1, "v3源", "https://same.com", v3raw);

    DedupeResult r = DedupeEngine.run(Arrays.asList(a, b), DedupeMode.STANDARD, false);
    assertEquals("同站应合并为 1 条", 1, r.getRetained().size());
  }
}