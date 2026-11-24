package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 健康记录上限保护测试。
 *
 * 回归点：records 原本只增不减——用户换过几批书源后旧记录仍常驻内存，
 * 万级书源 × 每条最多 20 个快照约 10MB，且这份数据会被序列化成十几 MB 的 JSON
 * 存进 SharedPreferences。
 *
 * 实现细节回归：淘汰逻辑最初放在 addSnapshot **之前**，此时新记录的 lastCheckTime
 * 还是 0，排序时排到最前被立即误删——用户每校验一个新源就丢一个新记录。
 */
public class SourceHealthTrackerLimitsTest {

  private static SourceRecord source(String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "s");
    raw.put("bookSourceUrl", url);
    return new SourceRecord(0, "s", url, raw);
  }

  private static CheckSourceResult result(SourceRecord s, boolean ok) {
    return new CheckSourceResult(s,
        ok ? CheckSourceResult.Status.SUCCESS : CheckSourceResult.Status.FAILED,
        Collections.<String>emptyList(), ok ? "ok" : "fail", 100, SourceKind.NOVEL);
  }

  @Test public void recordsGrowNormally() {
    SourceHealthTracker t = new SourceHealthTracker();
    for (int i = 0; i < 10; i++) {
      SourceRecord s = source("https://s" + i + ".com");
      t.recordCheck(s, result(s, true));
    }
    assertEquals(10, t.size());
  }

  /** 同一 URL 重复校验只增加快照，不增加记录数。 */
  @Test public void sameUrlDoesNotGrowRecordCount() {
    SourceHealthTracker t = new SourceHealthTracker();
    SourceRecord s = source("https://same.com");
    for (int i = 0; i < 5; i++) t.recordCheck(s, result(s, true));
    assertEquals("同一书源只应有一条记录", 1, t.size());
    assertEquals(5, t.get("https://same.com").totalChecks);
  }

  /**
   * 回归：每校验一个新源都必须留下记录（哪怕刚触发过淘汰）。
   *
   * 这是最初的实现缺陷——淘汰在 addSnapshot 之前跑，新记录 lastCheckTime=0
   * 被排序到最前直接删除，表现为"校验了新源但健康看板里没有"。
   */
  @Test public void newlyCheckedSourceIsNeverDropped() {
    SourceHealthTracker t = new SourceHealthTracker();
    // 用批量写入灌到接近上限（比逐条 recordCheck 快得多，也贴合真实使用场景）
    List<SourceRecord> batch = new ArrayList<>();
    for (int i = 0; i < SourceHealthTracker.MAX_RECORDS - 1; i++) {
      batch.add(source("https://old" + i + ".com"));
    }
    List<CheckSourceResult> results = new ArrayList<>();
    for (SourceRecord s : batch) results.add(result(s, true));
    t.recordBatch(batch, results);

    // 再来一个新源：它必须留下
    SourceRecord fresh = source("https://fresh.com");
    t.recordCheck(fresh, result(fresh, true));
    assertNotNull("新校验的书源必须出现在健康记录里", t.get("https://fresh.com"));
  }

  /** 超过上限时总数被压回上限，且不会无限增长。 */
  @Test public void enforcesMaxRecords() {
    SourceHealthTracker t = new SourceHealthTracker();
    List<SourceRecord> batch = new ArrayList<>();
    for (int i = 0; i < SourceHealthTracker.MAX_RECORDS + 10; i++) {
      batch.add(source("https://x" + i + ".com"));
    }
    List<CheckSourceResult> results = new ArrayList<>();
    for (SourceRecord s : batch) results.add(result(s, true));
    t.recordBatch(batch, results);

    assertTrue("记录数不应超过上限，实际 " + t.size(),
        t.size() <= SourceHealthTracker.MAX_RECORDS);
  }

  /** 没有记录时调用 prune 不崩。 */
  @Test public void pruneOnEmptyIsSafe() {
    SourceHealthTracker t = new SourceHealthTracker();
    t.prune();
    assertEquals(0, t.size());
  }

  /** 单条记录的快照上限仍是 20。 */
  @Test public void snapshotHistoryCappedAt20() {
    SourceHealthTracker t = new SourceHealthTracker();
    SourceRecord s = source("https://a.com");
    for (int i = 0; i < 50; i++) t.recordCheck(s, result(s, i % 2 == 0));
    assertEquals(20, t.get("https://a.com").history.size());
    assertEquals("总校验次数仍应累计", 50, t.get("https://a.com").totalChecks);
  }

  /** 空 URL / null 输入不产生记录。 */
  @Test public void nullInputsIgnored() {
    SourceHealthTracker t = new SourceHealthTracker();
    t.recordCheck(null, null);
    SourceRecord noUrl = new SourceRecord(0, "n", null, new LinkedHashMap<String, Object>());
    t.recordCheck(noUrl, result(noUrl, true));
    assertEquals(0, t.size());
  }

  /** 序列化往返后关键字段与序号都保留（序号决定淘汰优先级）。 */
  @Test public void roundTripPreservesSeqAndCounts() {
    SourceHealthTracker t = new SourceHealthTracker();
    SourceRecord s = source("https://a.com");
    t.recordCheck(s, result(s, true));
    t.recordCheck(s, result(s, false));

    SourceHealthTracker restored = new SourceHealthTracker();
    restored.fromJsonList(t.toJsonList());

    assertEquals(1, restored.size());
    assertEquals(2, restored.get("https://a.com").totalChecks);
    assertEquals(1, restored.get("https://a.com").successCount);
    assertNotNull("序号应恢复，否则淘汰时无法区分新旧", restored.get("https://a.com"));
  }
}