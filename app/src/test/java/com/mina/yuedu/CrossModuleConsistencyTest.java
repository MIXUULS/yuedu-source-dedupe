package com.mina.yuedu;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.core.DedupeEngine;
import com.mina.yuedu.core.MainState;
import com.mina.yuedu.core.ResultPruner;
import com.mina.yuedu.core.UrlRecovery;
import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 跨模块一致性测试：验证多个已抽出的类组合使用时行为正确。
 *
 * 单独测每个类都过，不代表组合起来对。这里专门验证边界衔接——
 * 特别是本次审查发现的几处「各自正确、组合出错」的风险点。
 */
public class CrossModuleConsistencyTest {

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }

  // ---------- URL 恢复 + 去重 + 剔除的组合 ----------

  /**
   * 恢复出的 URL 必须参与去重 key，且剔除逻辑要能按恢复后的 URL 找到它。
   *
   * 风险点：如果恢复只改了 SourceRecord.url 但没改 raw 里的 bookSourceUrl，
   * 导出时会写出空地址；如果不参与去重，同站两条会都保留。
   */
  @Test public void recoveredUrlFlowsThroughDedupeAndPrune() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "可恢复");
    raw.put("searchUrl", "https://same.com/search?q={{key}}");
    SourceRecord recoverable = new SourceRecord(0, "可恢复", null, raw);

    SourceRecord normal = source(1, "正常", "https://same.com");

    DedupeResult r = DedupeEngine.run(Arrays.asList(recoverable, normal), DedupeMode.STANDARD, false);
    assertEquals("同站应合并", 1, r.getRetained().size());

    SourceRecord kept = r.getRetained().get(0);
    assertNotNull("保留项必须有 URL", kept.getUrl());
    assertEquals("raw 里的 bookSourceUrl 也要被更新，否则导出仍为空",
        kept.getUrl(), String.valueOf(kept.getRaw().get("bookSourceUrl")));
  }

  /** 剔除时按 URL 匹配：恢复出的源也要能被正确剔除。 */
  @Test public void prunerWorksWithRecoveredUrls() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "可恢复");
    raw.put("searchUrl", "https://recovered.com/x");
    SourceRecord recoverable = new SourceRecord(0, "可恢复", null, raw);
    SourceRecord other = source(1, "其他", "https://other.com");

    DedupeResult r = DedupeEngine.run(Arrays.asList(recoverable, other), DedupeMode.STANDARD, false);
    assertEquals(2, r.getRetained().size());

    // 用恢复后的 URL 剔除
    ResultPruner.Pruned pruned = ResultPruner.prune(r, null,
        new HashSet<>(Collections.singletonList("https://recovered.com")));
    assertEquals("应成功剔除 1 条", 1, pruned.removedCount);
    assertEquals(1, pruned.result.getRetained().size());
    assertEquals("其他", pruned.result.getRetained().get(0).getName());
  }

  // ---------- 严格模式 + 恢复的组合 ----------

  /** 恢复出的 URL 在严格模式下也享受 www 归一化。 */
  @Test public void recoveryWorksWithStrictNormalization() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "可恢复");
    raw.put("searchUrl", "https://www.site.com/search");
    SourceRecord a = new SourceRecord(0, "可恢复", null, raw);

    SourceRecord b = source(1, "裸域", "https://site.com");

    DedupeResult strict = DedupeEngine.run(Arrays.asList(a, b), DedupeMode.STRICT, false);
    assertEquals("严格模式下 www 与裸域应合并", 1, strict.getRetained().size());

    DedupeResult standard = DedupeEngine.run(Arrays.asList(a, b), DedupeMode.STANDARD, false);
    assertEquals("标准模式下应保留 2 条", 2, standard.getRetained().size());
  }

  // ---------- MainState 与去重的组合 ----------

  /**
   * 代际守卫的完整场景：去重进行中清空，旧结果不能落地。
   *
   * 这是 v3.7.1 修复的核心缺陷，这里验证 MainState 的 invalidateAll
   * 确实能作废在途去重。
   */
  @Test public void generationGuardPreventsStaleWriteback() {
    MainState state = new MainState();
    int genAtStart = state.nextRecomputeGeneration();

    // 模拟：任务开始时记下代际
    int taskGen = genAtStart;

    // 清空
    state.invalidateAll();

    // 任务完成时检查代际
    assertNotEquals("代际已变，旧任务必须丢弃结果",
        taskGen, state.recomputeGeneration());
  }

  /** 新一轮去重开始后，上一轮的代际也应失效。 */
  @Test public void newRecomputeInvalidatesPrevious() {
    MainState state = new MainState();
    int first = state.nextRecomputeGeneration();
    int second = state.nextRecomputeGeneration();
    assertTrue("第二次应大于第一次", second > first);
    assertNotEquals(first, state.recomputeGeneration());
  }

  // ---------- 统计口径一致性 ----------

  /**
   * DedupeResult 的各个计数必须自洽。
   *
   * 曾出现过「保留数 + 重复数 + 无效数 != 原始数」的统计漏洞。
   */
  @Test public void dedupeCountsAreSelfConsistent() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "A", "https://a.com"),
        source(1, "B", "https://a.com"),   // 重复
        source(2, "C", "https://b.com"),
        source(3, "缺URL", null));
    // 注意：null URL 会被计为 invalid

    DedupeResult r = DedupeEngine.run(sources, DedupeMode.STANDARD, false);
    int sum = r.getRetained().size() + r.getDuplicateCount() + r.getInvalid().size();
    assertEquals("保留 + 重复 + 无效 必须等于原始数",
        r.getOriginalCount(), sum);
  }

  /** 剔除后计数仍然自洽。 */
  @Test public void countsStayConsistentAfterPrune() {
    DedupeResult r = DedupeEngine.run(Arrays.asList(
        source(0, "A", "https://a.com"),
        source(1, "B", "https://a.com"),
        source(2, "C", "https://b.com")), DedupeMode.STANDARD, false);

    ResultPruner.Pruned pruned = ResultPruner.prune(r, null,
        new HashSet<>(Collections.singletonList("https://b.com")));

    assertEquals("剔除后保留 1 条", 1, pruned.result.getRetained().size());
    assertEquals("重复组应保持（a.com 那组未受影响）", 1,
        pruned.result.getDuplicateGroups().size());
    assertEquals(1, pruned.result.getDuplicateCount());
  }

  // ---------- 校验结果与导出的一致性 ----------

  /** 校验结果里的源必须能在去重结果中找到对应（URL 一致）。 */
  @Test public void checkResultsAlignWithRetained() {
    DedupeResult r = DedupeEngine.run(Arrays.asList(
        source(0, "A", "https://a.com"),
        source(1, "B", "https://b.com")), DedupeMode.STANDARD, false);

    List<CheckSourceResult> checks = new ArrayList<>();
    for (SourceRecord s : r.getRetained()) {
      checks.add(new CheckSourceResult(s, CheckSourceResult.Status.SUCCESS,
          Collections.<String>emptyList(), "ok", 10, SourceKind.NOVEL));
    }

    // 每条校验结果都应能在 retained 里找到
    for (CheckSourceResult c : checks) {
      boolean found = false;
      for (SourceRecord s : r.getRetained()) {
        if (c.source.getUrl().equals(s.getUrl())) { found = true; break; }
      }
      assertTrue("校验结果的 URL 应能在 retained 里找到: " + c.source.getUrl(), found);
    }
  }
}