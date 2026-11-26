package com.mina.yuedu.check;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验结果的统计汇总，供界面显示「成功 N · 失败 M · 超时 K」与失败原因排行。
 *
 * 从 MainActivity.renderCheckStats 抽出来的原因：
 *  - 失败原因需要归一化（截断、去掉逗号后的补充说明），否则同一个原因会因为
 *    附带不同错误细节而被统计成多条，用户看到的"失败原因分布"是碎的
 *  - 这段纯计算混在 View 更新里，无法单测
 */
public final class CheckStats {

  /** 单条失败原因的最大展示长度，超出截断加省略号。 */
  private static final int MAX_REASON_LEN = 14;

  public final int ok;
  public final int fail;
  public final int timeout;
  public final int skipped;
  /** 归一化后的失败原因 → 出现次数，按出现顺序保留。 */
  public final Map<String, Integer> reasonCount;

  private CheckStats(int ok, int fail, int timeout, int skipped, Map<String, Integer> reasonCount) {
    this.ok = ok;
    this.fail = fail;
    this.timeout = timeout;
    this.skipped = skipped;
    this.reasonCount = reasonCount;
  }

  /** 参与统计的总数（不含 SKIPPED）。 */
  public int counted() {
    return ok + fail + timeout;
  }

  /** 可用率 0-1；没有可统计项时返回 0。 */
  public double passRate() {
    int total = counted();
    return total == 0 ? 0 : (double) ok / total;
  }

  public static CheckStats summarize(List<CheckSourceResult> results) {
    int ok = 0, fail = 0, timeout = 0, skipped = 0;
    Map<String, Integer> reasonCount = new LinkedHashMap<>();
    if (results == null) return new CheckStats(0, 0, 0, 0, reasonCount);

    for (CheckSourceResult r : results) {
      if (r == null) continue;
      if (r.status == CheckSourceResult.Status.SKIPPED) { skipped++; continue; }
      if (r.status == CheckSourceResult.Status.SUCCESS) { ok++; continue; }
      if (r.status == CheckSourceResult.Status.TIMEOUT) { timeout++; continue; }
      fail++;
      String reason = normalizeReason(r.message);
      reasonCount.merge(reason, 1, Integer::sum);
    }
    return new CheckStats(ok, fail, timeout, skipped, reasonCount);
  }

  /**
   * 归一化失败原因：空值兜底、截到第一个逗号、超长截断。
   *
   * 截到第一个逗号是因为失败信息常写成「搜索失效，HTTP 404」这种
   * 「主因 + 细节」的形式，统计时只应看主因。
   */
  static String normalizeReason(String message) {
    String reason = message == null ? "" : message.trim();
    if (reason.isEmpty()) return "未知原因";
    // 中英文逗号都处理。idx 从 0 开始也要截——否则 "，细节" 这种
    // 以逗号开头的串会把整个 ",细节" 当成原因展示。
    int idx = reason.indexOf('，');
    if (idx < 0) idx = reason.indexOf(',');
    if (idx >= 0) reason = reason.substring(0, idx).trim();
    if (reason.isEmpty()) return "未知原因";
    if (reason.length() > MAX_REASON_LEN) reason = reason.substring(0, MAX_REASON_LEN) + "…";
    return reason;
  }
}