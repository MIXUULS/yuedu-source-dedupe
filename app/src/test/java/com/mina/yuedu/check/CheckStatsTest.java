package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 校验结果统计测试。
 *
 * 重点在失败原因归一化：失败信息常写成「搜索失效，HTTP 404」这种
 * 「主因 + 细节」形式，不归一化的话同一个原因会因为附带细节不同
 * 被统计成多条，用户看到的分布是碎的。
 */
public class CheckStatsTest {

  private static CheckSourceResult row(CheckSourceResult.Status st, String msg) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "n");
    raw.put("bookSourceUrl", "https://a.com");
    return new CheckSourceResult(new SourceRecord(0, "n", "https://a.com", raw),
        st, Collections.<String>emptyList(), msg, 10, SourceKind.NOVEL);
  }

  // ---------- 计数 ----------

  @Test public void countsEachStatus() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(row(CheckSourceResult.Status.SUCCESS, "ok"));
    list.add(row(CheckSourceResult.Status.SUCCESS, "ok"));
    list.add(row(CheckSourceResult.Status.FAILED, "搜索失效"));
    list.add(row(CheckSourceResult.Status.TIMEOUT, "超时"));
    list.add(row(CheckSourceResult.Status.SKIPPED, "跳过"));

    CheckStats s = CheckStats.summarize(list);
    assertEquals(2, s.ok);
    assertEquals(1, s.fail);
    assertEquals(1, s.timeout);
    assertEquals(1, s.skipped);
  }

  /** SKIPPED 不计入可统计总数。 */
  @Test public void skippedNotCountedInTotal() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(row(CheckSourceResult.Status.SUCCESS, "ok"));
    list.add(row(CheckSourceResult.Status.SKIPPED, "跳过"));
    assertEquals(1, CheckStats.summarize(list).counted());
  }

  @Test public void passRateComputed() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(row(CheckSourceResult.Status.SUCCESS, "ok"));
    list.add(row(CheckSourceResult.Status.SUCCESS, "ok"));
    list.add(row(CheckSourceResult.Status.FAILED, "fail"));
    list.add(row(CheckSourceResult.Status.FAILED, "fail"));
    assertEquals(0.5, CheckStats.summarize(list).passRate(), 0.001);
  }

  @Test public void emptyListIsSafe() {
    CheckStats s = CheckStats.summarize(null);
    assertEquals(0, s.counted());
    assertEquals(0.0, s.passRate(), 0.001);
    assertTrue(s.reasonCount.isEmpty());

    CheckStats s2 = CheckStats.summarize(Collections.<CheckSourceResult>emptyList());
    assertEquals(0, s2.counted());
  }

  // ---------- 失败原因归一化 ----------

  /** 逗号后的细节要截掉，只保留主因。 */
  @Test public void trimsAfterChineseComma() {
    assertEquals("搜索失效", CheckStats.normalizeReason("搜索失效，HTTP 404"));
  }

  @Test public void trimsAfterEnglishComma() {
    assertEquals("搜索失效", CheckStats.normalizeReason("搜索失效,HTTP 404"));
  }

  /** 同一主因 + 不同细节应归并成一条统计。 */
  @Test public void mergesSameReasonWithDifferentDetails() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(row(CheckSourceResult.Status.FAILED, "搜索失效，HTTP 404"));
    list.add(row(CheckSourceResult.Status.FAILED, "搜索失效，连接超时"));
    list.add(row(CheckSourceResult.Status.FAILED, "搜索失效，DNS 失败"));

    CheckStats s = CheckStats.summarize(list);
    assertEquals("三条应归并成一个原因", 1, s.reasonCount.size());
    assertEquals(Integer.valueOf(3), s.reasonCount.get("搜索失效"));
  }

  /** 空消息兜底为「未知原因」。 */
  @Test public void emptyMessageBecomesUnknown() {
    assertEquals("未知原因", CheckStats.normalizeReason(null));
    assertEquals("未知原因", CheckStats.normalizeReason(""));
    assertEquals("未知原因", CheckStats.normalizeReason("   "));
  }

  /** 超长原因截断并加省略号。 */
  @Test public void longReasonTruncated() {
    String longMsg = "这是一个非常非常非常长的失败原因描述文字";
    String got = CheckStats.normalizeReason(longMsg);
    assertTrue("应截断", got.length() <= 15);
    assertTrue("应有省略号", got.endsWith("…"));
  }

  /** 短原因不截断。 */
  @Test public void shortReasonUnchanged() {
    assertEquals("正文失效", CheckStats.normalizeReason("正文失效"));
  }

  /** 只有逗号没有主因时不产生空字符串。 */
  @Test public void commaOnlyFallsBackToUnknown() {
    assertEquals("未知原因", CheckStats.normalizeReason("，细节"));
  }

  // ---------- 排序 ----------

  /** 原因计数可被调用方按次数排序（验证数据结构正确）。 */
  @Test public void reasonCountsAccurate() {
    List<CheckSourceResult> list = new ArrayList<>();
    for (int i = 0; i < 5; i++) list.add(row(CheckSourceResult.Status.FAILED, "搜索失效"));
    for (int i = 0; i < 2; i++) list.add(row(CheckSourceResult.Status.FAILED, "正文失效"));

    CheckStats s = CheckStats.summarize(list);
    assertEquals(Integer.valueOf(5), s.reasonCount.get("搜索失效"));
    assertEquals(Integer.valueOf(2), s.reasonCount.get("正文失效"));
  }

  /** 没有失败时原因表为空。 */
  @Test public void noFailuresMeansEmptyReasons() {
    CheckStats s = CheckStats.summarize(
        Collections.singletonList(row(CheckSourceResult.Status.SUCCESS, "ok")));
    assertTrue(s.reasonCount.isEmpty());
    assertEquals(0, s.fail);
  }

  /** 超时不计入失败原因统计。 */
  @Test public void timeoutNotInReasonTable() {
    CheckStats s = CheckStats.summarize(
        Collections.singletonList(row(CheckSourceResult.Status.TIMEOUT, "超时")));
    assertEquals(1, s.timeout);
    assertEquals(0, s.fail);
    assertTrue(s.reasonCount.isEmpty());
  }
}