package com.mina.yuedu.check;

import com.mina.yuedu.core.MiniJson;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 校验历史的记录、截断与持久化编解码。
 *
 * 从 MainActivity 抽出来的原因：这段维护着"统计数字 + 失败源清单 + 时间戳"三者的对应关系，
 * 还要做上限截断和 JSON 往返，出错时表现为历史条目错位或数字对不上，很难靠肉眼复现。
 * 纯逻辑（时间格式串由常量固定），可直接单测。
 */
public final class CheckHistoryStore {

  /** 最多保留的历史条数。 */
  public static final int MAX = 20;

  private static final String TIME_PATTERN = "MM-dd HH:mm";

  /** 一条校验历史。 */
  public static final class Entry {
    public final long ts;
    public final String timeText;
    public final int total, ok, fail, timeout;
    /** 失败/超时的书源 URL，供增量重验。 */
    public final List<String> badUrls;

    public Entry(long ts, int total, int ok, int fail, int timeout, List<String> badUrls) {
      this.ts = ts;
      this.timeText = formatTime(ts);
      this.total = total;
      this.ok = ok;
      this.fail = fail;
      this.timeout = timeout;
      this.badUrls = badUrls == null ? new ArrayList<String>() : new ArrayList<>(badUrls);
    }

    static String formatTime(long ts) {
      return new SimpleDateFormat(TIME_PATTERN, Locale.CHINA).format(new Date(ts));
    }
  }

  private CheckHistoryStore() {}

  /**
   * 把一次校验结果汇总成历史条目。
   *
   * @param ts 记录时间（毫秒），由调用方注入以便测试
   * @return 结果为空时返回 null（不记录空批次）
   */
  public static Entry summarize(List<CheckSourceResult> results, long ts) {
    if (results == null || results.isEmpty()) return null;
    int ok = 0, fail = 0, timeout = 0;
    List<String> bad = new ArrayList<>();
    for (CheckSourceResult r : results) {
      if (r == null) continue;
      if (r.status == CheckSourceResult.Status.SUCCESS) {
        ok++;
      } else if (r.status == CheckSourceResult.Status.TIMEOUT) {
        timeout++;
        if (r.source != null && r.source.getUrl() != null) bad.add(r.source.getUrl());
      } else if (r.status == CheckSourceResult.Status.FAILED) {
        fail++;
        if (r.source != null && r.source.getUrl() != null) bad.add(r.source.getUrl());
      }
    }
    // total 用传入列表长度：包含被跳过（SKIPPED）的项，与界面"共 N 条"一致
    return new Entry(ts, results.size(), ok, fail, timeout, bad);
  }

  /** 把新条目插到最前并截断到 {@link #MAX} 条。 */
  public static List<Entry> prepend(List<Entry> current, Entry entry) {
    List<Entry> out = new ArrayList<>(current == null ? new ArrayList<Entry>() : current);
    if (entry != null) out.add(0, entry);
    while (out.size() > MAX) out.remove(out.size() - 1);
    return out;
  }

  /** 序列化为 JSON。 */
  public static String toJson(List<Entry> entries) {
    List<Object> list = new ArrayList<>();
    if (entries != null) {
      for (Entry e : entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", e.ts);
        m.put("total", e.total);
        m.put("ok", e.ok);
        m.put("fail", e.fail);
        m.put("timeout", e.timeout);
        m.put("bad", e.badUrls);
        list.add(m);
      }
    }
    return MiniJson.stringify(list);
  }

  /** 反序列化；内容损坏时返回已解析出的部分（不抛异常，历史属于辅助信息）。 */
  public static List<Entry> fromJson(String raw) {
    List<Entry> out = new ArrayList<>();
    if (raw == null || raw.isEmpty()) return out;
    Object root;
    try {
      root = MiniJson.parse(raw);
    } catch (Exception e) {
      return out;
    }
    if (!(root instanceof List)) return out;
    for (Object o : (List<?>) root) {
      if (!(o instanceof Map)) continue;
      Map<?, ?> m = (Map<?, ?>) o;
      long ts = m.get("ts") instanceof Number ? ((Number) m.get("ts")).longValue() : 0;
      int total = intOf(m.get("total")), ok = intOf(m.get("ok"));
      int fail = intOf(m.get("fail")), timeout = intOf(m.get("timeout"));
      List<String> bad = new ArrayList<>();
      Object b = m.get("bad");
      if (b instanceof List) {
        for (Object x : (List<?>) b) if (x != null) bad.add(String.valueOf(x));
      }
      out.add(new Entry(ts, total, ok, fail, timeout, bad));
    }
    while (out.size() > MAX) out.remove(out.size() - 1);
    return out;
  }

  private static int intOf(Object o) {
    if (o instanceof Number) return ((Number) o).intValue();
    if (o == null) return 0;
    try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
  }
}