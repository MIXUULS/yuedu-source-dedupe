package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 版本对比（两只书源文件）的差异计算单元测试。 */
public class SourceDiffTest {

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }

  @Test public void detectsAddedSourcesOnlyInSecond() {
    List<SourceRecord> a = Arrays.asList(source(0, "A", "https://aa.com"));
    List<SourceRecord> b = Arrays.asList(source(0, "A", "https://aa.com"), source(1, "B", "https://bb.com"));
    SourceDiff.Result r = SourceDiff.compute(a, b);
    assertEquals(1, r.added.size());
    assertEquals("https://bb.com", r.added.get(0));
    assertTrue(r.removed.isEmpty());
    assertTrue(r.modified.isEmpty());
    assertEquals(2, r.mapB.size());
  }

  @Test public void detectsRemovedSourcesOnlyInFirst() {
    List<SourceRecord> a = Arrays.asList(source(0, "A", "https://aa.com"), source(1, "B", "https://bb.com"));
    List<SourceRecord> b = Collections.singletonList(source(0, "A", "https://aa.com"));
    SourceDiff.Result r = SourceDiff.compute(a, b);
    assertEquals(1, r.removed.size());
    assertEquals("https://bb.com", r.removed.get(0));
    assertTrue(r.added.isEmpty());
    assertTrue(r.modified.isEmpty());
  }

  @Test public void detectsModifiedWhenRawContentDiffers() {
    Map<String, Object> rawA = new LinkedHashMap<>();
    rawA.put("bookSourceName", "A");
    rawA.put("bookSourceUrl", "https://aa.com");
    Map<String, Object> rawB = new LinkedHashMap<>();
    rawB.put("bookSourceName", "A");
    rawB.put("bookSourceUrl", "https://aa.com");
    rawB.put("bookSourceGroup", "改造"); // 仅第二份有附加字段 → 视为内容不同

    SourceRecord sa = new SourceRecord(0, "A", "https://aa.com", rawA);
    SourceRecord sb = new SourceRecord(0, "A", "https://aa.com", rawB);
    SourceDiff.Result r = SourceDiff.compute(Collections.singletonList(sa), Collections.singletonList(sb));
    assertEquals(1, r.modified.size());
    assertTrue(r.modified.get(0).contains("https://aa.com"));
    assertTrue(r.added.isEmpty());
    assertTrue(r.removed.isEmpty());
  }

  @Test public void identicalListsReportNoDiff() {
    List<SourceRecord> a = Arrays.asList(source(0, "A", "https://aa.com"), source(1, "B", "https://bb.com"));
    List<SourceRecord> b = Arrays.asList(source(0, "A", "https://aa.com"), source(1, "B", "https://bb.com"));
    SourceDiff.Result r = SourceDiff.compute(a, b);
    assertTrue(r.added.isEmpty());
    assertTrue(r.removed.isEmpty());
    assertTrue(r.modified.isEmpty());
    assertTrue(SourceDiff.isIdentical(r));
  }

  @Test public void nullUrlRecordsAreIgnoredFromDiff() {
    SourceRecord noUrl = new SourceRecord(0, "NoUrl", null, new LinkedHashMap<>());
    List<SourceRecord> a = new ArrayList<>();
    a.add(noUrl);
    SourceDiff.Result r = SourceDiff.compute(a, Collections.<SourceRecord>emptyList());
    assertTrue(r.added.isEmpty());
    assertTrue(r.removed.isEmpty());
    assertTrue(r.modified.isEmpty());
  }

  @Test public void handlesNullListsAsEmpty() {
    SourceDiff.Result r = SourceDiff.compute(null, Collections.singletonList(source(0, "A", "https://a.com")));
    assertEquals(1, r.added.size());
    assertTrue(SourceDiff.isIdentical(SourceDiff.compute(null, null)));
  }

  /**
   * 回归：曾用 String.valueOf(rawMap) 比较内容。AbstractMap.toString() 按迭代顺序输出，
   * 两份语义完全相同、只是键顺序不同的书源会被误判成"已修改"，导出顺序一变就满屏假差异。
   */
  @Test public void sameContentDifferentKeyOrderIsNotModified() {
    Map<String, Object> rawA = new LinkedHashMap<>();
    rawA.put("bookSourceName", "A");
    rawA.put("bookSourceUrl", "https://aa.com");
    rawA.put("bookSourceGroup", "小说");

    Map<String, Object> rawB = new LinkedHashMap<>();
    rawB.put("bookSourceGroup", "小说");   // 同样的键值，只是顺序不同
    rawB.put("bookSourceUrl", "https://aa.com");
    rawB.put("bookSourceName", "A");

    SourceRecord sa = new SourceRecord(0, "A", "https://aa.com", rawA);
    SourceRecord sb = new SourceRecord(0, "A", "https://aa.com", rawB);
    SourceDiff.Result r = SourceDiff.compute(Collections.singletonList(sa), Collections.singletonList(sb));
    assertTrue("键顺序不同不应算修改", r.modified.isEmpty());
    assertTrue(SourceDiff.isIdentical(r));
  }

  /** 嵌套对象（规则表）键顺序不同也不应算修改。 */
  @Test public void nestedRuleKeyOrderIsNotModified() {
    Map<String, Object> ruleA = new LinkedHashMap<>();
    ruleA.put("bookList", "#list");
    ruleA.put("name", "h1");
    Map<String, Object> ruleB = new LinkedHashMap<>();
    ruleB.put("name", "h1");
    ruleB.put("bookList", "#list");

    Map<String, Object> rawA = new LinkedHashMap<>();
    rawA.put("bookSourceName", "A");
    rawA.put("bookSourceUrl", "https://aa.com");
    rawA.put("ruleSearch", ruleA);
    Map<String, Object> rawB = new LinkedHashMap<>();
    rawB.put("bookSourceName", "A");
    rawB.put("bookSourceUrl", "https://aa.com");
    rawB.put("ruleSearch", ruleB);

    SourceDiff.Result r = SourceDiff.compute(
        Collections.singletonList(new SourceRecord(0, "A", "https://aa.com", rawA)),
        Collections.singletonList(new SourceRecord(0, "A", "https://aa.com", rawB)));
    assertTrue("嵌套键顺序不同不应算修改", r.modified.isEmpty());
  }

  /** 真实内容变化仍要能检出。 */
  @Test public void realContentChangeStillDetected() {
    Map<String, Object> rawA = new LinkedHashMap<>();
    rawA.put("bookSourceName", "A");
    rawA.put("bookSourceUrl", "https://aa.com");
    Map<String, Object> rawB = new LinkedHashMap<>();
    rawB.put("bookSourceName", "A");
    rawB.put("bookSourceUrl", "https://aa.com");
    rawB.put("bookSourceGroup", "漫画");

    SourceDiff.Result r = SourceDiff.compute(
        Collections.singletonList(new SourceRecord(0, "A", "https://aa.com", rawA)),
        Collections.singletonList(new SourceRecord(0, "A", "https://aa.com", rawB)));
    assertEquals(1, r.modified.size());
  }
}
