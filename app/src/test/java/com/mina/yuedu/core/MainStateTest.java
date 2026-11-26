package com.mina.yuedu.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 并发状态与代际号测试。
 *
 * 这些状态之间原本有隐含约束但无强制机制，v3.7.1 修的 30 个缺陷里有 6 个
 * 就是这类踩踏。收进 MainState 后由这些用例守住约束。
 */
public class MainStateTest {

  // ---------- 销毁 ----------

  @Test public void destroyedStartsFalseAndCanBeMarked() {
    MainState s = new MainState();
    assertFalse(s.isDestroyed());
    s.markDestroyed();
    assertTrue(s.isDestroyed());
  }

  // ---------- 抓取 ----------

  @Test public void startFetchIncrementsGenerationAndSetsRunning() {
    MainState s = new MainState();
    int g1 = s.startFetch();
    assertTrue(s.isFetchRunning());
    assertEquals(1, g1);

    int g2 = s.startFetch();
    assertEquals("每次开始都应递增", 2, g2);
    assertTrue(g2 > g1);
  }

  /** 作废后旧代际必须失效——这是"旧任务回调不能写结果"的基础。 */
  @Test public void invalidateFetchMakesOldGenerationStale() {
    MainState s = new MainState();
    int oldGen = s.startFetch();
    s.invalidateFetch();
    assertNotEquals("旧代际必须失效", oldGen, s.fetchGeneration());
  }

  /** invalidateFetch 不改变 running 标志（那是 setFetchRunning 的职责）。 */
  @Test public void invalidateFetchDoesNotClearRunning() {
    MainState s = new MainState();
    s.startFetch();
    s.invalidateFetch();
    assertTrue("作废代际不等于停止任务", s.isFetchRunning());
  }

  // ---------- 去重 ----------

  @Test public void recomputeGenerationIncrements() {
    MainState s = new MainState();
    assertEquals(1, s.nextRecomputeGeneration());
    assertEquals(2, s.nextRecomputeGeneration());
    assertEquals(2, s.recomputeGeneration());
  }

  // ---------- 校验 ----------

  @Test public void startCheckResetsCancelAndIncrements() {
    MainState s = new MainState();
    s.requestCheckCancel();
    assertTrue(s.isCheckCancelRequested());

    int g1 = s.startCheck();
    assertTrue(s.isCheckRunning());
    assertFalse("新一轮校验应清除上次的取消标志", s.isCheckCancelRequested());
    assertEquals(1, g1);
  }

  @Test public void finishCheckClearsBothFlags() {
    MainState s = new MainState();
    s.startCheck();
    s.requestCheckCancel();
    s.finishCheck();
    assertFalse(s.isCheckRunning());
    assertFalse(s.isCheckCancelRequested());
  }

  // ---------- 丢弃标志 ----------

  @Test public void discardFlagRoundTrips() {
    MainState s = new MainState();
    assertFalse(s.isDiscard());
    s.setDiscard(true);
    assertTrue(s.isDiscard());
    s.setDiscard(false);
    assertFalse(s.isDiscard());
  }

  // ---------- 组合操作 ----------

  /**
   * 核心约束：清空时三条链路的代际必须**同时**作废。
   *
   * 这是 v3.7.1 修复的关键缺陷原型——clearAll 当时只自增了 fetchGen，
   * 导致在途去重任务把已删除的旧结果写回来，用户以为清空了其实又出现。
   */
  @Test public void invalidateAllInvalidatesEveryGeneration() {
    MainState s = new MainState();
    int fetchBefore = s.fetchGeneration();
    int recomputeBefore = s.recomputeGeneration();
    int checkBefore = s.checkGeneration();
    s.startFetch();
    s.nextRecomputeGeneration();
    s.startCheck();

    int[] gens = s.invalidateAll();

    assertNotEquals("fetchGen 必须作废", fetchBefore, gens[0]);
    assertNotEquals("recomputeGen 必须作废，否则旧结果会复活", recomputeBefore, gens[1]);
    assertNotEquals("checkGen 必须作废", checkBefore, gens[2]);
    assertEquals(gens[0], s.fetchGeneration());
    assertEquals(gens[1], s.recomputeGeneration());
    assertEquals(gens[2], s.checkGeneration());
  }

  /** invalidateAll 同时清掉所有运行标志。 */
  @Test public void invalidateAllClearsRunningFlags() {
    MainState s = new MainState();
    s.startFetch();
    s.startCheck();
    s.setDiscard(true);

    s.invalidateAll();

    assertFalse(s.isFetchRunning());
    assertFalse(s.isCheckRunning());
    assertFalse(s.isCheckCancelRequested());
    assertFalse(s.isDiscard());
  }

  /** 抓取与校验互斥。 */
  @Test public void cannotStartTaskWhileBusy() {
    MainState s = new MainState();
    assertTrue("空闲时应允许", s.canStartTask());

    s.startFetch();
    assertFalse("抓取中不允许", s.canStartTask());

    s.setFetchRunning(false);
    s.startCheck();
    assertFalse("校验中不允许", s.canStartTask());

    s.finishCheck();
    assertTrue("都停了应允许", s.canStartTask());
  }

  /** 代际号单调递增，不会回绕到旧值。 */
  @Test public void generationsAreMonotonic() {
    MainState s = new MainState();
    int prev = 0;
    for (int i = 0; i < 100; i++) {
      int g = s.startFetch();
      assertTrue("代际必须严格递增", g > prev);
      prev = g;
    }
  }

  /** 多次 invalidateAll 之后代际仍然互不相同。 */
  @Test public void repeatedInvalidationKeepsGenerationsDistinct() {
    MainState s = new MainState();
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (int i = 0; i < 20; i++) {
      int[] gens = s.invalidateAll();
      assertTrue("fetch 代际不应重复", seen.add(gens[0]));
      assertTrue("recompute 代际不应重复", seen.add(gens[1] + 1000));
      assertTrue("check 代际不应重复", seen.add(gens[2] + 2000));
    }
  }
}