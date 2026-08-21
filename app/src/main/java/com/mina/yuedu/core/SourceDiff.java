package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 两份书源列表的差异计算（可独立测试的纯逻辑，不依赖 Android）。
 * 按 URL 分组，输出新增 / 移除 / 修改三类差异。
 */
public final class SourceDiff {

  /** 一次对比的结果：新增（第二份独有）、移除（第一份独有）、修改（两边都有但内容不同）。 */
  public static final class Result {
    public final Map<String, SourceRecord> mapA;
    public final Map<String, SourceRecord> mapB;
    public final List<String> added;    // 仅在 B 中的 URL
    public final List<String> removed;  // 仅在 A 中的 URL
    public final List<String> modified; // 两边都有但内容不同（带名称说明）

    Result(Map<String, SourceRecord> mapA, Map<String, SourceRecord> mapB,
           List<String> added, List<String> removed, List<String> modified) {
      this.mapA = mapA; this.mapB = mapB;
      this.added = added; this.removed = removed; this.modified = modified;
    }
  }

  private SourceDiff() {}

  /** 按 URL 建立索引，空 URL 的记录被忽略。 */
  private static Map<String, SourceRecord> index(List<SourceRecord> records) {
    Map<String, SourceRecord> map = new LinkedHashMap<>();
    if (records == null) return map;
    for (SourceRecord s : records) if (s.getUrl() != null) map.put(s.getUrl(), s);
    return map;
  }

  /** 计算两份书源列表的差异。 */
  public static Result compute(List<SourceRecord> a, List<SourceRecord> b) {
    Map<String, SourceRecord> mapA = index(a);
    Map<String, SourceRecord> mapB = index(b);
    Set<String> all = new LinkedHashSet<>(mapA.keySet());
    all.addAll(mapB.keySet());

    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> modified = new ArrayList<>();

    for (String url : all) {
      SourceRecord ra = mapA.get(url);
      SourceRecord rb = mapB.get(url);
      if (ra == null) added.add(url);
      else if (rb == null) removed.add(url);
      else if (!String.valueOf(ra.getRaw().get("bookSourceName")).equals(String.valueOf(rb.getRaw().get("bookSourceName")))
          || !String.valueOf(ra.getRaw()).equals(String.valueOf(rb.getRaw()))) {
        modified.add(url + "  「" + ra.getName() + "」");
      }
    }
    return new Result(mapA, mapB, added, removed, modified);
  }

  /** 是否无差异（两份内容一致）。 */
  public static boolean isIdentical(Result r) {
    return r.added.isEmpty() && r.removed.isEmpty() && r.modified.isEmpty();
  }
}
