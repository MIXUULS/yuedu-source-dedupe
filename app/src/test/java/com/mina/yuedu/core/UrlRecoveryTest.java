package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * URL 恢复测试（Issue #1 一.2）。
 *
 * 问题：bookSourceUrl 为空的书源原本直接判为「URL 为空」丢弃，
 * 但 searchUrl / ruleSearch.baseUrl 里往往有可用地址。
 */
public class UrlRecoveryTest {

  private static SourceRecord withRaw(Map<String, Object> raw) {
    Object url = raw.get("bookSourceUrl");
    return new SourceRecord(0, String.valueOf(raw.get("bookSourceName")), 
        url == null ? null : String.valueOf(url), raw);
  }

  private static Map<String, Object> base(String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    if (url != null) raw.put("bookSourceUrl", url);
    return raw;
  }

  // ---------- 直接可用的 URL ----------

  @Test public void keepsExistingUrl() {
    assertEquals("https://a.com", UrlRecovery.recover(withRaw(base("A", "https://a.com"))));
  }

  @Test public void trimsWhitespace() {
    assertEquals("https://a.com", UrlRecovery.recover(withRaw(base("A", "  https://a.com  "))));
  }

  // ---------- 从 searchUrl 恢复 ----------

  /** 核心场景：bookSourceUrl 为空，searchUrl 里有完整地址。 */
  @Test public void recoversFromSearchUrl() {
    Map<String, Object> raw = base("A", null);
    raw.put("searchUrl", "https://real.com/search?q={{key}}");
    assertEquals("https://real.com", UrlRecovery.recover(withRaw(raw)));
  }

  /** 恢复出的只是 scheme://host，不带路径与查询。 */
  @Test public void recoveryStripsPathAndQuery() {
    Map<String, Object> raw = base("A", null);
    raw.put("searchUrl", "https://real.com/a/b/c?x=1#frag");
    String got = UrlRecovery.recover(withRaw(raw));
    assertEquals("https://real.com", got);
    assertFalse("不应带路径", got.contains("/a/"));
  }

  /** 非默认端口要保留。 */
  @Test public void recoveryKeepsNonDefaultPort() {
    Map<String, Object> raw = base("A", null);
    raw.put("searchUrl", "https://real.com:8443/search");
    assertEquals("https://real.com:8443", UrlRecovery.recover(withRaw(raw)));
  }

  /** 默认端口要剥掉，避免同一站出现两种 key。 */
  @Test public void recoveryStripsDefaultPort() {
    Map<String, Object> raw = base("A", null);
    raw.put("searchUrl", "https://real.com:443/search");
    assertEquals("https://real.com", UrlRecovery.recover(withRaw(raw)));

    Map<String, Object> raw2 = base("B", null);
    raw2.put("searchUrl", "http://real.com:80/x");
    assertEquals("http://real.com", UrlRecovery.recover(withRaw(raw2)));
  }

  // ---------- 从规则 baseUrl 恢复 ----------

  @Test public void recoversFromRuleSearchBaseUrl() {
    Map<String, Object> raw = base("A", null);
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("baseUrl", "https://from-rule.com");
    rule.put("bookList", "body");
    raw.put("ruleSearch", rule);
    assertEquals("https://from-rule.com", UrlRecovery.recover(withRaw(raw)));
  }

  @Test public void recoversFromRuleExploreBaseUrl() {
    Map<String, Object> raw = base("A", null);
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("baseUrl", "https://explore.com");
    raw.put("ruleExplore", rule);
    assertEquals("https://explore.com", UrlRecovery.recover(withRaw(raw)));
  }

  /** searchUrl 优先于规则 baseUrl（前者更直接）。 */
  @Test public void searchUrlTakesPriority() {
    Map<String, Object> raw = base("A", null);
    raw.put("searchUrl", "https://from-search.com/x");
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("baseUrl", "https://from-rule.com");
    raw.put("ruleSearch", rule);
    assertEquals("https://from-search.com", UrlRecovery.recover(withRaw(raw)));
  }

  // ---------- 从 exploreUrl 恢复 ----------

  @Test public void recoversFromExploreUrl() {
    Map<String, Object> raw = base("A", null);
    raw.put("exploreUrl", "https://explore.com/list");
    assertEquals("https://explore.com", UrlRecovery.recover(withRaw(raw)));
  }

