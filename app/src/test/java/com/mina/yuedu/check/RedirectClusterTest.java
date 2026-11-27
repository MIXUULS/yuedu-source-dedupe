package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 重定向去重测试。
 *
 * 用户反馈（Issue #1 三.2）：很多书源 URL 重定向后其实是同一个网站。
 * aaa.com 与 bbb.com 都 301 到 ccc.com，按原 URL 去重看不出冗余。
 */
public class RedirectClusterTest {

  private static SourceRecord src(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }

  private static Map<String, String> map(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    return m;
  }

  // ---------- 主机提取 ----------

  @Test public void extractsHost() {
    assertEquals("a.com", RedirectCluster.hostOf("https://a.com/path?x=1"));
    assertEquals("a.com", RedirectCluster.hostOf("http://a.com:8080/p"));
  }

  /** #身份标签不是真实地址的一部分。 */
  @Test public void ignoresIdentityTag() {
    assertEquals("a.com", RedirectCluster.hostOf("https://a.com#简体"));
  }

  /** 中文域名 URI 拿不到 host 时用 authority 兜底。 */
  @Test public void handlesChineseDomain() {
    String h = RedirectCluster.hostOf("https://例子.com/path");
    assertNotNull("中文域名应能提取到主机", h);
    assertTrue("实际: " + h, h.contains("例子") || h.length() > 0);
  }

  @Test public void nullSafeHostOf() {
    assertNull(RedirectCluster.hostOf(null));
    assertNull(RedirectCluster.hostOf(""));
    assertNull(RedirectCluster.hostOf("不是网址"));
  }

  // ---------- 主机归一化 ----------

  @Test public void normalizesMobilePrefixes() {
    assertEquals("a.com", RedirectCluster.normalizeHost("www.a.com"));
    assertEquals("a.com", RedirectCluster.normalizeHost("m.a.com"));
    assertEquals("a.com", RedirectCluster.normalizeHost("wap.a.com"));
    assertEquals("a.com", RedirectCluster.normalizeHost("mobile.a.com"));
  }

  @Test public void keepsBareDomain() {
    assertEquals("a.com", RedirectCluster.normalizeHost("a.com"));
    assertEquals("sub.a.com", RedirectCluster.normalizeHost("sub.a.com"));
  }

  /** 剥完只剩顶级域的不剥（www.com 本身可能是裸域）。 */
  @Test public void doesNotStripToTldOnly() {
    assertEquals("www.com", RedirectCluster.normalizeHost("www.com"));
  }

  // ---------- 成组 ----------

