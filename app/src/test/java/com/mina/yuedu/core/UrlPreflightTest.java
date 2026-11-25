package com.mina.yuedu.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** 解析前 URL 预检（数量 / 重复 / 非 http 行）单元测试。 */
public class UrlPreflightTest {

  @Test public void countsUniqueAndDuplicates() {
    UrlPreflight.Report r = UrlPreflight.analyze(
        "https://a.com\nhttps://b.com\nhttps://a.com\nhttps://a.com");
    assertEquals("4 个非空行", 4, r.totalLines);
    assertEquals("去重后 2 个", 2, r.usableCount());
    assertEquals(Arrays.asList("https://a.com", "https://b.com"), r.unique);
    assertEquals("a 重复了 2 次", Integer.valueOf(2), r.duplicates.get("https://a.com"));
  }

  @Test public void detectsNonHttpLines() {
    UrlPreflight.Report r = UrlPreflight.analyze(
        "https://a.com\nftp://b.com\n随便一行文字\nwww.c.com");
    assertEquals(1, r.usableCount());
    assertEquals(3, r.nonHttp.size());
    assertTrue(r.hasIssues());
  }

  /** 没有问题时不产生摘要（调用方据此跳过提示，避免打扰）。 */
  @Test public void cleanInputHasNoSummary() {
    UrlPreflight.Report r = UrlPreflight.analyze("https://a.com\nhttps://b.com");
    assertEquals(2, r.usableCount());
    assertFalse(r.hasIssues());
    assertNull("无问题时不提示", UrlPreflight.summary(r));
  }

  /** 有重复时摘要要说明并强调已自动去重。 */
  @Test public void summaryMentionsDuplicates() {
    String s = UrlPreflight.summary(UrlPreflight.analyze(
        "https://a.com\nhttps://a.com\nhttps://b.com"));
    assertNotNull(s);
    assertTrue("应提到重复: " + s, s.contains("重复"));
    assertTrue("应说明已去重: " + s, s.contains("去重"));
  }

  /** 有非 http 行时摘要要说明会被忽略。 */
  @Test public void summaryMentionsNonHttp() {
    String s = UrlPreflight.summary(UrlPreflight.analyze(
        "https://a.com\nnot-a-url"));
    assertNotNull(s);
    assertTrue("应提到非 http 行: " + s, s.contains("不是 http"));
    assertTrue("应说明会忽略: " + s, s.contains("忽略"));
  }

  @Test public void emptyAndNullInput() {
    UrlPreflight.Report r = UrlPreflight.analyze("");
    assertEquals(0, r.totalLines);
    assertEquals(0, r.usableCount());
    assertFalse(r.hasIssues());
    assertNull(UrlPreflight.summary(r));

    UrlPreflight.Report r2 = UrlPreflight.analyze(null);
    assertEquals(0, r2.usableCount());
  }

  /** 空行不计入总行数（避免"检测到 10 行"里含空行的困惑）。 */
  @Test public void blankLinesNotCounted() {
    UrlPreflight.Report r = UrlPreflight.analyze("https://a.com\n\n\nhttps://b.com\n   \n");
    assertEquals(2, r.totalLines);
  }

  /** http 与 https 都算有效；大小写不敏感。 */
  @Test public void acceptsBothSchemesCaseInsensitive() {
    UrlPreflight.Report r = UrlPreflight.analyze(
        "http://a.com\nHTTPS://B.COM");
    assertEquals(2, r.usableCount());
    assertTrue(r.nonHttp.isEmpty());
  }

  /** 去重后的列表保持首次出现顺序。 */
  @Test public void uniqueKeepsFirstSeenOrder() {
    UrlPreflight.Report r = UrlPreflight.analyze(
        "https://z.com\nhttps://a.com\nhttps://z.com");
    assertEquals(Arrays.asList("https://z.com", "https://a.com"), r.unique);
  }

  /** 重复计数只算多余的次数，不含首次。 */
  @Test public void duplicateCountExcludesFirstOccurrence() {
    UrlPreflight.Report r = UrlPreflight.analyze(
        "https://a.com\nhttps://a.com\nhttps://a.com");
    assertEquals("首次不算重复", Integer.valueOf(2), r.duplicates.get("https://a.com"));
  }
}