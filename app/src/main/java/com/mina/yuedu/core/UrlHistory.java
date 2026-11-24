package com.mina.yuedu.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近使用 URL 历史（最新在前、去重、上限 10 条）。
 *
 * 从 MainActivity 抽出来的原因：这段有明确的规则（去重 / 置顶 / 截断 / 换行序列化），
 * 但夹在读写 SharedPreferences 的代码里没法单独验证。现在纯函数化，规则由测试守住。
 */
public final class UrlHistory {

  /** 保留的最大条数。 */
  public static final int MAX = 10;

  private UrlHistory() {}

  /** 把持久化的多行文本解析成列表（去掉空行与首尾空白）。 */
  public static List<String> parse(String raw) {
    List<String> out = new ArrayList<>();
    if (raw == null || raw.isEmpty()) return out;
    for (String line : raw.split("\n")) {
      String t = line.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  /**
   * 把一批新 URL 合进历史：每条都提到最前（已存在则先移除再去重），结果截断到 {@link #MAX}。
   *
   * @param current 现有历史，可为 null
   * @param added   新加入的 URL，可为 null
   */
  public static List<String> merge(List<String> current, List<String> added) {
    List<String> hist = new ArrayList<>(current == null ? new ArrayList<String>() : current);
    if (added != null) {
      for (String u : added) {
        if (u == null) continue;
        hist.remove(u);
        hist.add(0, u);
      }
    }
    while (hist.size() > MAX) hist.remove(hist.size() - 1);
    return hist;
  }

  /** 序列化成按行存储的文本。 */
  public static String format(List<String> list) {
    StringBuilder sb = new StringBuilder();
    if (list != null) {
      for (String s : list) {
        if (s == null) continue;
        if (sb.length() > 0) sb.append('\n');
        sb.append(s);
      }
    }
    return sb.toString();
  }
}