package com.mina.yuedu.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * URL 输入框文本 ↔ URL 列表的解析与合并。
 *
 * 从 MainActivity 抽出来的原因：多行输入、剪贴板粘贴、YCK 批量收集都走同一套规则
 * （按行切分、去首尾空白、跳过空行、保序去重），而历史实现里 YCK 批量收集因为没有
 * "一次合并再一次写入"导致 O(n²) 卡顿。规则集中在这里既能单测也能复用。
 */
public final class UrlInputParser {

  private UrlInputParser() {}

  /** 按行切分成 URL 列表，保留顺序。 */
  public static List<String> parse(String text) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) return out;
    for (String line : text.split("\\r?\\n")) {
      String t = line.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  /**
   * 把新 URL 合进现有列表：保序去重。
   *
   * @param existing 现有列表，可为 null
   * @param added    新加入的 URL，可为 null
   * @return 合并后的新列表（不修改入参）
   */
  public static List<String> merge(List<String> existing, List<String> added) {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    if (existing != null) {
      for (String s : existing) {
        if (s != null && !s.trim().isEmpty()) set.add(s.trim());
      }
    }
    if (added != null) {
      for (String s : added) {
        if (s != null && !s.trim().isEmpty()) set.add(s.trim());
      }
    }
    return new ArrayList<>(set);
  }

  /** 序列化成多行文本（输入框内容）。 */
  public static String format(List<String> urls) {
    if (urls == null || urls.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (String s : urls) {
      if (s == null || s.isEmpty()) continue;
      if (sb.length() > 0) sb.append('\n');
      sb.append(s);
    }
    return sb.toString();
  }

  /**
   * 从任意文本里提取可能是 JSON 书源地址的行。
   *
   * 剪贴板里常混着说明文字、空行、重复项，这里只保留 http(s) 开头的行。
   *
   * @return 去重后的 URL 列表，保持出现顺序
   */
  public static List<String> extractUrls(String text) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) return out;
    LinkedHashSet<String> set = new LinkedHashSet<>();
    for (String line : text.split("\\r?\\n")) {
      String t = line.trim();
      // 去掉常见的列表符号/引号包裹
      t = stripWrapping(t);
      String lower = t.toLowerCase(Locale.ROOT);
      if (lower.startsWith("http://") || lower.startsWith("https://")) set.add(t);
    }
    out.addAll(set);
    return out;
  }

  /** 去掉行首尾的列表符号、引号、逗号等包裹字符。 */
  private static String stripWrapping(String s) {
    String t = s;
    // 行首：-,*,+,•,数字., 引号
    int i = 0;
    while (i < t.length()) {
      char c = t.charAt(i);
      if (c == '-' || c == '*' || c == '+' || c == '•' || c == '"' || c == '\''
          || c == '[' || c == '(' || Character.isWhitespace(c)) {
        i++;
      } else break;
    }
    t = t.substring(i);
    // 行尾：引号、逗号、右括号
    int j = t.length();
    while (j > 0) {
      char c = t.charAt(j - 1);
      if (c == '"' || c == '\'' || c == ']' || c == ')' || c == ',' || c == ';'
          || Character.isWhitespace(c)) {
        j--;
      } else break;
    }
    return t.substring(0, j);
  }
}