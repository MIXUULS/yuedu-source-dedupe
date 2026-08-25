package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.model.SourceRecord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceHealthTrackerTest {
  @Test public void verySlowSourceReceivesStrongerSpeedPenalty() {
    SourceRecord source = source("https://example.com");
    SourceHealthTracker tracker = new SourceHealthTracker();
    tracker.recordCheck(source, new CheckSourceResult(source, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 25000));

    assertEquals(70.0, tracker.get(source.getUrl()).score, 0.001);
  }

  private static SourceRecord source(String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "测试源");
    raw.put("bookSourceUrl", url);
    return new SourceRecord(0, "测试源", url, raw);
  }
}