  /** 核心场景：两个不同域名重定向到同一个站点 → 应成组。 */
  @Test public void clustersDifferentOriginsPointingToSameSite() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A", "https://aaa.com"),
        src(1, "B", "https://bbb.com"));
    Map<String, String> resolved = map(
        "https://aaa.com", "https://ccc.com/",
        "https://bbb.com", "https://ccc.com/");

    List<RedirectCluster.Group> groups = RedirectCluster.cluster(sources, resolved);
    assertEquals("应聚成 1 组", 1, groups.size());
    assertEquals("ccc.com", groups.get(0).finalHost);
    assertEquals(2, groups.get(0).size());
    assertTrue("应是跨主机", groups.get(0).hasMultipleOrigins());
  }

  /** 没发生跨域重定向的不能成组（普通去重已覆盖）。 */
  @Test public void ignoresSameHostRedirect() {
    List<SourceRecord> sources = Collections.singletonList(src(0, "A", "https://aaa.com"));
    Map<String, String> resolved = map("https://aaa.com", "https://aaa.com/home");
    assertTrue("同主机不算重定向去重收益",
        RedirectCluster.cluster(sources, resolved).isEmpty());
  }

  /** 只有一条落到某站点的不成组。 */
  @Test public void singleMemberDoesNotCluster() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A", "https://aaa.com"),
        src(1, "B", "https://bbb.com"));
    Map<String, String> resolved = map(
        "https://aaa.com", "https://ccc.com/",
        "https://bbb.com", "https://other.com/");   // 落到不同站
    assertTrue(RedirectCluster.cluster(sources, resolved).isEmpty());
  }

  /** 探测失败的（结果里没有）不参与成组。 */
  @Test public void failedProbesExcluded() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A", "https://aaa.com"),
        src(1, "B", "https://bbb.com"));
    Map<String, String> resolved = map("https://aaa.com", "https://ccc.com/");
    // bbb.com 探测失败，没在结果里
    assertTrue("只剩 1 条不能成组",
        RedirectCluster.cluster(sources, resolved).isEmpty());
  }

  /** PC 站跳手机站不算跨站重定向（归一化后同主机）。 */
  @Test public void doesNotClusterWwwToMobile() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A", "https://www.a.com"),
        src(1, "B", "https://a.com"));
    Map<String, String> resolved = map(
        "https://www.a.com", "https://m.a.com/",
        "https://a.com", "https://m.a.com/");
    assertTrue("同类前缀归一化后是同站，不该建议合并",
        RedirectCluster.cluster(sources, resolved).isEmpty());
  }

  @Test public void nullAndEmptySafe() {
    assertTrue(RedirectCluster.cluster(null, null).isEmpty());
    assertTrue(RedirectCluster.cluster(Collections.<SourceRecord>emptyList(),
        Collections.<String, String>emptyMap()).isEmpty());
    assertTrue(RedirectCluster.cluster(
        Collections.singletonList(src(0, "A", "https://a.com")), null).isEmpty());
  }

  // ---------- 说明文本 ----------

  @Test public void describeShowsMapping() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A", "https://aaa.com"),
        src(1, "B", "https://bbb.com"));
    Map<String, String> resolved = map(
        "https://aaa.com", "https://ccc.com/",
        "https://bbb.com", "https://ccc.com/");
    List<RedirectCluster.Group> groups = RedirectCluster.cluster(sources, resolved);
    String text = RedirectCluster.describe(groups.get(0));
    assertTrue("应说明最终站点", text.contains("ccc.com"));
    assertTrue("应列出来源主机", text.contains("aaa.com"));
    assertTrue(text.contains("bbb.com"));
  }

  // ---------- 探测辅助 ----------

  /** 去 #身份标签。 */
  @Test public void stripsIdentityForProbe() {
    assertEquals("https://a.com/x", RedirectProbe.stripIdentity("https://a.com/x#标签"));
    assertNull(RedirectProbe.stripIdentity(null));
    assertNull(RedirectProbe.stripIdentity("  "));
    assertEquals("https://a.com", RedirectProbe.stripIdentity("https://a.com"));
  }

  /** 探测结果按组大小降序排（界面先显示收益最大的）。 */
  @Test public void probeAndClusterSortsBySize() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A1", "https://a1.com"), src(1, "A2", "https://a2.com"),
        src(2, "A3", "https://a3.com"),
        src(3, "B1", "https://b1.com"), src(4, "B2", "https://b2.com"));
    Map<String, String> resolved = map(
        "https://a1.com", "https://big.com/",
        "https://a2.com", "https://big.com/",
        "https://a3.com", "https://big.com/",
        "https://b1.com", "https://small.com/",
        "https://b2.com", "https://small.com/");
    List<RedirectCluster.Group> groups = RedirectProbe.probeAndCluster(sources, resolved);
    assertEquals(2, groups.size());
    assertEquals("最大组应排前", 3, groups.get(0).size());
    assertEquals("big.com", groups.get(0).finalHost);
  }
  // ---------- 合并时挑保留项 ----------

  /** 规则更完整的应被保留。 */
  @Test public void pickBestPrefersCompleteRules() {
    Map<String, Object> full = new LinkedHashMap<>();
    full.put("bookSourceName", "完整");
    full.put("bookSourceUrl", "https://a.com");
    full.put("ruleToc", mapOf("chapterList", "x"));
    full.put("ruleContent", mapOf("content", "y"));
    SourceRecord complete = new SourceRecord(0, "完整", "https://a.com", full);

    Map<String, Object> bare = new LinkedHashMap<>();
    bare.put("bookSourceName", "裸");
    bare.put("bookSourceUrl", "https://a.com");
    SourceRecord empty = new SourceRecord(1, "裸", "https://a.com", bare);

    assertSame(complete, RedirectCluster.pickBestInGroup(Arrays.asList(empty, complete)));
  }

  /** 规则相同时，URL 更短的应优先（主域名优于轮换域名）。 */
  @Test public void pickBestPrefersShorterUrl() {
    SourceRecord shortUrl = src(0, "短", "https://a.com");
    SourceRecord longUrl = src(1, "长", "https://very-long-mirror-name-example.com");
    assertSame(shortUrl, RedirectCluster.pickBestInGroup(Arrays.asList(longUrl, shortUrl)));
  }

  @Test public void pickBestOnEmptyOrNull() {
    assertNull(RedirectCluster.pickBestInGroup(null));
    assertNull(RedirectCluster.pickBestInGroup(Collections.<SourceRecord>emptyList()));
  }

  private static Map<String, Object> mapOf(String k, String v) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(k, v);
    return m;
  }
}