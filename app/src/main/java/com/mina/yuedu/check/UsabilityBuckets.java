package com.mina.yuedu.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把校验结果按「可用程度」分成三档，供导出与界面使用。
 *
 * 用户的原始需求：**去掉无论如何都不能用的网站**（换代理、换 UA 都救不回来的）。
 * 但不是所有失败都等价——需要把「确定死了」和「只是这次没成功」分开：
 *
 * <pre>
 *   USABLE      校验通过。可以直接用。
 *
 *   SITE_ALIVE  站点在线，但书源规则这次没跑通。
 *               典型：403 反爬、429 限流、5xx 抖动、规则过时。
 *               **不该删**——站点还在维护，改规则或换时间就能用。
 *
 *   SITE_DEAD   站点确定不可用：DNS 解析失败、连接被拒、无路由。
 *               换什么手段都没用，这才是真正该清理的。
 * </pre>
 *
 * 实测依据（2257 条真实书源 / 1443 个唯一域名）：
 * <pre>
 *   正常可用        921 个（63.8%）
 *   有响应但 4xx/5xx 173 个（12.0%）← 403 占 106，站点完全活着
 *   连不上           73 个（5.1%）
 *   DNS 失败         37 个（2.6%）
 * </pre>
 * 也就是说：把 4xx/5xx 当死站会误杀 173 个域名，比真正的死站还多 57%。
 */
public final class UsabilityBuckets {

  /** 三档分类。 */
  public enum Level {
    /** 校验通过。 */
    USABLE,
    /** 站点在线但书源没跑通——不应清理。 */
    SITE_ALIVE,
    /** 站点确定不可用——可以清理。 */
    SITE_DEAD,
    /** 跳过校验或超时，无法判定。 */
    UNKNOWN
  }

  public final List<CheckSourceResult> usable;
  public final List<CheckSourceResult> siteAlive;
  public final List<CheckSourceResult> siteDead;
  public final List<CheckSourceResult> unknown;

  private UsabilityBuckets(List<CheckSourceResult> usable, List<CheckSourceResult> siteAlive,
                           List<CheckSourceResult> siteDead, List<CheckSourceResult> unknown) {
    this.usable = usable;
    this.siteAlive = siteAlive;
    this.siteDead = siteDead;
    this.unknown = unknown;
  }

  /** 总数。 */
  public int size() {
    return usable.size() + siteAlive.size() + siteDead.size() + unknown.size();
  }

  /**
   * 按校验结果分档。
   */
  public static UsabilityBuckets split(List<CheckSourceResult> results) {
    List<CheckSourceResult> usable = new ArrayList<>();
    List<CheckSourceResult> alive = new ArrayList<>();
    List<CheckSourceResult> dead = new ArrayList<>();
    List<CheckSourceResult> unknown = new ArrayList<>();

    if (results != null) {
      for (CheckSourceResult r : results) {
        if (r == null) continue;
        switch (classify(r)) {
          case USABLE: usable.add(r); break;
          case SITE_DEAD: dead.add(r); break;
          case SITE_ALIVE: alive.add(r); break;
          default: unknown.add(r); break;
        }
      }
    }
    return new UsabilityBuckets(usable, alive, dead, unknown);
  }

  /**
   * 判定单条结果属于哪一档。
   *
   * 判定依据只有「失败原因里是否出现确定的死站信号」——
   * 这样即使校验五步中某步失败，只要站点本身活着就不会被误判成死站。
   */
  public static Level classify(CheckSourceResult r) {
    if (r == null) return Level.UNKNOWN;
    if (r.status == CheckSourceResult.Status.SUCCESS) return Level.USABLE;
    if (r.status == CheckSourceResult.Status.SKIPPED) return Level.UNKNOWN;
    if (r.status == CheckSourceResult.Status.TIMEOUT) return Level.UNKNOWN;

    // FAILED：看失败原因
    if (isDefinitelyDead(r)) return Level.SITE_DEAD;
    return Level.SITE_ALIVE;
  }

  /**
   * 失败原因里是否出现「确定站点不可用」的信号。
   *
   * 只认这几类（与 SiteLiveness.DEAD 对齐）：
   *   - 明确的「网站失效」标记（由 CheckSourceEngine 在 DNS/连接被拒时写入）
   *   - 文本形式的 DNS 失败 / 连接被拒
   *
   * 注意：不能把「403」「404」「超时」当死站。
   */
  static boolean isDefinitelyDead(CheckSourceResult r) {
    if (r == null) return false;
    if (r.groups != null) {
      for (String g : r.groups) {
        if (isDeadSignal(g)) return true;
      }
    }
    return isDeadSignal(r.message);
  }

  /** 单个原因字符串是否是死站信号。 */
  static boolean isDeadSignal(String text) {
    if (text == null) return false;
    String t = text.trim();
    if (t.isEmpty()) return false;
    // 「网站失效」只在 CheckSourceEngine 确认 DNS/连接被拒时写入
    if (t.equals("网站失效")) return true;

    String lower = t.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("unable to resolve host")
        || lower.contains("no address associated")
        || lower.contains("nodename nor servname")
        || lower.contains("getaddrinfo")
        || lower.contains("connection refused")
        || lower.contains("econnrefused")
        || lower.contains("no route to host")
        || lower.contains("unknownhost");
  }

  /**
   * 生成「站点确认不可用」的 URL 集合，供按 URL 过滤导出。
   */
  public java.util.Set<String> deadUrls() {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (CheckSourceResult r : siteDead) {
      if (r.source != null && r.source.getUrl() != null) out.add(r.source.getUrl());
    }
    return out;
  }

  /**
   * 失败原因聚合，供界面显示「N 条站点已死 · M 条站点在线但规则失效」。
   */
  public Map<String, Integer> deadReasonCounts() {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (CheckSourceResult r : siteDead) {
      String key = firstReason(r);
      out.merge(key, 1, Integer::sum);
    }
    return out;
  }

  /** 取第一条失败原因，没有则给兜底文案。 */
  private static String firstReason(CheckSourceResult r) {
    if (r.groups != null && !r.groups.isEmpty()) {
      String g = r.groups.get(0);
      if (g != null && !g.trim().isEmpty()) return g.trim();
    }
    String m = r.message;
    return (m == null || m.trim().isEmpty()) ? "未知原因" : m.trim();
  }
}