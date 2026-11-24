package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 为书源分配互不冲突的全局顺序号。
 *
 * 从 MainActivity 抽出来的原因：这是并发正确性的关键点——本地导入、网络解析、
 * 剪贴板导入、YCK 收集都可能并发提交，顺序号一旦重复，去重结果的"保留哪一条"
 * 就会不确定（排序不稳定），表现为"同样输入两次跑出不同结果"。
 *
 * 原实现靠"调用方记得在 buckets 锁内调用"这条隐式约定维持正确性，很容易漏。
 * 现在把计数器与分配动作封装进来，锁由本类自己持有。
 */
public final class OrderAssigner {

  private int next;

  public OrderAssigner() {
    this(0);
  }

  public OrderAssigner(int start) {
    this.next = start;
  }

  /**
   * 为一批记录分配连续顺序号。
   *
   * @param records 待分配的书源，可为 null
   * @return 带新顺序号的新列表（不修改入参）；入参为空时原样返回
   */
  public synchronized List<SourceRecord> assign(List<SourceRecord> records) {
    if (records == null || records.isEmpty()) return records;
    List<SourceRecord> out = new ArrayList<>(records.size());
    for (SourceRecord s : records) out.add(s.withOrder(next++));
    return out;
  }

  /** 当前计数器值（下一个将被分配的顺序号）。 */
  public synchronized int peek() {
    return next;
  }

  /** 重置计数器（清空全部书源时调用）。 */
  public synchronized void reset() {
    next = 0;
  }

  /** 直接设定计数器（从持久化状态恢复时用）。 */
  public synchronized void set(int value) {
    next = Math.max(0, value);
  }
}