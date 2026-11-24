package com.mina.yuedu.export;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** 导出范围选择（仅可用过滤 / 类型过滤 / 组合顺序）单元测试。 */
public class ExportRecordSelectorTest {

  private static final Set<SourceKind> ALL = EnumSet.allOf(SourceKind.class);

  private static SourceRecord novel(String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(0, name, url, raw);
  }

  private static SourceRecord comic(String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("bookSourceType", 2);
    return new SourceRecord(0, name, url, raw);
  }

  private static CheckSourceResult check(SourceRecord s, CheckSourceResult.Status st) {
    return new CheckSourceResult(s, st, Collections.<String>emptyList(), st.name(), 10, SourceKind.NOVEL);
  }

  @Test public void emptyInputsYieldEmpty() {
    assertTrue(ExportRecordSelector.select(null, null, false, ALL).isEmpty());
    assertTrue(ExportRecordSelector.select(Collections.<SourceRecord>emptyList(), null, false, ALL).isEmpty());
  }

  /** 没有任何类型被勾选时，一条都不导出。 */
  @Test public void emptyKindSelectionExportsNothing() {
    List<SourceRecord> retained = Collections.singletonList(novel("A", "https://a.com"));
    assertTrue(ExportRecordSelector.select(retained, null, false,
        Collections.<SourceKind>emptySet()).isEmpty());
    assertTrue(ExportRecordSelector.select(retained, null, false, null).isEmpty());
  }

  /** 不启用"仅可用"时，全部保留源都导出。 */
  @Test public void withoutOnlyUsableExportsAll() {
    List<SourceRecord> retained = Arrays.asList(
        novel("A", "https://a.com"), novel("B", "https://b.com"));
    List<SourceRecord> out = ExportRecordSelector.select(retained, null, false, ALL);
    assertEquals(2, out.size());
  }

  /** 启用"仅可用"时只导出校验通过的。 */
  @Test public void onlyUsableFiltersByCheckResult() {
    SourceRecord a = novel("A", "https://a.com");
    SourceRecord b = novel("B", "https://b.com");
    List<SourceRecord> retained = Arrays.asList(a, b);
    List<CheckSourceResult> checks = Arrays.asList(
        check(a, CheckSourceResult.Status.SUCCESS),
        check(b, CheckSourceResult.Status.FAILED));

    List<SourceRecord> out = ExportRecordSelector.select(retained, checks, true, ALL);
    assertEquals(1, out.size());
    assertEquals("A", out.get(0).getName());
  }

  /** 启用"仅可用"但没有校验数据时，退化为全量（否则用户会看到 0 条而困惑）。 */
  @Test public void onlyUsableWithoutChecksFallsBackToAll() {
    List<SourceRecord> retained = Arrays.asList(
        novel("A", "https://a.com"), novel("B", "https://b.com"));
    assertEquals(2, ExportRecordSelector.select(retained, null, true, ALL).size());
    assertEquals(2, ExportRecordSelector.select(retained,
        Collections.<CheckSourceResult>emptyList(), true, ALL).size());
  }

  /** 只勾选漫画时，小说不导出。 */
  @Test public void kindFilterExcludesOtherKinds() {
    List<SourceRecord> retained = Arrays.asList(
        novel("N", "https://n.com"), comic("C", "https://c.com"));
    List<SourceRecord> out = ExportRecordSelector.select(retained, null, false,
        EnumSet.of(SourceKind.COMIC));
    assertEquals(1, out.size());
    assertEquals("C", out.get(0).getName());
  }

  /** 两个过滤条件叠加：先按可用，再按类型。 */
  @Test public void combinesOnlyUsableWithKindFilter() {
    SourceRecord n = novel("N", "https://n.com");
    SourceRecord c = comic("C", "https://c.com");
    List<SourceRecord> retained = Arrays.asList(n, c);
    List<CheckSourceResult> checks = Arrays.asList(
        check(n, CheckSourceResult.Status.SUCCESS),
        new CheckSourceResult(c, CheckSourceResult.Status.SUCCESS,
            Collections.<String>emptyList(), "ok", 10, SourceKind.COMIC));

    // 只导出可用的漫画
    List<SourceRecord> out = ExportRecordSelector.select(retained, checks, true,
        EnumSet.of(SourceKind.COMIC));
    assertEquals(1, out.size());
    assertEquals("C", out.get(0).getName());
  }

  /** 超时不算可用。 */
  @Test public void timeoutIsNotUsable() {
    SourceRecord a = novel("A", "https://a.com");
    List<SourceRecord> out = ExportRecordSelector.select(
        Collections.singletonList(a),
        Collections.singletonList(check(a, CheckSourceResult.Status.TIMEOUT)),
        true, ALL);
    assertTrue("超时的源不应被当作可用导出", out.isEmpty());
  }

  /** usableUrls 只收可用且 URL 非空的。 */
  @Test public void usableUrlsCollectsOnlyUsable() {
    SourceRecord a = novel("A", "https://a.com");
    SourceRecord b = novel("B", "https://b.com");
    Set<String> urls = ExportRecordSelector.usableUrls(Arrays.asList(
        check(a, CheckSourceResult.Status.SUCCESS),
        check(b, CheckSourceResult.Status.FAILED)));
    assertEquals(1, urls.size());
    assertTrue(urls.contains("https://a.com"));
  }

  @Test public void usableUrlsHandlesNull() {
    assertTrue(ExportRecordSelector.usableUrls(null).isEmpty());
    assertTrue(ExportRecordSelector.usableUrls(
        Collections.<CheckSourceResult>emptyList()).isEmpty());
  }

  /** 返回的列表不应是内部共享结构，改动它不能影响源数据。 */
  @Test public void returnedListIsIndependent() {
    List<SourceRecord> retained = new ArrayList<>(Arrays.asList(
        novel("A", "https://a.com"), novel("B", "https://b.com")));
    List<SourceRecord> out = ExportRecordSelector.select(retained, null, false, ALL);
    out.clear();
    assertEquals("清空导出结果不应影响原保留列表", 2, retained.size());
  }

  /** countByKind 统计各类型数量。 */
  @Test public void countByKindWorks() {
    List<SourceRecord> retained = Arrays.asList(
        novel("N1", "https://n1.com"), novel("N2", "https://n2.com"), comic("C", "https://c.com"));
    assertEquals(2, ExportRecordSelector.countByKind(retained, SourceKind.NOVEL));
    assertEquals(1, ExportRecordSelector.countByKind(retained, SourceKind.COMIC));
    assertEquals(0, ExportRecordSelector.countByKind(retained, SourceKind.VIDEO));
    assertEquals(0, ExportRecordSelector.countByKind(null, SourceKind.NOVEL));
  }

  /** 多类型勾选时都导出。 */
  @Test public void multipleKindsSelected() {
    List<SourceRecord> retained = Arrays.asList(
        novel("N", "https://n.com"), comic("C", "https://c.com"));
    List<SourceRecord> out = ExportRecordSelector.select(retained, null, false,
        new HashSet<>(Arrays.asList(SourceKind.NOVEL, SourceKind.COMIC)));
    assertEquals(2, out.size());
  }
}