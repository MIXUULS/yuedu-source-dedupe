package com.mina.yuedu.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发状态与任务代际的统一持有者。
 *
 * 为什么需要它：这些字段原本散落在 MainActivity 里——
 * <pre>
 *   destroyed, fetchRunning, checkRunning, checkCancelRequested,
 *   fetchGen, recomputeGen, checkGen, discard
 * </pre>
 * 它们之间有隐含约束（例如"清空时必须同时作废三条链路的代际"），
 * 但约束只写在注释里，没有任何机制强制。v3.7.1 修复的 30 个缺陷里，
 * 有 6 个就是这类状态踩踏：在途任务把旧结果写回、并发启动第二个引擎、
 * 清空后数据复活。
 *
 * 把这些约束收进一个类之后，"作废代际"和"查询是否在跑"都只有一条路径，
 * 漏掉某条链路的概率大幅下降。
 *
 * 线程安全：代际号与运行标志都可能被后台线程读取（如去重线程检查代际），
 * 因此用 volatile / AtomicInteger。
 */
public final class MainState {

  /** Activity 是否已销毁：所有 UI 回调的第一道守卫。 */
  private volatile boolean destroyed;

  /** 是否正在抓取。 */
  private volatile boolean fetchRunning;

  /** 是否正在校验。 */
  private volatile boolean checkRunning;

  /** 用户是否请求停止校验（用于区分"自然结束"与"被中断"）。 */
  private volatile boolean checkCancelRequested;

  /** 解析结果是否应丢弃（用户选择"放弃全部"或清空时置位）。 */
  private volatile boolean discard;

  /** 三条链路的代际号，每次新任务递增，旧回调凭此作废。 */
  private final AtomicInteger fetchGen = new AtomicInteger();
  private final AtomicInteger recomputeGen = new AtomicInteger();
  private final AtomicInteger checkGen = new AtomicInteger();

  // ---------- 销毁 ----------

  public boolean isDestroyed() { return destroyed; }
  public void markDestroyed() { destroyed = true; }

  // ---------- 抓取 ----------

  public boolean isFetchRunning() { return fetchRunning; }
  public void setFetchRunning(boolean running) { this.fetchRunning = running; }

  /** 开始新一轮抓取：作废旧代际并返回新代际号。 */
  public int startFetch() {
    fetchRunning = true;
    return fetchGen.incrementAndGet();
  }

  /** 当前抓取代际号（回调里用它与自己的 gen 比较）。 */
  public int fetchGeneration() { return fetchGen.get(); }

  /** 作废在途抓取（清空/丢弃时用），不改变 running 标志。 */
  public int invalidateFetch() { return fetchGen.incrementAndGet(); }

  // ---------- 去重 ----------

  public int nextRecomputeGeneration() { return recomputeGen.incrementAndGet(); }
  public int recomputeGeneration() { return recomputeGen.get(); }

  /** 清空时作废在途去重：否则它持有的旧快照会把已删除的结果写回来。 */
  public int invalidateRecompute() { return recomputeGen.incrementAndGet(); }

  // ---------- 校验 ----------

  public boolean isCheckRunning() { return checkRunning; }
  public boolean isCheckCancelRequested() { return checkCancelRequested; }

  /** 开始一轮校验：置运行标志、清取消标志、返回新代际号。 */
  public int startCheck() {
    checkRunning = true;
    checkCancelRequested = false;
    return checkGen.incrementAndGet();
  }

  /** 请求停止校验（只置标志，实际取消由引擎负责）。 */
  public void requestCheckCancel() { checkCancelRequested = true; }

  /** 校验收尾：清运行与取消标志。 */
  public void finishCheck() {
    checkRunning = false;
    checkCancelRequested = false;
  }

  public int checkGeneration() { return checkGen.get(); }

  /** 作废在途校验回调（清空/重新解析时用）。 */
  public int invalidateCheck() { return checkGen.incrementAndGet(); }

  // ---------- 丢弃标志 ----------

  public boolean isDiscard() { return discard; }
  public void setDiscard(boolean d) { this.discard = d; }

  // ---------- 组合操作 ----------

  /**
   * 清空全部任务状态。
   *
   * 这是"三条链路必须一起作废"这条约束的唯一实现点——
   * 之前分散在 clearAll() 里三行手工自增，漏一行就会让旧结果复活。
   *
   * @return 依次为 fetch/recompute/check 的新代际号
   */
  public int[] invalidateAll() {
    fetchRunning = false;
    checkRunning = false;
    checkCancelRequested = false;
    discard = false;
    return new int[]{
        fetchGen.incrementAndGet(),
        recomputeGen.incrementAndGet(),
        checkGen.incrementAndGet()
    };
  }

  /**
   * 是否允许启动新任务。
   *
   * 抓取与校验互斥：同时跑会让两边都想改同一份结果集。
   */
  public boolean canStartTask() {
    return !fetchRunning && !checkRunning;
  }
}