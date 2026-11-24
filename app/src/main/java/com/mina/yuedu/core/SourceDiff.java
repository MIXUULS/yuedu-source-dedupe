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
      else if (!sameContent(ra, rb)) {
        modified.add(url + "  「" + ra.getName() + "」");
      }
    }
    return new Result(mapA, mapB, added, removed, modified);
  }

  /**
   * 判断两条记录内容是否等价。
   *
   * 不能用 {@code String.valueOf(map)} 比较：{@code AbstractMap.toString()} 按迭代顺序输出，
   * 两份语义相同但键顺序不同的书源会被误判为"已修改"（导出顺序一变就满屏假差异）。
   * 这里改用 MiniJson 序列化（null 与空串归一化后）比较，键顺序不影响结果。
   */
  private static boolean sameContent(SourceRecord a, SourceRecord b) {
    String nameA = a.getRaw().get("bookSourceName") == null ? "" : String.valueOf(a.getRaw().get("bookSourceName"));
    String nameB = b.getRaw().get("bookSourceName") == null ? "" : String.valueOf(b.getRaw().get("bookSourceName"));
    if (!nameA.equals(nameB)) return false;
    return canonical(a).equals(canonical(b));
  }

  /** 把书源序列化成与键顺序无关的稳定文本（顶层与嵌套对象都按键排序）。 */
  private static String canonical(SourceRecord s) {
    try {
      return MiniJson.stringify(sortKeys(s.getRaw()));
    } catch (Exception e) {
      // 极端情况下（不可序列化的值）退化为直接比较，宁可报差异也不要抛异常中断对比
      return String.valueOf(s.getRaw());
    }
  }

  /** 递归把 Map 换成 TreeMap，保证嵌套结构也按键排序。 */
  @SuppressWarnings("unchecked")
  private static Object sortKeys(Object value) {
    if (value instanceof Map) {
      java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        sorted.put(String.valueOf(e.getKey()), sortKeys(e.getValue()));
      }
      return sorted;
    }
    if (value instanceof List) {
      List<Object> out = new ArrayList<>(((List<?>) value).size());
      for (Object item : (List<?>) value) out.add(sortKeys(item));
      return out;
    }
    return value;
  }

  /** 是否无差异（两份内容一致）。 */
  public static boolean isIdentical(Result r) {
    return r.added.isEmpty() && r.removed.isEmpty() && r.modified.isEmpty();
  }
}
