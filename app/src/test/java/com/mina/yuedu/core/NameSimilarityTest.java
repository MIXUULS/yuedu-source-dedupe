package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * 名称相似度计算工具单元测试。
 */
public class NameSimilarityTest {

  @Test public void identicalNamesAreSimilar() {
    assertTrue(NameSimilarity.isSimilar("笔趣阁", "笔趣阁", 0.75));
    assertTrue(NameSimilarity.isSimilar("", "", 0.75));
  }

  @Test public void nullHandling() {
    assertFalse(NameSimilarity.isSimilar(null, "abc", 0.75));
    assertFalse(NameSimilarity.isSimilar("abc", null, 0.75));
    assertFalse(NameSimilarity.isSimilar(null, null, 0.75));
    assertEquals(0.0, NameSimilarity.similarity(null, "abc"), 0.001);
    assertEquals(0.0, NameSimilarity.similarity("abc", null), 0.001);
  }

  @Test public void normalizingRemovesModifiers() {
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁小说网"));
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁(官方)"));
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁（官方）"));
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁 官方 备用"));
  }

  @Test public void similarNamesAreDetected() {
    // 名称相似但不同
    assertTrue(NameSimilarity.isSimilar("笔趣阁", "笔趣阁小说网", 0.75));
    assertTrue(NameSimilarity.isSimilar("全本小说网", "全本小说网", 0.75));
    // 名称差异较大
    assertFalse(NameSimilarity.isSimilar("笔趣阁", "起点中文网", 0.75));
    assertFalse(NameSimilarity.isSimilar("全本小说网", "笔趣阁", 0.75));
  }

  @Test public void preferredKeywordDetection() {
    assertTrue(NameSimilarity.hasPreferredKeyword("笔趣阁官方"));
    assertTrue(NameSimilarity.hasPreferredKeyword("起点中文网(原版)"));
    assertFalse(NameSimilarity.hasPreferredKeyword("笔趣阁"));
    assertFalse(NameSimilarity.hasPreferredKeyword(null));
  }

  @Test public void levenshteinDistance() {
    assertEquals(0, NameSimilarity.levenshtein("", ""));
    assertEquals(3, NameSimilarity.levenshtein("abc", ""));
    assertEquals(3, NameSimilarity.levenshtein("", "abc"));
    assertEquals(0, NameSimilarity.levenshtein("abc", "abc"));
    assertEquals(1, NameSimilarity.levenshtein("abc", "abd"));
    assertEquals(1, NameSimilarity.levenshtein("abc", "ac"));  // 删除 'b'
    assertEquals(2, NameSimilarity.levenshtein("abc", "a"));   // 删除 'b','c'
  }

  @Test public void nameGroupingGroupsSimilarSources() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://a.com"),
        source(1, "笔趣阁小说网", "https://b.com"),
        source(2, "起点中文网", "https://c.com"),
        source(3, "笔趣阁(官方)", "https://d.com")
    );
    List<List<SourceRecord>> groups = NameSimilarity.groupByName(sources);
    // 应该有一组：3个"笔趣阁"相关源
    boolean found = false;
    for (List<SourceRecord> g : groups) {
      if (g.size() >= 3) {
        found = true;
        // 第一个应该是评分最高的（含"官方"的）
        assertTrue(g.get(0).getName().contains("官方"));
        break;
      }
    }
    assertTrue("应找到包含3个笔趣阁相关源的组", found);
  }

  @Test public void nameGroupingKeepsDissimilarSeparate() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://a.com"),
        source(1, "起点中文网", "https://b.com"),
        source(2, "全本小说网", "https://c.com")
    );
    List<List<SourceRecord>> groups = NameSimilarity.groupByName(sources);
    // 3个不同名称，不应有分组
    assertTrue("不同名称不应分组", groups.isEmpty());
  }

  @Test public void nameGroupingHandlesLargeLengthDifferencesWithoutFalseMatch() {
    assertFalse(NameSimilarity.isSimilar("完整书源名称甲", "完整书源名称甲以及完全不同的长长长长长长长长长后缀", 0.75));
  }

  @Test public void shortNamesMustBeExactMatch() {
    // 短名称（≤4字符）归一化后必须完全相等才视为相似，避免误合并
    assertFalse(NameSimilarity.isSimilar("AB", "AC", 0.75)); // 不同短名
    assertTrue(NameSimilarity.isSimilar("AB", "AB", 0.75));  // 相同短名
    assertTrue(NameSimilarity.isSimilar("AB", "AB小说网", 0.75)); // "AB小说网"→"AB" 归一化后相等
    assertFalse(NameSimilarity.isSimilar("AB", "CD小说网", 0.75)); // 不同短名
  }

  @Test public void dedupeEngineNameModeMergesByName() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://a.com"),
        source(1, "笔趣阁小说网", "https://b.com"),
        source(2, "起点中文网", "https://c.com")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    // 应合并"笔趣阁"和"笔趣阁小说网"，保留"起点中文网"
    assertEquals(2, result.getRetained().size());
    assertEquals(1, result.getDuplicateGroups().size());
    assertEquals("名称相似度去重：名称相同或高度相似",
        result.getDuplicateGroups().get(0).getReason());
  }

  @Test public void dedupeEngineNameModeWithPreferredKeyword() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://a.com"),
        source(1, "笔趣阁(官方)", "https://b.com")  // 官方优先
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals(1, result.getRetained().size());
    // 应保留含"官方"的源
    assertTrue(result.getRetained().get(0).getName().contains("官方"));
  }

  @Test public void dedupeEngineNameModeWithCleanNames() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁\uD83D\uDE00", "https://a.com"),  // 带 emoji
        source(1, "笔趣阁小说网", "https://b.com")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, true);
    assertEquals(1, result.getRetained().size());
    // 清理后名称应不含 emoji 和"小说网"等修饰词
    String name = result.getRetained().get(0).getName();
    assertFalse(name.contains("\uD83D\uDE00"));
  }

  @Test public void nameModeHandlesEmptySources() {
    DedupeResult result = DedupeEngine.run(new ArrayList<SourceRecord>(), DedupeMode.NAME, false);
    assertEquals(0, result.getRetained().size());
    assertTrue(result.getInvalid().isEmpty());
  }

  @Test public void nameModeHandlesNullUrl() {
    List<SourceRecord> sources = new ArrayList<>();
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "测试源");
    sources.add(new SourceRecord(0, "测试源", null, raw));
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals(0, result.getRetained().size());
    assertEquals(1, result.getInvalid().size());
  }

  @Test public void nameModeClassifiesEmptyUrlCorrectly() {
    List<SourceRecord> sources = Collections.singletonList(source(0, "测试源", "  "));
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertTrue(result.getRetained().isEmpty());
    assertEquals(1, result.getInvalid().size());
    assertEquals(com.mina.yuedu.model.InvalidSource.Kind.EMPTY_URL,
        result.getInvalid().get(0).getKind());
  }

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(order, name, url, raw);
  }
}
