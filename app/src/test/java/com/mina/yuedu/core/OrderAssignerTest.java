package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.Assert.*;

/**
 * 顺序号分配器单元测试。
 *
 * 核心风险：本地导入/网络解析/剪贴板/YCK 收集会并发提交，顺序号一旦重复，
 * 去重的"保留哪一条"就不确定，表现为同样输入两次跑出不同结果。
 */
public class OrderAssignerTest {

  private static SourceRecord source(int order, String name) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    return new SourceRecord(order, name, "https://" + name + ".com", raw);
  }

  @Test public void assignsSequentialOrders() {
    OrderAssigner assigner = new OrderAssigner();
    List<SourceRecord> out = assigner.assign(Arrays.asList(
        source(0, "a"), source(0, "b"), source(0, "c")));

    assertEquals(0, out.get(0).getOrder());
    assertEquals(1, out.get(1).getOrder());
    assertEquals(2, out.get(2).getOrder());
    assertEquals(3, assigner.peek());
  }

  /** 多次调用要接着上次的号继续，不能重置。 */
  @Test public void ordersContinueAcrossCalls() {
    OrderAssigner assigner = new OrderAssigner();
    assigner.assign(Arrays.asList(source(0, "a"), source(0, "b")));
    List<SourceRecord> second = assigner.assign(Collections.singletonList(source(0, "c")));
    assertEquals("第二次调用应从 2 开始", 2, second.get(0).getOrder());
  }

  @Test public void emptyInputReturnsAsIsAndDoesNotAdvance() {
    OrderAssigner assigner = new OrderAssigner();
    assertNull(assigner.assign(null));
    assertTrue(assigner.assign(Collections.<SourceRecord>emptyList()).isEmpty());
    assertEquals("空批次不应消耗顺序号", 0, assigner.peek());
  }

  @Test public void resetRestartsFromZero() {
    OrderAssigner assigner = new OrderAssigner();
    assigner.assign(Arrays.asList(source(0, "a"), source(0, "b")));
    assigner.reset();
    assertEquals(0, assigner.peek());
    assertEquals(0, assigner.assign(Collections.singletonList(source(0, "c"))).get(0).getOrder());
  }

  @Test public void setClampsNegativeToZero() {
    OrderAssigner assigner = new OrderAssigner();
    assigner.set(-5);
    assertEquals(0, assigner.peek());
    assigner.set(100);
    assertEquals(100, assigner.peek());
  }

  @Test public void constructorWithStartValue() {
    OrderAssigner assigner = new OrderAssigner(50);
    assertEquals(50, assigner.assign(Collections.singletonList(source(0, "a"))).get(0).getOrder());
  }

  /** 不修改传入列表。 */
  @Test public void doesNotMutateInput() {
    OrderAssigner assigner = new OrderAssigner();
    SourceRecord s = source(99, "a");
    List<SourceRecord> input = new ArrayList<>(Collections.singletonList(s));
    assigner.assign(input);
    assertEquals("原列表里的对象不应被替换", 99, input.get(0).getOrder());
  }

  /**
   * 并发分配时顺序号必须互不重复——这是本类存在的根本目的。
   */
  @Test public void concurrentAssignmentProducesUniqueOrders() throws Exception {
    final OrderAssigner assigner = new OrderAssigner();
    final int threads = 8, perThread = 200;
    final ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int t = 0; t < threads; t++) {
        final int tid = t;
        pool.execute(() -> {
          try {
            start.await();
            for (int i = 0; i < perThread; i++) {
              List<SourceRecord> out = assigner.assign(
                  Collections.singletonList(source(0, "t" + tid + "_" + i)));
              collected.add(out.get(0).getOrder());
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue("并发分配应在超时内完成", done.await(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    int expected = threads * perThread;
    assertEquals("分配总数应正确", expected, collected.size());
    assertEquals("顺序号必须互不重复", expected, new java.util.HashSet<>(collected).size());
    assertEquals("计数器应等于分配总数", expected, assigner.peek());
  }
}