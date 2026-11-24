package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 校验历史（汇总 / 截断 / JSON 往返）单元测试。 */
public class CheckHistoryStoreTest {

  private static CheckSourceResult row(String url, CheckSourceResult.Status st) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "n");
    raw.put("bookSourceUrl", url);
    return new CheckSourceResult(new SourceRecord(0, "n", url, raw), st,
        Collections.<String>emptyList(), st.name(), 10, SourceKind.NOVEL);
  }

  /** 统计出三类计数，失败与超时的 URL 进 badUrls。 */
  @Test public void summarizeCountsAndCollectsBadUrls() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(row("https://ok.com", CheckSourceResult.Status.SUCCESS));
    list.add(row("https://bad.com", CheckSourceResult.Status.FAILED));
    list.add(row("https://slow.com", CheckSourceResult.Status.TIMEOUT));

    CheckHistoryStore.Entry e = CheckHistoryStore.summarize(list, 1000L);
    assertNotNull(e);
    assertEquals(3, e.total);
    assertEquals(1, e.ok);
    assertEquals(1, e.fail);
    assertEquals(1, e.timeout);
    assertEquals("失败与超时都要进 badUrls", 2, e.badUrls.size());
    assertTrue(e.badUrls.contains("https://bad.com"));
    assertTrue(e.badUrls.contains("https://slow.com"));
  }

  /** 成功源不应进 badUrls。 */
  @Test public void successUrlsNotInBadList() {
    CheckHistoryStore.Entry e = CheckHistoryStore.summarize(
        Collections.singletonList(row("https://ok.com", CheckSourceResult.Status.SUCCESS)), 1L);
    assertTrue(e.badUrls.isEmpty());
  }

  /** 空批次不产生历史条目。 */
  @Test public void emptyBatchProducesNoEntry() {
    assertNull(CheckHistoryStore.summarize(null, 1L));
    assertNull(CheckHistoryStore.summarize(Collections.<CheckSourceResult>emptyList(), 1L));
  }

  /** 新条目插到最前。 */
  @Test public void prependPutsNewestFirst() {
    CheckHistoryStore.Entry older = CheckHistoryStore.summarize(
        Collections.singletonList(row("https://a.com", CheckSourceResult.Status.SUCCESS)), 100L);
    CheckHistoryStore.Entry newer = CheckHistoryStore.summarize(
        Collections.singletonList(row("https://b.com", CheckSourceResult.Status.FAILED)), 200L);

    List<CheckHistoryStore.Entry> out = CheckHistoryStore.prepend(
        new ArrayList<>(Collections.singletonList(older)), newer);
    assertEquals(newer, out.get(0));
    assertEquals(older, out.get(1));
  }

  /** 超过上限要截断，挤掉最旧的。 */
  @Test public void capsAtMaxEntries() {
    List<CheckHistoryStore.Entry> list = new ArrayList<>();
    for (int i = 0; i < CheckHistoryStore.MAX + 5; i++) {
      list = CheckHistoryStore.prepend(list, CheckHistoryStore.summarize(
          Collections.singletonList(row("https://s" + i + ".com", CheckSourceResult.Status.SUCCESS)),
          i * 1000L));
    }
    assertEquals(CheckHistoryStore.MAX, list.size());
    assertEquals("最新的在最前", CheckHistoryStore.MAX * 1000L + 4000L, list.get(0).ts);
  }

  /** JSON 往返：计数与 badUrls 都要保留。 */
  @Test public void jsonRoundTripPreservesData() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(row("https://ok.com", CheckSourceResult.Status.SUCCESS));
    list.add(row("https://bad.com", CheckSourceResult.Status.FAILED));
    CheckHistoryStore.Entry e = CheckHistoryStore.summarize(list, 123456789L);

    String json = CheckHistoryStore.toJson(Collections.singletonList(e));
    List<CheckHistoryStore.Entry> back = CheckHistoryStore.fromJson(json);

    assertEquals(1, back.size());
    assertEquals(e.ts, back.get(0).ts);
    assertEquals(e.total, back.get(0).total);
    assertEquals(e.ok, back.get(0).ok);
    assertEquals(e.fail, back.get(0).fail);
    assertEquals(e.badUrls, back.get(0).badUrls);
  }

  /** timeText 由 ts 推导，往返后仍然一致。 */
  @Test public void timeTextIsDerivedFromTs() {
    CheckHistoryStore.Entry e = CheckHistoryStore.summarize(
        Collections.singletonList(row("https://a.com", CheckSourceResult.Status.SUCCESS)), 1700000000000L);
    assertEquals(e.timeText, CheckHistoryStore.fromJson(
        CheckHistoryStore.toJson(Collections.singletonList(e))).get(0).timeText);
  }

  /** 损坏的 JSON 不抛异常，返回空或已解析部分。 */
  @Test public void corruptJsonIsSafe() {
    assertTrue(CheckHistoryStore.fromJson(null).isEmpty());
    assertTrue(CheckHistoryStore.fromJson("").isEmpty());
    assertTrue(CheckHistoryStore.fromJson("not json").isEmpty());
    assertTrue(CheckHistoryStore.fromJson("{\"a\":1}").isEmpty());
  }

  /** 载入时同样按上限截断（防止旧数据超量）。 */
  @Test public void fromJsonAlsoCapsAtMax() {
    List<CheckHistoryStore.Entry> many = new ArrayList<>();
    for (int i = 0; i < CheckHistoryStore.MAX + 3; i++) {
      many.add(new CheckHistoryStore.Entry(i * 1000L, 1, 1, 0, 0, null));
    }
    assertEquals(CheckHistoryStore.MAX,
        CheckHistoryStore.fromJson(CheckHistoryStore.toJson(many)).size());
  }

  /** 缺少字段的旧格式条目也能读出来，不崩。 */
  @Test public void toleratesPartialFields() {
    List<CheckHistoryStore.Entry> out = CheckHistoryStore.fromJson("[{\"ts\":1000,\"total\":2}]");
    assertEquals(1, out.size());
    assertEquals(0, out.get(0).ok);
    assertTrue(out.get(0).badUrls.isEmpty());
  }
}