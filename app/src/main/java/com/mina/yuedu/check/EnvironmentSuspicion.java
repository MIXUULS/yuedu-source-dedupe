package com.mina.yuedu.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判断「连接类失败」是不是环境（代理/VPN/网络）造成的，而不是书源自身的问题。
 *
 * 为什么需要这个类（真人实测发现）：
 * <pre>
 *   同一批 2257 条书源，用户在手机开代理后重跑校验：
 *     无代理：857 可用 / 1400 失败
 *     有代理：626 可用 / 1631 失败   ← 反而更差
 *
 *   失败明细里大量是「站点在线但连接异常」，耗时 70-80 秒。
 *   但同一批站点单独测是通的（如 linovel.net 返回 403 = 站点活着）。
 * </pre>
 *
 * 结论：那 231 条「变失败」的书源，问题在**代理链路**，不在书源。
 * 但界面上它们和真正失效的源长得一模一样，用户会误判成「书源变差了」。
 *
 * 本类的职责：从失败耗时与原因里识别出「疑似环境问题」的特征，
 * 提示用户「这批失败可能是网络环境造成的，建议关代理再跑一次」。
 */
public final class EnvironmentSuspicion {

  /** 判定结果。 */
  public static final class Verdict {
    /** 是否是疑似环境（代理/VPN）造成的失败。 */
    public final boolean suspectedEnvironment;
    /** 耗时中位数是否异常长（毫秒）。 */
    public final long medianCostMs;
    /** 连接类失败占比（0-1）。 */
    public final double connectionFailureRatio;
    /** 给用户看的建议，无怀疑时为 null。 */
    public final String advice;

    Verdict(boolean suspected, long median, double ratio, String advice) {
      this.suspectedEnvironment = suspected;
      this.medianCostMs = median;
      this.connectionFailureRatio = ratio;
      this.advice = advice;
    }
  }

  /** 连接类失败的原因关键词（这些不代表站点死了）。 */
  private static final String[] CONNECTION_HINTS = {
      "站点在线但连接异常", "网站失效", "超时", "连接超时", "connection",
      "timeout", "reset", "refused", "eof", "ssl", "handshake"
  };

  /** 耗时超过这个值就值得怀疑（正常校验单条很少超过 20 秒）。 */
  private static final long SLOW_MS = 30_000L;

  /** 连接类失败占比超过这个值就提示环境问题。 */
  private static final double RATIO_THRESHOLD = 0.60;

  private EnvironmentSuspicion() {}

  /**
   * 分析一批校验结果，判断失败是否可能由网络环境造成。
   *
   * 只在「失败很多、且失败以连接类为主、且耗时异常长」三条同时成立时才提示，
   * 避免正常的结果差异也弹提示骚扰用户。
   */
  public static Verdict analyze(List<CheckSourceResult> results) {
    if (results == null || results.isEmpty()) {
      return new Verdict(false, 0, 0, null);
    }

    List<Long> failCosts = new ArrayList<>();
    int failed = 0;
    int connectionFailures = 0;

    for (CheckSourceResult r : results) {
      if (r == null || r.status != CheckSourceResult.Status.FAILED) continue;
      failed++;
      failCosts.add(r.respondTimeMs);
      if (isConnectionFailure(r)) connectionFailures++;
    }

    if (failed == 0) return new Verdict(false, 0, 0, null);

    double ratio = connectionFailures / (double) failed;
    long median = median(failCosts);

    // 三条同时成立才提示：别把正常的少量失败也当成环境问题
    boolean slow = median >= SLOW_MS;
    boolean manyConnectionFailures = ratio >= RATIO_THRESHOLD;
    boolean suspected = slow && manyConnectionFailures && failed >= 20;

    String advice = null;
    if (suspected) {
      advice = "本次失败以「连接类」为主，且平均耗时很长（中位数 "
          + (median / 1000) + " 秒），很可能是网络环境（代理/VPN）造成的，"
          + "而不是书源失效。建议关掉代理或换个节点再跑一次校验对比结果。";
    }
    return new Verdict(suspected, median, ratio, advice);
  }

  /** 该条失败是否属于「连接层面」的问题（而非规则或内容问题）。 */
  static boolean isConnectionFailure(CheckSourceResult r) {
    if (r == null) return false;
    StringBuilder sb = new StringBuilder();
    if (r.groups != null) {
      for (String g : r.groups) {
        if (g != null) sb.append(g).append(' ');
      }
    }
    if (r.message != null) sb.append(r.message);
    String text = sb.toString().toLowerCase(Locale.ROOT);
    if (text.isEmpty()) return false;

    for (String hint : CONNECTION_HINTS) {
      if (text.contains(hint.toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  /** 中位数；空列表返回 0。 */
  static long median(List<Long> values) {
    if (values == null || values.isEmpty()) return 0;
    List<Long> copy = new ArrayList<>(values);
    copy.sort(Long::compare);
    int n = copy.size();
    return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2;
  }
}