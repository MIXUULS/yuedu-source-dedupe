package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.DuplicateGroup;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 剔除书源后三份数据（retained / duplicateGroups / checkResults）保持一致性的测试。
 *
 * 回归点：历史实现漏了刷新分组里的 removed 列表，删完书源后 getDuplicateCount() 仍按旧列表统计，
 * 界面上的"重复"数会虚高；另外提示用的是"勾选条数"，勾到已合并的重复源时会虚报。
 */
public class ResultPrunerTest {

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }

  /**
   * 构造一个「1 保留 + 2 重复」的结果。
   *
   * 用 AGGRESSIVE 模式：同主机不同路径会被合并成一组，且组内各源的 **URL 互不相同**。
   * 这正是真实场景（同一站点的多个入口），也只有这种数据才能区分"删保留源"和"删重复源"——
   * 标准模式下重复源与保留源 URL 完全相同，按 URL 根本分不开，那是另一套语义。
   */
  private static DedupeResult twoDuplicates() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "A", "https://a.com/1"),
        source(1, "B", "https://a.com/2"),
        source(2, "C", "https://a.com/3"),
        source(3, "D", "https://d.com/1"));
    return DedupeEngine.run(sources, DedupeMode.AGGRESSIVE, false);
  }

  @Test public void removesRetainedAndKeepsCountsConsistent() {
    DedupeResult before = twoDuplicates();
    assertEquals(2, before.getRetained().size());
    assertEquals(2, before.getDuplicateCount());

    ResultPruner.Pruned p = ResultPruner.prune(before, null,
        new HashSet<>(Collections.singletonList("https://d.com/1")));

    assertEquals(1, p.removedCount);
    assertEquals(1, p.result.getRetained().size());
    assertEquals("https://a.com/1", p.result.getRetained().get(0).getUrl());
  }

  /** 回归：删掉重复项后 getDuplicateCount() 必须跟着减，不能停留在旧值。 */
  @Test public void removingDuplicateUpdatesDuplicateCount() {
    DedupeResult before = twoDuplicates();
    assertEquals(2, before.getDuplicateCount());

    DuplicateGroup group = before.getDuplicateGroups().get(0);
    String dupUrl = group.getRemoved().get(0).getUrl();
    ResultPruner.Pruned p = ResultPruner.prune(before, null,
        new HashSet<>(Collections.singletonList(dupUrl)));

    assertEquals("只删了重复项，retained 不变", 2, p.result.getRetained().size());
    assertEquals("重复数必须减 1", before.getDuplicateCount() - 1, p.result.getDuplicateCount());
  }

  /** 删掉保留源时整组失效，被合并的重复项也不再计入重复组。 */
  @Test public void removingKeptDropsWholeGroup() {
    DedupeResult before = twoDuplicates();
    String keptUrl = before.getRetained().get(0).getUrl();

    ResultPruner.Pruned p = ResultPruner.prune(before, null,
        new HashSet<>(Collections.singletonList(keptUrl)));

    assertTrue("该组已不成立", p.result.getDuplicateGroups().isEmpty());
    assertEquals(0, p.result.getDuplicateCount());
    assertEquals(1, p.result.getRetained().size()); // 只剩 D
  }

  /** 勾选集合里含"本来就不在 retained"的重复源时，removedCount 只反映真实移除数。 */
  @Test public void removedCountOnlyCountsRetainedRemovals() {
    DedupeResult before = twoDuplicates();
    DuplicateGroup group = before.getDuplicateGroups().get(0);
    Set<String> urls = new HashSet<>();
    urls.add("https://d.com/1");                 // 真实在 retained 里
    urls.add(group.getRemoved().get(0).getUrl()); // 不在 retained 里

    ResultPruner.Pruned p = ResultPruner.prune(before, null, urls);
    assertEquals("只算真实移除的那一条", 1, p.removedCount);
  }

  /** 校验结果里对应被删书源的条目也要一起剔除。 */
  @Test public void checkResultsFollowRetained() {
    DedupeResult before = twoDuplicates();
    List<CheckSourceResult> checks = new ArrayList<>();
    for (SourceRecord s : before.getRetained()) {
      checks.add(new CheckSourceResult(s, CheckSourceResult.Status.SUCCESS,
          Collections.<String>emptyList(), "校验成功", 50, SourceKind.NOVEL));
    }
    assertEquals(2, checks.size());

    ResultPruner.Pruned p = ResultPruner.prune(before, checks,
        new HashSet<>(Collections.singletonList("https://d.com/1")));

    assertEquals(1, p.checkResults.size());
    assertEquals("https://a.com/1", p.checkResults.get(0).source.getUrl());
  }

  @Test public void nullArgsAreSafe() {
    ResultPruner.Pruned p = ResultPruner.prune(null, null, null);
    assertEquals(0, p.removedCount);
    assertNull(p.result);
    assertTrue(p.checkResults.isEmpty());

    DedupeResult before = twoDuplicates();
    ResultPruner.Pruned p2 = ResultPruner.prune(before, null, null);
    assertEquals("没传要删的集合就原样返回", 2, p2.result.getRetained().size());
    assertEquals(0, p2.removedCount);
  }

  /** 空缺 URL 的书源不应因集合里有 null 而被误删（直接构造结果，绕开引擎的 invalid 过滤）。 */
  @Test public void nullUrlSourcesArePreserved() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "无地址");
    SourceRecord noUrl = new SourceRecord(9, "无地址", null, raw);
    DedupeResult result = new DedupeResult(1,
        Collections.singletonList(noUrl), Collections.<DuplicateGroup>emptyList(),
        Collections.emptyList());

    Set<String> urls = new HashSet<>();
    urls.add(null);
    ResultPruner.Pruned p = ResultPruner.prune(result, null, urls);
    assertEquals("null URL 的书源应被保留", 1, p.result.getRetained().size());
    assertEquals(0, p.removedCount);
  }
}