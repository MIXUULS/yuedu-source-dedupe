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
      // v2 老格式先把字段补成 v3（1028/1399 条真实书源是老格式，
      // 不补的话下游校验与规则完整度判断都会误判）
      SourceRecord adapted = V2FieldAdapter.adapt(s);
      // URL 为空时先从 searchUrl / 规则 baseUrl 里恢复（Issue #1）：
      // 直接丢弃会把还能用的书源当废品，用户看到的是"导入 N 条但只剩 M 条"。
      // 这一步只做"补救"，不判死——恢复不出来才老实报 URL 缺失。
      String u = UrlRecovery.recover(adapted);
      if(u==null){
        String rawUrl = adapted.getUrl();
        if(rawUrl==null) invalid.add(new InvalidSource(InvalidSource.Kind.MISSING_URL, adapted.getName()));
        else invalid.add(new InvalidSource(InvalidSource.Kind.EMPTY_URL, adapted.getName()));
        continue;
      }
      try{
        String k=UrlNormalizer.key(u, mode);
        // 恢复出的 URL 与原值不同时，把恢复值写进记录，否则导出后仍然是空地址
        SourceRecord base = u.equals(adapted.getUrl()) ? adapted : withRecoveredUrl(adapted, u);
        SourceRecord v=clean? base.withName(NameCleaner.clean(base.getName())) : base;
        buckets.computeIfAbsent(k, x->new ArrayList<>()).add(v);
      }catch(Exception e){ invalid.add(new InvalidSource(InvalidSource.Kind.INVALID_URL, u)); }
    }
    return buildResult(sources.size(), buckets, mode, clean, invalid);
  }

  /** 把恢复出的 URL 写进记录（同时更新 raw 里的 bookSourceUrl，保证导出正确）。 */
  private static SourceRecord withRecoveredUrl(SourceRecord s, String url){
    Map<String,Object> raw = new LinkedHashMap<>(s.getRaw());
    raw.put("bookSourceUrl", url);
    return new SourceRecord(s.getOrder(), s.getName(), url, raw);
  }

  /** 按名称相似度去重。 */
  private static DedupeResult runByName(List<SourceRecord> sources, boolean clean,
                                        List<InvalidSource> invalid) {
    List<SourceRecord> processed = new ArrayList<>(sources.size());
    for (SourceRecord s : sources) {
      // 与 run() 保持一致：先适配 v2 格式，再尝试恢复 URL
      SourceRecord adapted = V2FieldAdapter.adapt(s);
      String recovered = UrlRecovery.recover(adapted);
      if (recovered == null) {
        if (adapted.getUrl() == null) {
          invalid.add(new InvalidSource(InvalidSource.Kind.MISSING_URL, adapted.getName()));
        } else {
          invalid.add(new InvalidSource(InvalidSource.Kind.EMPTY_URL, adapted.getName()));
        }
        continue;
      }
      SourceRecord base = recovered.equals(adapted.getUrl()) ? adapted : withRecoveredUrl(adapted, recovered);
      processed.add(clean ? base.withName(NameCleaner.clean(base.getName())) : base);
    }
    // 按名称分组
    List<List<SourceRecord>> nameGroups = NameSimilarity.groupByName(processed);
    // 将分组结果转换为 buckets 格式（key = 归一化名称）
    Map<String, List<SourceRecord>> buckets = new LinkedHashMap<>();
    Set<SourceRecord> grouped = new HashSet<>();
    for (List<SourceRecord> group : nameGroups) {
      String key = NameSimilarity.normalize(group.get(0).getName());
      key = key.isEmpty() ? group.get(0).getName() : key;
      // 不能直接 put：不同分组可能归一化出同一个 key（如 "A站"/"a 站"），
      // 直接覆盖会让前一组的书源既不进 retained 也不进 duplicate，凭空消失。
      buckets.computeIfAbsent(key, x -> new ArrayList<>()).addAll(group);
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

  /** 从 buckets 构建最终结果（invalid 为本次运行收集的无效源，必须传入，否则无效源会凭空消失）。 */
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
    if(t instanceof Number) n += recencyBonus(((Number)t).longValue());
    else if(t!=null){ try{ n += recencyBonus(Long.parseLong(String.valueOf(t)));}catch(Exception ignored){} }
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
  /** 新近度加分：最近更新 50 分起，每满 30 天衰减 1 分，下限 0。lastUpdateTime 是毫秒时间戳，
   *  旧公式除以 1e9 对任何真实时间戳都恒超过上限，等于只有"有无时间戳"之分。 */
  static int recencyBonus(long epochMs){
    long days = (System.currentTimeMillis() - epochMs) / 86_400_000L;
    if(days < 0) days = 0;
    return 50 - (int)Math.min(50, days / 30);
  }
  private static String reason(DedupeMode m){
    if(m==DedupeMode.AGGRESSIVE) return "激进模式下域名相同";
    if(m==DedupeMode.STANDARD) return "标准化书源 URL 相同（已剥离跟踪参数）";
    if(m==DedupeMode.NAME) return "名称相似度去重：名称相同或高度相似";
    return "规范化书源 URL 相同";
  }
}