  /** exploreUrl 多行时取第一个非空。 */
  @Test public void exploreUrlTakesFirstLine() {
    Map<String, Object> raw = base("A", null);
    raw.put("exploreUrl", "\n\nhttps://first.com/a\nhttps://second.com/b");
    assertEquals("https://first.com", UrlRecovery.recover(withRaw(raw)));
  }

  // ---------- 无法恢复的情况 ----------

  @Test public void returnsNullWhenNothingUsable() {
    assertNull(UrlRecovery.recover(withRaw(base("A", null))));
    assertNull(UrlRecovery.recover(null));
  }

  /** 相对地址无法确定主机，放弃。 */
  @Test public void relativeUrlIsNotUsable() {
    Map<String, Object> raw = base("A", null);
    raw.put("searchUrl", "/search?q={{key}}");
    assertNull(UrlRecovery.recover(withRaw(raw)));
  }

  @Test public void isRecoverableReflectsState() {
    Map<String, Object> raw = base("A", null);
    raw.put("searchUrl", "https://real.com/x");
    assertTrue(UrlRecovery.isRecoverable(withRaw(raw)));

    assertFalse("已有 URL 不算'可恢复'", UrlRecovery.isRecoverable(withRaw(base("B", "https://b.com"))));
    assertFalse(UrlRecovery.isRecoverable(withRaw(base("C", null))));
  }

  // ---------- 接入 DedupeEngine ----------

  /** 端到端：URL 为空但有 searchUrl 的书源不应被判无效。 */
  @Test public void dedupeDoesNotDropRecoverableSource() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "可恢复");
    raw.put("searchUrl", "https://real.com/search?q={{key}}");
    SourceRecord s = new SourceRecord(0, "可恢复", null, raw);

    DedupeResult r = DedupeEngine.run(Collections.singletonList(s), DedupeMode.STANDARD, false);
    assertEquals("不应判为无效", 0, r.getInvalid().size());
    assertEquals("应保留 1 条", 1, r.getRetained().size());
    assertEquals("恢复出的 URL 应写回记录", "https://real.com", r.getRetained().get(0).getUrl());
  }

  /** 恢复后的源能与同站的正常源合并（证明恢复值参与去重 key）。 */
  @Test public void recoveredSourceMergesWithSameHost() {
    Map<String, Object> recoverable = new LinkedHashMap<>();
    recoverable.put("bookSourceName", "可恢复");
    recoverable.put("searchUrl", "https://same.com/search");
    SourceRecord a = new SourceRecord(0, "可恢复", null, recoverable);

    Map<String, Object> normal = new LinkedHashMap<>();
    normal.put("bookSourceName", "正常");
    normal.put("bookSourceUrl", "https://same.com");
    SourceRecord b = new SourceRecord(1, "正常", "https://same.com", normal);

    DedupeResult r = DedupeEngine.run(Arrays.asList(a, b), DedupeMode.STANDARD, false);
    assertEquals("同站应合并为 1 条", 1, r.getRetained().size());
    assertEquals("应记为 1 条重复", 1, r.getDuplicateCount());
  }

  /** 真正无法恢复的（URL 空且无其他来源）仍要判无效。 */
  @Test public void trulyEmptyUrlStillInvalid() {
    SourceRecord s = new SourceRecord(0, "空", "   ", new LinkedHashMap<String, Object>());
    DedupeResult r = DedupeEngine.run(Collections.singletonList(s), DedupeMode.STANDARD, false);
    assertEquals(1, r.getInvalid().size());
    assertTrue(r.getRetained().isEmpty());
  }

  /** NAME 模式同样享受恢复能力。 */
  @Test public void nameModeAlsoRecovers() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "可恢复");
    raw.put("searchUrl", "https://real.com/x");
    SourceRecord s = new SourceRecord(0, "可恢复", null, raw);

    DedupeResult r = DedupeEngine.run(Collections.singletonList(s), DedupeMode.NAME, false);
    assertEquals("NAME 模式也不应判无效", 0, r.getInvalid().size());
    assertEquals(1, r.getRetained().size());
  }
}