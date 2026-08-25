package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.model.SourceRecord;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 书源健康追踪：记录每个书源的历次校验结果，计算质量评分，持久化到 SharedPreferences。
 * 纯逻辑，不依赖 Android，JSON 序列化通过 MiniJson 处理。
 */
public final class SourceHealthTracker {

  /** 单次校验快照。 */
  public static final class Snapshot {
    public final long ts;
    public final boolean success;
    public final long responseTimeMs;
    public final String failReason;
    public final String timeText;

    public Snapshot(long ts, boolean success, long responseTimeMs, String failReason) {
      this.ts = ts;
      this.success = success;
      this.responseTimeMs = responseTimeMs;
      this.failReason = failReason == null ? "" : failReason;
      this.timeText = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
    }
  }

  /** 单个书源的健康档案。 */
  public static final class HealthRecord {
    public final String url;
    public String name;
    public final List<Snapshot> history;
    public int totalChecks;
    public int successCount;
    public long totalResponseMs;
    public long lastCheckTime;
    public double score; // 0-100

    public HealthRecord(String url, String name) {
      this.url = url;
      this.name = name == null ? "" : name;
      this.history = new ArrayList<>();
    }

    public void addSnapshot(Snapshot s) {
      history.add(0, s);
      if (history.size() > 20) history.remove(history.size() - 1);
      totalChecks++;
      if (s.success) successCount++;
      totalResponseMs += s.responseTimeMs;
      lastCheckTime = s.ts;
      recomputeScore();
    }

    public long avgResponseMs() {
      return totalChecks > 0 ? totalResponseMs / totalChecks : 0;
    }

    public double passRate() {
      return totalChecks > 0 ? (double) successCount / totalChecks : 0;
    }

    /** 建议：保留（retain）/ 观察（watch）/ 删除（remove）。 */
    public String suggestion() {
      if (totalChecks == 0) return "未校验";
      if (score >= 70) return "建议保留";
      if (score >= 40) return "建议观察";
      if (totalChecks >= 3 && successCount == 0) return "建议删除";
      return "建议观察";
    }

    /** 质量评分 0-100。 */
    private void recomputeScore() {
      double s = 50;
      // 最近一次校验
      Snapshot last = history.isEmpty() ? null : history.get(0);
      if (last != null) s += last.success ? 20 : -20;
      // 最近 3 次连续成功/失败
      if (history.size() >= 3) {
        boolean allOk = true, allFail = true;
        for (int i = 0; i < 3; i++) {
          if (history.get(i).success) allFail = false;
          else allOk = false;
        }
        if (allOk) s += 10;
        if (allFail) s -= 10;
      }
      // 响应速度
      long avg = avgResponseMs();
      if (avg > 0 && avg < 1000) s += 10;
      else if (avg >= 1000 && avg < 3000) s += 5;
      else if (avg > 20000) s -= 10;
      else if (avg >= 3000 && avg <= 10000) { /* 中等速度，不调整 */ }
      else if (avg > 10000) s -= 5;
      // 通过率
      double pr = passRate();
      if (pr >= 0.8) s += 10;
      else if (pr <= 0.3 && totalChecks >= 3) s -= 10;
      // 历史稳定性：有多次校验记录的加分
      if (totalChecks >= 5) s += 5;

      score = Math.max(0, Math.min(100, s));
    }
  }

  private final Map<String, HealthRecord> records = new LinkedHashMap<>();

  /** 记录单次校验结果。 */
  public void recordCheck(SourceRecord source, CheckSourceResult result) {
    if (source == null || source.getUrl() == null) return;
    HealthRecord r = records.get(source.getUrl());
    if (r == null) {
      r = new HealthRecord(source.getUrl(), source.getName());
      records.put(source.getUrl(), r);
    }
    r.name = source.getName();
    r.addSnapshot(new Snapshot(
        System.currentTimeMillis(),
        result.isUsable(),
        result.respondTimeMs,
        result.message));
  }

  /** 批量记录校验结果。 */
  public void recordBatch(List<SourceRecord> sources, List<CheckSourceResult> results) {
    if (sources == null || results == null) return;
    Map<String, CheckSourceResult> resultMap = new LinkedHashMap<>();
    for (CheckSourceResult r : results) {
      if (r.source.getUrl() != null) resultMap.put(r.source.getUrl(), r);
    }
    for (SourceRecord s : sources) {
      if (s.getUrl() == null) continue;
      CheckSourceResult r = resultMap.get(s.getUrl());
      if (r != null) recordCheck(s, r);
    }
  }

  /** 获取某个源的健康记录。 */
  public HealthRecord get(String url) { return records.get(url); }

  /** 获取所有健康记录，按评分降序。 */
  public List<HealthRecord> getAll() {
    List<HealthRecord> list = new ArrayList<>(records.values());
    Collections.sort(list, (a, b) -> Double.compare(b.score, a.score));
    return list;
  }

  /** 获取建议删除的源。 */
  public List<HealthRecord> getSuggestedRemovals() {
    List<HealthRecord> out = new ArrayList<>();
    for (HealthRecord r : records.values()) {
      if ("建议删除".equals(r.suggestion())) out.add(r);
    }
    return out;
  }

  /** 导出为可序列化的 Map 列表（用于持久化）。 */
  public List<Object> toJsonList() {
    List<Object> list = new ArrayList<>();
    for (HealthRecord r : records.values()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("url", r.url);
      m.put("name", r.name);
      m.put("total", r.totalChecks);
      m.put("success", r.successCount);
      m.put("totalMs", r.totalResponseMs);
      m.put("lastCheck", r.lastCheckTime);
      List<Object> snaps = new ArrayList<>();
      for (Snapshot s : r.history) {
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("ts", s.ts);
        sm.put("ok", s.success);
        sm.put("ms", s.responseTimeMs);
        sm.put("reason", s.failReason);
        snaps.add(sm);
      }
      m.put("history", snaps);
      list.add(m);
    }
    return list;
  }

  /** 从持久化数据恢复。 */
  @SuppressWarnings("unchecked")
  public void fromJsonList(List<Object> jsonList) {
    records.clear();
    if (jsonList == null) return;
    for (Object o : jsonList) {
      if (!(o instanceof Map)) continue;
      Map<String, Object> m = (Map<String, Object>) o;
      String url = str(m.get("url"));
      if (url == null || url.isEmpty()) continue;
      HealthRecord r = new HealthRecord(url, str(m.get("name")));
      r.totalChecks = intOf(m.get("total"));
      r.successCount = intOf(m.get("success"));
      r.totalResponseMs = longOf(m.get("totalMs"));
      r.lastCheckTime = longOf(m.get("lastCheck"));
      Object h = m.get("history");
      if (h instanceof List) {
        for (Object so : (List<Object>) h) {
          if (!(so instanceof Map)) continue;
          Map<String, Object> sm = (Map<String, Object>) so;
          r.history.add(new Snapshot(
              longOf(sm.get("ts")),
              boolOf(sm.get("ok")),
              longOf(sm.get("ms")),
              str(sm.get("reason"))));
        }
      }
      r.recomputeScore();
      records.put(url, r);
    }
  }

  private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
  private static int intOf(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
  private static long longOf(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
  private static boolean boolOf(Object o) { return o instanceof Boolean && (Boolean) o; }
}
