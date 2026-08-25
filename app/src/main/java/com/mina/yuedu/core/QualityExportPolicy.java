package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按健康评分优选书源，并限制同域保留数量。 */
public final class QualityExportPolicy {
  private QualityExportPolicy() {}

  public static List<SourceRecord> select(List<SourceRecord> sources,
      SourceHealthTracker tracker, double minScore, int maxPerDomain) {
    if (sources == null || tracker == null || maxPerDomain < 1) return Collections.emptyList();
    Map<String, List<ScoredSource>> domains = new LinkedHashMap<>();
    for (SourceRecord source : sources) {
      if (source == null || source.getUrl() == null) continue;
      SourceHealthTracker.HealthRecord health = tracker.get(source.getUrl());
      if (health == null || health.score < minScore) continue;
      domains.computeIfAbsent(domain(source.getUrl()), k -> new ArrayList<>()).add(new ScoredSource(source, health));
    }
    List<SourceRecord> out = new ArrayList<>();
    Comparator<ScoredSource> order = (a, b) -> {
      int score = Double.compare(b.health.score, a.health.score);
      if (score != 0) return score;
      int speed = Long.compare(speedForOrder(a.health), speedForOrder(b.health));
      return speed != 0 ? speed : Integer.compare(a.source.getOrder(), b.source.getOrder());
    };
    for (List<ScoredSource> candidates : domains.values()) {
      candidates.sort(order);
      for (int i = 0; i < Math.min(maxPerDomain, candidates.size()); i++) out.add(candidates.get(i).source);
    }
    out.sort(Comparator.comparingInt(SourceRecord::getOrder));
    return out;
  }

  private static long speedForOrder(SourceHealthTracker.HealthRecord health) {
    long speed = health.avgResponseMs();
    return speed <= 0 ? Long.MAX_VALUE : speed;
  }

  private static String domain(String url) {
    try {
      String host = new URL(url).getHost().toLowerCase(java.util.Locale.ROOT);
      return host.startsWith("www.") ? host.substring(4) : host;
    } catch (Exception ignored) {
      return url;
    }
  }

  private static final class ScoredSource {
    final SourceRecord source;
    final SourceHealthTracker.HealthRecord health;
    ScoredSource(SourceRecord source, SourceHealthTracker.HealthRecord health) {
      this.source = source;
      this.health = health;
    }
  }
}
