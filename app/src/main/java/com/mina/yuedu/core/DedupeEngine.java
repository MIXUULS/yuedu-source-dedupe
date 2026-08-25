package com.mina.yuedu.core;
import com.mina.yuedu.model.*;
import java.util.*;
public final class DedupeEngine {
  private DedupeEngine(){}
  public static DedupeResult run(List<SourceRecord> sources, DedupeMode mode, boolean clean){
    List<InvalidSource> invalid=new ArrayList<>();
    // 名称模式：按名称相似度分组，不走 URL 规范化
    if (mode == DedupeMode.NAME) {
      return runByName(sources, clean, invalid);
    }
    Map<String,List<SourceRecord>> buckets=new LinkedHashMap<>();
    for(SourceRecord s: sources){
      String u=s.getUrl();
      if(u==null){ invalid.add(new InvalidSource(InvalidSource.Kind.MISSING_URL, s.getName())); continue; }
      if(u.trim().isEmpty()){ invalid.add(new InvalidSource(InvalidSource.Kind.EMPTY_URL, s.getName())); continue; }
      try{
        String k=UrlNormalizer.key(u, mode);
        SourceRecord v=clean? s.withName(NameCleaner.clean(s.getName())) : s;
        buckets.computeIfAbsent(k, x->new ArrayList<>()).add(v);
      }catch(Exception e){ invalid.add(new InvalidSource(InvalidSource.Kind.INVALID_URL, u)); }
    }
    return buildResult(sources.size(), buckets, mode, clean);
  }

  /** 按名称相似度去重。 */
  private static DedupeResult runByName(List<SourceRecord> sources, boolean clean, List<InvalidSource> invalid) {
    List<SourceRecord> processed = new ArrayList<>(sources.size());
    for (SourceRecord s : sources) {
      if (s.getUrl() == null) {
        invalid.add(new InvalidSource(InvalidSource.Kind.MISSING_URL, s.getName()));
        continue;
      }
      if (s.getUrl().trim().isEmpty()) {
        invalid.add(new InvalidSource(InvalidSource.Kind.EMPTY_URL, s.getName()));
        continue;
      }
      processed.add(clean ? s.withName(NameCleaner.clean(s.getName())) : s);
    }
    // 按名称分组
    List<List<SourceRecord>> nameGroups = NameSimilarity.groupByName(processed);
    // 将分组结果转换为 buckets 格式（key = 归一化名称）
    Map<String, List<SourceRecord>> buckets = new LinkedHashMap<>();
    Set<SourceRecord> grouped = new HashSet<>();
    for (List<SourceRecord> group : nameGroups) {
      String key = NameSimilarity.normalize(group.get(0).getName());
      key = key.isEmpty() ? group.get(0).getName() : key;
      buckets.put(key, new ArrayList<>(group));
      grouped.addAll(group);
    }
    // 未分组的单独加入
    for (SourceRecord s : processed) {
      if (!grouped.contains(s)) {
        String key = s.getUrl(); // 用 URL 作为 key，确保唯一
        buckets.computeIfAbsent(key, x -> new ArrayList<>()).add(s);
      }
    }
    return buildResult(sources.size(), buckets, DedupeMode.NAME, clean, invalid);
  }

  /** 从 buckets 构建最终结果。 */
  private static DedupeResult buildResult(int total, Map<String, List<SourceRecord>> buckets,
                                           DedupeMode mode, boolean clean) {
    return buildResult(total, buckets, mode, clean, new ArrayList<InvalidSource>());
  }

  /** 从 buckets 构建最终结果（携带外部传入的 invalid 列表）。 */
  private static DedupeResult buildResult(int total, Map<String, List<SourceRecord>> buckets,
                                           DedupeMode mode, boolean clean, List<InvalidSource> invalid) {
    List<SourceRecord> kept = new ArrayList<>();
    List<DuplicateGroup> groups = new ArrayList<>();
    for (Map.Entry<String, List<SourceRecord>> e : buckets.entrySet()) {
      List<SourceRecord> g = e.getValue();
      g.sort((a, b) -> { int d = Integer.compare(score(b), score(a)); return d != 0 ? d : Integer.compare(a.getOrder(), b.getOrder()); });
      SourceRecord k = g.get(0);
      kept.add(k);
      if (g.size() > 1) {
        groups.add(new DuplicateGroup(e.getKey(), reason(mode), k, g.subList(1, g.size())));
      }
    }
    kept.sort(Comparator.comparingInt(SourceRecord::getOrder));
    return new DedupeResult(total, kept, groups, invalid);
  }

  private static int score(SourceRecord s){
    int n=0;
    if(s.getName()!=null && !s.getName().trim().isEmpty()) n+=10;
    // 含"官方"等优先关键词加分
    if (NameSimilarity.hasPreferredKeyword(s.getName())) n+=15;
    if(s.getUrl()!=null && !s.getUrl().trim().isEmpty()) n+=10;
    for(Object v: s.getRaw().values()) if(v!=null && !String.valueOf(v).trim().isEmpty()) n++;
    Object en=s.getRaw().get("enabled");
    if(!(en instanceof Boolean) || (Boolean)en) n+=5;
    Object t=s.getRaw().get("lastUpdateTime");
    if(t instanceof Number) n += (int)Math.min(50, ((Number)t).longValue() / 1_000_000_000L);
    else if(t!=null){ try{ n += (int)Math.min(50, Long.parseLong(String.valueOf(t)) / 1_000_000_000L);}catch(Exception ignored){} }
    // 规则完整性：有完整规则的书源优先保留
    for(String rk: new String[]{"ruleSearch","ruleBookInfo","ruleToc","ruleContent","ruleExplore"}){
      Object rv=s.getRaw().get(rk);
      if(rv instanceof Map && !((Map<?,?>)rv).isEmpty()) n+=8;
      else if(rv!=null && !String.valueOf(rv).trim().isEmpty()) n+=4;
    }
    // 名称简洁性加分：名称越短越清晰（通常更好）
    String name = s.getName();
    if (name != null && !name.trim().isEmpty()) {
      int len = name.trim().length();
      if (len <= 6) n += 5;      // 极简名称
      else if (len <= 10) n += 3; // 简洁名称
    }
    return n;
  }
  private static String reason(DedupeMode m){
    if(m==DedupeMode.AGGRESSIVE) return "激进模式下域名相同";
    if(m==DedupeMode.STANDARD) return "标准化书源 URL 相同（已剥离跟踪参数）";
    if(m==DedupeMode.NAME) return "名称相似度去重：名称相同或高度相似";
    return "规范化书源 URL 相同";
  }
}
