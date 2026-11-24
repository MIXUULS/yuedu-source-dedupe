package com.mina.yuedu.export;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 决定"导出哪些书源"的纯逻辑。
 *
 * 从 MainActivity 抽出来的原因：
 *  1. 这段被 {@code refreshExportPreview()} 高频调用（每次切换分类开关都调），
 *     而原实现每次都重建"可用 URL 集合"——万级校验时是一次 O(n) 全表扫描，
 *     连续切换开关会明显掉帧。
 *  2. 导出范围直接决定用户最终拿到什么文件，规则必须可测。
 *
 * 筛选顺序：
 * <pre>
 *   全量保留源 → （仅可用时）按校验结果过滤 → 按类型过滤
 * </pre>
 */
public final class ExportRecordSelector {

  private ExportRecordSelector() {}

  /**
   * @param retained   去重后保留的书源（可为 null）
   * @param checkResults 校验结果，用于"仅可用"过滤（可为 null）
   * @param onlyUsable 是否只导出校验可用的
   * @param allowKinds 允许导出的类型集合；为空表示"一个都不要"
   * @return 导出列表（不可变，避免调用方误改内部状态）
   */
  public static List<SourceRecord> select(List<SourceRecord> retained,
                                          List<CheckSourceResult> checkResults,
                                          boolean onlyUsable,
                                          Set<SourceKind> allowKinds) {
    if (retained == null || retained.isEmpty()) return Collections.emptyList();
    if (allowKinds == null || allowKinds.isEmpty()) return Collections.emptyList();

    List<SourceRecord> base;
    if (!onlyUsable || checkResults == null || checkResults.isEmpty()) {
      base = retained;
    } else {
      // 只收集可用书源的 URL 再按 URL 过滤：
      // 单次建集合 O(m) + 单次过滤 O(n)，比"每条保留源都遍历 checkResults"的 O(n×m) 快得多。
      Set<String> good = usableUrls(checkResults);
      List<SourceRecord> filtered = new ArrayList<>();
      for (SourceRecord s : retained) {
        if (s.getUrl() != null && good.contains(s.getUrl())) filtered.add(s);
      }
      base = filtered;
    }

    List<SourceRecord> byKind = SourceKind.filter(base, allowKinds);
    // 返回拷贝：SourceKind.filter 可能返回共享列表，防止调用方原地排序污染内部状态
    return new ArrayList<>(byKind);
  }

  /** 收集校验结果为"可用"的书源 URL。 */
  public static Set<String> usableUrls(List<CheckSourceResult> checkResults) {
    Set<String> good = new HashSet<>();
    if (checkResults == null) return good;
    for (CheckSourceResult r : checkResults) {
      if (r == null || r.source == null) continue;
      if (r.isUsable() && r.source.getUrl() != null) good.add(r.source.getUrl());
    }
    return good;
  }

  /** 统计各类型数量，供界面显示"去重后 N 条"（未按开关过滤，展示全貌）。 */
  public static int countByKind(List<SourceRecord> retained, SourceKind kind) {
    if (retained == null || kind == null) return 0;
    int n = 0;
    for (SourceRecord s : retained) if (SourceKind.of(s) == kind) n++;
    return n;
  }
}