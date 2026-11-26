package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 「可用 / 站点在线 / 站点已死」三档分类测试。
 *
 * 用户需求：去掉**无论如何都不能用**的网站。
 * 关键是不能把 403 反爬、404 路径变化、超时当成死站——
 * 实测 1443 个真实域名里这类有 173 个，比真正的死站（110 个）还多。
 */
public class UsabilityBucketsTest {

  private static CheckSourceResult result(String name, CheckSourceResult.Status st,
                                          List<String> groups, String msg) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", "https://" + name + ".com");
    SourceRecord s = new SourceRecord(0, name, "https://" + name + ".com", raw);
    return new CheckSourceResult(s, st, groups, msg, 10, SourceKind.NOVEL);
  }

  private static CheckSourceResult failed(String name, String... reasons) {
    return result(name, CheckSourceResult.Status.FAILED, Arrays.asList(reasons),
        String.join(",", reasons));
  }

  // ---------- 分档 ----------

  @Test public void successIsUsable() {
    CheckSourceResult r = result("ok", CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "校验成功");
    assertEquals(UsabilityBuckets.Level.USABLE, UsabilityBuckets.classify(r));
  }

  /** 最关键的判定：403 反爬的站还活着，绝不能算死站。 */
  @Test public void forbiddenIsSiteAlive() {
    CheckSourceResult r = failed("a", "站点拒绝访问（403，可能反爬）");
    assertEquals("403 不能算死站", UsabilityBuckets.Level.SITE_ALIVE,
        UsabilityBuckets.classify(r));
  }

  @Test public void notFoundIsSiteAlive() {
    assertEquals(UsabilityBuckets.Level.SITE_ALIVE,
        UsabilityBuckets.classify(failed("b", "路径不存在（404，站点在线）")));
  }

  @Test public void timeoutIsUnknown() {
    CheckSourceResult r = result("c", CheckSourceResult.Status.TIMEOUT,
        Collections.singletonList("校验超时"), "timeout");
    assertEquals("超时不判死", UsabilityBuckets.Level.UNKNOWN,
        UsabilityBuckets.classify(r));
  }

  @Test public void ruleOutdatedIsSiteAlive() {
    assertEquals(UsabilityBuckets.Level.SITE_ALIVE,
        UsabilityBuckets.classify(failed("d", "搜索规则不完整")));
    assertEquals(UsabilityBuckets.Level.SITE_ALIVE,
        UsabilityBuckets.classify(failed("e", "正文失效")));
  }

  /** 「网站失效」标记只在真正确认死站时写入。 */
  @Test public void siteDeadMarkerIsDead() {
    assertEquals(UsabilityBuckets.Level.SITE_DEAD,
        UsabilityBuckets.classify(failed("f", "网站失效")));
  }

  @Test public void dnsFailureTextIsDead() {
    assertEquals(UsabilityBuckets.Level.SITE_DEAD,
        UsabilityBuckets.classify(failed("g", "Unable to resolve host \"x.com\"")));
  }

  @Test public void refusedTextIsDead() {
    assertEquals(UsabilityBuckets.Level.SITE_DEAD,
        UsabilityBuckets.classify(failed("h", "Connection refused")));
  }

  @Test public void skippedIsUnknown() {
    CheckSourceResult r = result("i", CheckSourceResult.Status.SKIPPED,
        Collections.singletonList("跳过"), "skip");
    assertEquals(UsabilityBuckets.Level.UNKNOWN, UsabilityBuckets.classify(r));
  }

  // ---------- 信号识别 ----------

  @Test public void deadSignalRecognizesOnlyRealDeadCases() {
    assertTrue(UsabilityBuckets.isDeadSignal("网站失效"));
    assertTrue(UsabilityBuckets.isDeadSignal("getaddrinfo failed"));
    assertTrue(UsabilityBuckets.isDeadSignal("econnrefused"));

    assertFalse("403 不是死站信号", UsabilityBuckets.isDeadSignal("站点拒绝访问（403，可能反爬）"));
    assertFalse("404 不是死站信号", UsabilityBuckets.isDeadSignal("路径不存在（404，站点在线）"));
    assertFalse("超时不是死站信号", UsabilityBuckets.isDeadSignal("校验超时"));
    assertFalse(UsabilityBuckets.isDeadSignal(null));
    assertFalse(UsabilityBuckets.isDeadSignal(""));
  }

  // ---------- 批量分档 ----------

  @Test public void splitPopulatesAllBuckets() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(result("u1", CheckSourceResult.Status.SUCCESS, Collections.<String>emptyList(), "ok"));
    list.add(failed("a1", "站点拒绝访问（403，可能反爬）"));
    list.add(failed("d1", "网站失效"));
    list.add(result("t1", CheckSourceResult.Status.TIMEOUT, Collections.singletonList("超时"), "timeout"));

    UsabilityBuckets b = UsabilityBuckets.split(list);
    assertEquals(1, b.usable.size());
    assertEquals(1, b.siteAlive.size());
    assertEquals(1, b.siteDead.size());
    assertEquals(1, b.unknown.size());
    assertEquals(4, b.size());
  }

  @Test public void emptyAndNullSafe() {
    assertEquals(0, UsabilityBuckets.split(null).size());
    assertEquals(0, UsabilityBuckets.split(Collections.<CheckSourceResult>emptyList()).size());
  }

  // ---------- 死站 URL 集合 ----------

  /** 232 里只有死站的 URL 被收集，活站的不能被收集（否则会误删）。 */
  @Test public void deadUrlsContainsOnlyDead() {
    List<CheckSourceResult> list = Arrays.asList(
        result("alive1", CheckSourceResult.Status.SUCCESS, Collections.<String>emptyList(), "ok"),
        failed("forbidden1", "站点拒绝访问（403，可能反爬）"),
        failed("dead1", "网站失效"),
        failed("dead2", "Connection refused"));

    Set<String> dead = UsabilityBuckets.split(list).deadUrls();
    assertEquals("只应收集死站", 2, dead.size());
    assertTrue(dead.contains("https://dead1.com"));
    assertTrue(dead.contains("https://dead2.com"));
    assertFalse("活站不能进集合", dead.contains("https://forbidden1.com"));
    assertFalse(dead.contains("https://alive1.com"));
  }

  // ---------- 原因聚合 ----------

  @Test public void deadReasonCountsAggregate() {
    List<CheckSourceResult> list = Arrays.asList(
        failed("d1", "网站失效"),
        failed("d2", "网站失效"),
        failed("d3", "Connection refused"));
    Map<String, Integer> counts = UsabilityBuckets.split(list).deadReasonCounts();
    assertEquals(Integer.valueOf(2), counts.get("网站失效"));
    assertEquals(Integer.valueOf(1), counts.get("Connection refused"));
  }
}