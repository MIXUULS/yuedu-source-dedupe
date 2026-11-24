package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.DuplicateGroup;
import com.mina.yuedu.model.SourceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从已经算好的去重结果里剔除指定 URL 的书源，同步维护重复分组与校验结果。
 *
 * 从 MainActivity 抽出来的原因：这里维护着三份互相关联的数据
 * （retained / duplicateGroups / checkResults），任何一份没同步都会让统计虚高；
 * 历史实现就是漏了刷新 duplicateGroups 的 removed 列表（删除后"重复数"仍按旧列表算）。
 * 纯逻辑，不依赖 Android，可直接单测。
 */
public final class ResultPruner {

  /** 一次剔除的结果。调用方用 {@link #removedCount} 而不是传入集合大小去提示用户。 */
  public static final class Pruned {
    public final DedupeResult result;
    public final List<CheckSourceResult> checkResults;
    /** 实际从 retained 里移除的条数（传入集合里可能含"已被合并的重复源"，它们本来就不在 retained）。 */
    public final int removedCount;

    Pruned(DedupeResult result, List<CheckSourceResult> checkResults, int removedCount) {
      this.result = result;
      this.checkResults = Collections.unmodifiableList(checkResults);
      this.removedCount = removedCount;
    }
  }

  private ResultPruner() {}

  /**
   * @param result       当前去重结果
   * @param checkResults 当前校验结果，可为 null
   * @param urls         要移除的 URL 集合，可为 null（此时原样返回）
   */
  public static Pruned prune(DedupeResult result, List<CheckSourceResult> checkResults, Set<String> urls) {
    if (result == null) {
      return new Pruned(null, checkResults == null ? new ArrayList<>() : new ArrayList<>(checkResults), 0);
    }
    Set<String> removeSet = urls == null ? Collections.emptySet() : new HashSet<>(urls);

    int removedCount = 0;
    List<SourceRecord> kept = new ArrayList<>();
    for (SourceRecord s : result.getRetained()) {
      if (s.getUrl() == null || !removeSet.contains(s.getUrl())) kept.add(s);
      else removedCount++;
    }

    // 校验结果只保留仍然存活的书源
    Set<String> keptUrls = new HashSet<>();
    for (SourceRecord s : kept) if (s.getUrl() != null) keptUrls.add(s.getUrl());
    List<CheckSourceResult> newChecks = new ArrayList<>();
    if (checkResults != null) {
      for (CheckSourceResult c : checkResults) {
        if (c.source == null || c.source.getUrl() == null || keptUrls.contains(c.source.getUrl())) newChecks.add(c);
      }
    }

    // 重建分组结构，让统计与实际保留的书源一致：
    //  - 保留源被删掉的组整个丢弃（组已不成立）
    //  - 组内重复项被删掉的写回刷新后的列表，否则 getDuplicateCount() 仍按旧列表统计会虚高
    List<DuplicateGroup> newGroups = new ArrayList<>();
    for (DuplicateGroup g : result.getDuplicateGroups()) {
      String keptUrl = g.getKept().getUrl();
      if (keptUrl != null && removeSet.contains(keptUrl)) continue;
      List<SourceRecord> removed = new ArrayList<>();
      for (SourceRecord s : g.getRemoved()) {
        if (s.getUrl() == null || !removeSet.contains(s.getUrl())) removed.add(s);
      }
      if (removed.isEmpty()) continue; // 重复项已全部删除，该组不再有重复
      newGroups.add(new DuplicateGroup(g.getKey(), g.getReason(), g.getKept(), removed));
    }

    DedupeResult pruned = new DedupeResult(result.getOriginalCount(), kept, newGroups, result.getInvalid());
    return new Pruned(pruned, newChecks, removedCount);
  }
}