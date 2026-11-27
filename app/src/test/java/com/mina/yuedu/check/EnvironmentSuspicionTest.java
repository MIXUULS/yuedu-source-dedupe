package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 环境怀疑判定测试。
 *
 * 真实场景（用户实测）：同一批 2257 条书源
 * <pre>
 *   无代理：857 可用 / 1400 失败
 *   有代理：626 可用 / 1631 失败    ← 反而更差
 *   失败明细耗时 70-80 秒，原因都是「站点在线但连接异常」
 * </pre>
 * 这类失败不该让用户误以为是书源变差了。
 */
public class EnvironmentSuspicionTest {

  private static CheckSourceResult failed(String name, long costMs, String... reasons) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", "https://" + name + ".com");
    SourceRecord s = new SourceRecord(0, name, "https://" + name + ".com", raw);
    List<String> g = new ArrayList<>();
    Collections.addAll(g, reasons);
    return new CheckSourceResult(s, CheckSourceResult.Status.FAILED, g,
        String.join(",", reasons), costMs, SourceKind.NOVEL);
  }

  private static CheckSourceResult ok(String name, long costMs) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", "https://" + name + ".com");
    SourceRecord s = new SourceRecord(0, name, "https://" + name + ".com", raw);
    return new CheckSourceResult(s, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "校验成功", costMs, SourceKind.NOVEL);
  }

  // ---------- 连接类失败识别 ----------

  @Test public void recognizesConnectionFailures() {
    assertTrue(EnvironmentSuspicion.isConnectionFailure(
        failed("a", 1000, "站点在线但连接异常")));
    assertTrue(EnvironmentSuspicion.isConnectionFailure(
        failed("b", 1000, "网站失效")));
    assertTrue(EnvironmentSuspicion.isConnectionFailure(
        failed("c", 1000, "校验超时")));
  }

  /** 规则失效不是连接问题。 */
  @Test public void ruleFailuresAreNotConnection() {
    assertFalse("搜索失效是规则问题",
        EnvironmentSuspicion.isConnectionFailure(failed("d", 1000, "搜索失效")));
    assertFalse("目录失效是规则问题",
        EnvironmentSuspicion.isConnectionFailure(failed("e", 1000, "目录失效")));
    assertFalse("正文失效是规则问题",
        EnvironmentSuspicion.isConnectionFailure(failed("f", 1000, "正文失效")));
  }

  @Test public void nullSafe() {
    assertFalse(EnvironmentSuspicion.isConnectionFailure(null));
  }

  // ---------- 中位数 ----------

  @Test public void medianOfOddCount() {
    assertEquals(3L, EnvironmentSuspicion.median(java.util.Arrays.asList(1L, 3L, 5L)));
  }

  @Test public void medianOfEvenCount() {
    assertEquals(3L, EnvironmentSuspicion.median(java.util.Arrays.asList(1L, 2L, 4L, 5L)));
  }

  @Test public void medianOfEmpty() {
    assertEquals(0L, EnvironmentSuspicion.median(Collections.<Long>emptyList()));
    assertEquals(0L, EnvironmentSuspicion.median(null));
  }

  // ---------- 综合判定 ----------

  /**
   * 核心场景：大量连接类失败 + 耗时很长 → 应提示环境问题。
   */
  @Test public void suspectsEnvironmentOnSlowConnectionFailures() {
    List<CheckSourceResult> results = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      results.add(failed("s" + i, 75_000, "站点在线但连接异常"));
    }
    results.add(ok("good", 500));

    EnvironmentSuspicion.Verdict v = EnvironmentSuspicion.analyze(results);
    assertTrue("应怀疑是环境问题", v.suspectedEnvironment);
    assertNotNull("应给出建议", v.advice);
    assertTrue("建议里应提到代理", v.advice.contains("代理"));
    assertEquals(75_000L, v.medianCostMs);
  }

  /** 全是规则失效（不是连接问题）→ 不该提示环境。 */
  @Test public void doesNotSuspectOnRuleFailures() {
    List<CheckSourceResult> results = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      results.add(failed("r" + i, 3_000, "搜索失效,目录失效"));
    }
    EnvironmentSuspicion.Verdict v = EnvironmentSuspicion.analyze(results);
    assertFalse("规则失效不该怀疑环境", v.suspectedEnvironment);
    assertNull(v.advice);
  }

  /** 连接失败但很快（说明是明确拒绝，不是代理慢）→ 不该提示。 */
  @Test public void doesNotSuspectWhenFailuresAreFast() {
    List<CheckSourceResult> results = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      results.add(failed("q" + i, 800, "站点在线但连接异常"));
    }
    EnvironmentSuspicion.Verdict v = EnvironmentSuspicion.analyze(results);
    assertFalse("失败很快说明不是链路问题", v.suspectedEnvironment);
  }

  /** 失败数量太少 → 不提示（避免正常波动也弹窗）。 */
  @Test public void doesNotSuspectOnFewFailures() {
    List<CheckSourceResult> results = new ArrayList<>();
    for (int i = 0; i < 5; i++) results.add(failed("f" + i, 75_000, "站点在线但连接异常"));
    for (int i = 0; i < 100; i++) results.add(ok("o" + i, 300));

    EnvironmentSuspicion.Verdict v = EnvironmentSuspicion.analyze(results);
    assertFalse("只失败 5 条不该提示环境", v.suspectedEnvironment);
  }

  /** 连接类失败占比不够高 → 不提示。 */
  @Test public void doesNotSuspectWhenMostFailuresAreRuleFailures() {
    List<CheckSourceResult> results = new ArrayList<>();
    for (int i = 0; i < 10; i++) results.add(failed("c" + i, 75_000, "站点在线但连接异常"));
    for (int i = 0; i < 40; i++) results.add(failed("r" + i, 75_000, "搜索失效"));
    // 连接类只占 20%
    EnvironmentSuspicion.Verdict v = EnvironmentSuspicion.analyze(results);
    assertFalse("连接失败占比不足不该提示", v.suspectedEnvironment);
  }

  @Test public void emptyResultsSafe() {
    assertFalse(EnvironmentSuspicion.analyze(null).suspectedEnvironment);
    assertFalse(EnvironmentSuspicion.analyze(
        Collections.<CheckSourceResult>emptyList()).suspectedEnvironment);
  }

  /** 全成功时不提示。 */
  @Test public void allSuccessNoSuspicion() {
    List<CheckSourceResult> results = new ArrayList<>();
    for (int i = 0; i < 50; i++) results.add(ok("s" + i, 300));
    assertFalse(EnvironmentSuspicion.analyze(results).suspectedEnvironment);
  }

  /** 占比字段准确反映实际情况。 */
  @Test public void ratioReflectsReality() {
    List<CheckSourceResult> results = new ArrayList<>();
    for (int i = 0; i < 3; i++) results.add(failed("c" + i, 75_000, "站点在线但连接异常"));
    for (int i = 0; i < 7; i++) results.add(failed("r" + i, 75_000, "搜索失效"));

    EnvironmentSuspicion.Verdict v = EnvironmentSuspicion.analyze(results);
    assertEquals(0.3, v.connectionFailureRatio, 0.01);
  }
}