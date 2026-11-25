package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从当前去重结果中挑出"要重验的书源"。
 *
 * 从 MainActivity.recheckUrls 抽出来的原因：这段决定重验范围，边界情况多
 * （URL 为 null、来源已不在结果里、重复 URL 只验一次），出错时表现为
 * "点了重验但什么都没发生"或"重验了错误的源"，很难复现。
 */
public final class RecheckPlanner {

  private RecheckPlanner() {}

  /**
   * @param retained 当前去重后保留的书源
   * @param urls     要重验的 URL 集合（通常来自校验结果里失败/超时的条目）
   * @return 与 urls 对应且仍在 retained 中的书源；urls 为空或没有匹配时返回空列表
   */
  public static List<SourceRecord> plan(List<SourceRecord> retained, Set<String> urls) {
    if (retained == null || retained.isEmpty() || urls == null || urls.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> targets = new HashSet<>(urls);
    List<SourceRecord> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (SourceRecord s : retained) {
      if (s.getUrl() == null) continue;
      if (!targets.contains(s.getUrl())) continue;
      // 同一 URL 只重验一次：retained 理论上已去重，但防御性处理避免了重复校验浪费配额
      if (seen.add(s.getUrl())) out.add(s);
    }
    return out;
  }

  /** 单个书源的快速重验。 */
  public static List<SourceRecord> planSingle(List<SourceRecord> retained, String url) {
    if (url == null || url.trim().isEmpty()) return Collections.emptyList();
    return plan(retained, Collections.singleton(url.trim()));
  }
}