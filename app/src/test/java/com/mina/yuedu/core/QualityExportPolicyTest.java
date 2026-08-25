package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.model.SourceRecord;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class QualityExportPolicyTest {
  @Test public void keepsBestTwoSourcesPerDomainAndExcludesLowScores() {
    SourceRecord a = source(0, "a", "https://www.a.com/1");
    SourceRecord b = source(1, "b", "https://a.com/2");
    SourceRecord c = source(2, "c", "https://a.com/3");
    SourceRecord d = source(3, "d", "https://b.com/1");
    SourceHealthTracker tracker = new SourceHealthTracker();
    for (SourceRecord source : Arrays.asList(a, b, c, d)) {
      tracker.recordCheck(source, new CheckSourceResult(source, CheckSourceResult.Status.SUCCESS,
          Collections.<String>emptyList(), "ok", 1000));
    }
    tracker.get(a.getUrl()).score = 95;
    tracker.get(b.getUrl()).score = 90;
    tracker.get(c.getUrl()).score = 80;
    tracker.get(d.getUrl()).score = 60;

    List<SourceRecord> selected = QualityExportPolicy.select(Arrays.asList(a, b, c, d), tracker, 70, 2);
    assertEquals(Arrays.asList(a, b), selected);
  }

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }
}
