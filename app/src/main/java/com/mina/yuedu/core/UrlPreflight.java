package com.mina.yuedu.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 解析前的 URL 预检：统计数量、找出重复、识别明显无效的地址。
 *
 * 从 MainActivity.startParse 抽出来的原因：用户在点「解析网络源」之前完全不知道
 * 自己粘贴了什么——50 行里可能有 3 个重复、2 个空行、1 个不是 http 的地址。
 * 原来的行为是直接开跑，用户只能从失败数里猜到出了问题。
 */
public final class UrlPreflight {

  /** 预检结果。 */
  public static final class Report {
    /** 去重后的有效 URL（按首次出现顺序）。 */
    public final List<String> unique;
    /** 重复出现的 URL → 重复次数（不含首次）。 */
    public final Map<String, Integer> duplicates;
    /** 不是 http(s) 开头的行。 */
    public final List<String> nonHttp;
    /** 原始行数（含空行已剔除后的非空行数）。 */
    public final int totalLines;

    Report(List<String> unique, Map<String, Integer> duplicates,
           List<String> nonHttp, int totalLines) {
      this.unique = unique;
      this.duplicates = duplicates;
      this.nonHttp = nonHttp;
      this.totalLines = totalLines;
    }

    /** 是否有任何值得提示的问题。 */
    public boolean hasIssues() {
      return !duplicates.isEmpty() || !nonHttp.isEmpty();
    }

    /** 可解析的地址数量。 */
    public int usableCount() {
      return unique.size();
    }
  }

  private UrlPreflight() {}

  /** 分析输入框内容。 */
  public static Report analyze(String text) {
    List<String> lines = UrlInputParser.parse(text);
    LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
    List<String> nonHttp = new ArrayList<>();
    for (String line : lines) {
      String lower = line.toLowerCase(java.util.Locale.ROOT);
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
        nonHttp.add(line);
        continue;
      }
      seen.merge(line, 1, Integer::sum);
    }
    List<String> unique = new ArrayList<>();
    Map<String, Integer> duplicates = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> e : seen.entrySet()) {
      unique.add(e.getKey());
      if (e.getValue() > 1) duplicates.put(e.getKey(), e.getValue() - 1);
    }
    return new Report(unique, duplicates, nonHttp, lines.size());
  }

  /** 生成给用户看的一句话摘要；没有问题时可返回 null（调用方据此跳过提示）。 */
  public static String summary(Report r) {
    if (r == null || !r.hasIssues()) return null;
    StringBuilder sb = new StringBuilder();
    sb.append("检测到 ").append(r.totalLines).append(" 行，可解析 ").append(r.usableCount()).append(" 个地址");
    int dupCount = 0;
    for (int n : r.duplicates.values()) dupCount += n;
    if (dupCount > 0) sb.append("，其中 ").append(dupCount).append(" 个重复（已自动去重）");
    if (!r.nonHttp.isEmpty()) sb.append("，").append(r.nonHttp.size()).append(" 行不是 http 地址（将忽略）");
    return sb.toString();
  }
}