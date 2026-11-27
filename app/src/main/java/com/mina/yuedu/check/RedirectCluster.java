package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 重定向去重：找出「原来不同域名、但重定向后指向同一站点」的书源。
 *
 * 用户反馈（Issue #1 三.2）：很多书源的 URL 重定向后其实是同一个网站。
 * 例如 aaa.com 和 bbb.com 都 301 到 ccc.com，去重时按原 URL 看是两个源，
 * 实际是同一站的镜像——这类冗余只有发请求看最终落地地址才能发现。
 *
 * 为什么单独成类而不是塞进 DedupeEngine：
 * <pre>
 *   DedupeEngine 是纯计算，毫秒级；重定向检测要发真实网络请求，万级书源
 *   即使 32 并发也要几分钟。混在一起会让「切个去重模式」变成「等几分钟」。
 * </pre>
 *
 * 因此这里的定位是：
 * <ul>
 *   <li>只在用户主动触发时运行（可选功能，默认关）</li>
 *   <li>结果作为「建议」呈现，由用户决定是否合并，不自动改结果</li>
 *   <li>只比较最终主机名，不做内容比对（避免误判）</li>
 * </ul>
 */
public final class RedirectCluster {

  /** 一个「同落地站」的分组。 */
  public static final class Group {
    /** 重定向后的最终主机（归一化后）。 */
    public final String finalHost;
    /** 落到同一最终主机的原始书源。必定 >= 2 条，否则不会成组。 */
    public final List<SourceRecord> members;
    /** 原始主机 → 最终主机，便于向用户解释。 */
    public final Map<String, String> hostMapping;

    Group(String finalHost, List<SourceRecord> members, Map<String, String> hostMapping) {
      this.finalHost = finalHost;
      this.members = members;
      this.hostMapping = hostMapping;
    }

    public int size() { return members.size(); }

    /** 是否有多个不同的原始主机（只有一个主机的不算重定向去重收益）。 */
    public boolean hasMultipleOrigins() {
      return new LinkedHashSet<>(hostMapping.keySet()).size() > 1;
    }
  }

  private RedirectCluster() {}

  /**
   * 把探测结果聚成组。
   *
   * @param sources     原始书源
   * @param resolvedUrl 书源 URL → 重定向后的最终 URL（探测失败的不放入或值为 null）
   * @return 只有真正成组（>=2 条且来自不同原始主机）的才返回
   */
  public static List<Group> cluster(List<SourceRecord> sources,
                                    Map<String, String> resolvedUrl) {
    List<Group> out = new ArrayList<>();
    if (sources == null || sources.isEmpty() || resolvedUrl == null || resolvedUrl.isEmpty()) {
      return out;
    }

    // 最终主机 → 书源列表
    Map<String, List<SourceRecord>> byFinal = new LinkedHashMap<>();
    Map<String, Map<String, String>> hostMaps = new LinkedHashMap<>();

    for (SourceRecord s : sources) {
      if (s == null || s.getUrl() == null) continue;
      String original = s.getUrl();
      String finalUrl = resolvedUrl.get(original);
      if (finalUrl == null || finalUrl.trim().isEmpty()) continue;

      String finalHost = hostOf(finalUrl);
      if (finalHost == null || finalHost.isEmpty()) continue;
      finalHost = normalizeHost(finalHost);

      String origHost = hostOf(original);
      origHost = origHost == null ? "" : normalizeHost(origHost);
      // 原始主机 == 最终主机（没发生跨域重定向）→ 对「重定向去重」没有意义
      if (origHost.equals(finalHost)) continue;

      byFinal.computeIfAbsent(finalHost, k -> new ArrayList<>()).add(s);
      hostMaps.computeIfAbsent(finalHost, k -> new LinkedHashMap<>())
          .put(origHost, finalHost);
    }

    for (Map.Entry<String, List<SourceRecord>> e : byFinal.entrySet()) {
      if (e.getValue().size() < 2) continue;
      Group g = new Group(e.getKey(), e.getValue(), hostMaps.get(e.getKey()));
      // 只保留跨主机的组：同一主机下的多条本来就是同一站，普通去重已覆盖
      if (g.hasMultipleOrigins()) out.add(g);
    }
    return out;
  }

  /** 提取主机名（小写，去掉端口）。 */
  static String hostOf(String url) {
    if (url == null) return null;
    String s = url.trim();
    if (s.isEmpty()) return null;
    // 去掉 #身份标签
    int hash = s.indexOf('#');
    if (hash >= 0) s = s.substring(0, hash);
    try {
      URI u = new URI(s);
      String h = u.getHost();
      if (h != null && !h.trim().isEmpty()) return h.trim();
      // 中文域名等 URI 拿不到 host 时用 authority 兜底
      String auth = u.getRawAuthority();
      if (auth != null && !auth.isEmpty()) {
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        int colon = auth.lastIndexOf(':');
        if (colon > 0 && auth.substring(colon + 1).matches("\\d{1,5}")) {
          auth = auth.substring(0, colon);
        }
        if (!auth.trim().isEmpty()) return auth.trim();
      }
    } catch (Exception ignored) {
      // URI 解析失败时手工截取
    }
    int schemeEnd = s.indexOf("://");
    if (schemeEnd < 0) return null;
    String rest = s.substring(schemeEnd + 3);
    int end = rest.length();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '/' || c == '?' || c == '#' || c == ':') { end = i; break; }
    }
    String h = rest.substring(0, end).trim();
    return h.isEmpty() ? null : h;
  }

  /**
   * 归一化主机：小写 + 去掉 www./m./wap./mobile. 前缀。
   *
   * 与严格模式的去重口径一致——同一站在 PC 版与手机版之间的跳转
   * 不应算成「跨站重定向」，否则会产生大量无意义的「合并建议」。
   */
  static String normalizeHost(String host) {
    if (host == null) return "";
    String h = host.trim().toLowerCase(Locale.ROOT);
    if (h.isEmpty()) return "";
    if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
    for (String prefix : new String[]{"www.", "m.", "wap.", "mobile."}) {
      if (!h.startsWith(prefix)) continue;
      String rest = h.substring(prefix.length());
      if (rest.indexOf('.') > 0 && rest.lastIndexOf('.') != rest.length() - 1) return rest;
    }
    return h;
  }

  /**
   * 生成给用户看的说明文本。
   */
  public static String describe(Group g) {
    if (g == null) return "";
    StringBuilder sb = new StringBuilder();
    sb.append("最终站点 ").append(g.finalHost).append("（").append(g.size()).append(" 条）\n");
    Set<String> origins = new LinkedHashSet<>(g.hostMapping.keySet());
    for (String o : origins) {
      sb.append("  · ").append(o.isEmpty() ? "(无主机)" : o);
      if (!o.equals(g.finalHost)) sb.append(" → ").append(g.finalHost);
      sb.append("\n");
    }
    return sb.toString();
  }
}