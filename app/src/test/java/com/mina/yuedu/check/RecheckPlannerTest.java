package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** 重验范围规划（单个 / 批量 / 边界）单元测试。 */
public class RecheckPlannerTest {

  private static SourceRecord source(String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(0, name, url, raw);
  }

  private static List<SourceRecord> retained() {
    return new ArrayList<>(Arrays.asList(
        source("A", "https://a.com"),
        source("B", "https://b.com"),
        source("C", "https://c.com")));
  }

  @Test public void plansOnlyMatchingUrls() {
    List<SourceRecord> out = RecheckPlanner.plan(retained(),
        new HashSet<>(Arrays.asList("https://a.com", "https://c.com")));
    assertEquals(2, out.size());
    assertEquals("A", out.get(0).getName());
    assertEquals("C", out.get(1).getName());
  }

  /** 单条重验。 */
  @Test public void planSinglePicksOne() {
    List<SourceRecord> out = RecheckPlanner.planSingle(retained(), "https://b.com");
    assertEquals(1, out.size());
    assertEquals("B", out.get(0).getName());
  }

  /** 单条 URL 首尾空白要能处理（用户从列表复制可能带空格）。 */
  @Test public void planSingleTrimsWhitespace() {
    assertEquals(1, RecheckPlanner.planSingle(retained(), "  https://a.com  ").size());
  }

  /** 不在 retained 里的 URL 返回空（调用方据此提示"已不在结果中"）。 */
  @Test public void unknownUrlYieldsEmpty() {
    assertTrue(RecheckPlanner.plan(retained(),
        new HashSet<>(Collections.singletonList("https://gone.com"))).isEmpty());
    assertTrue(RecheckPlanner.planSingle(retained(), "https://gone.com").isEmpty());
  }

  /** 空输入安全。 */
  @Test public void nullAndEmptyAreSafe() {
    assertTrue(RecheckPlanner.plan(null, new HashSet<>(Arrays.asList("https://a.com"))).isEmpty());
    assertTrue(RecheckPlanner.plan(retained(), null).isEmpty());
    assertTrue(RecheckPlanner.plan(retained(), Collections.<String>emptySet()).isEmpty());
    assertTrue(RecheckPlanner.planSingle(retained(), null).isEmpty());
    assertTrue(RecheckPlanner.planSingle(retained(), "").isEmpty());
    assertTrue(RecheckPlanner.planSingle(null, "https://a.com").isEmpty());
  }

  /** URL 为 null 的书源不参与重验。 */
  @Test public void nullUrlSourcesSkipped() {
    List<SourceRecord> list = new ArrayList<>();
    list.add(new SourceRecord(0, "无地址", null, new LinkedHashMap<String, Object>()));
    assertTrue(RecheckPlanner.plan(list, new HashSet<>(Collections.singletonList("https://a.com"))).isEmpty());
  }

  /** 同一 URL 出现多次时只重验一次（避免重复消耗校验配额）。 */
  @Test public void deduplicatesSameUrl() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "dup");
    raw.put("bookSourceUrl", "https://a.com");
    List<SourceRecord> list = Arrays.asList(
        new SourceRecord(0, "dup", "https://a.com", raw),
        new SourceRecord(1, "dup", "https://a.com", raw));
    assertEquals(1, RecheckPlanner.plan(list,
        new HashSet<>(Collections.singletonList("https://a.com"))).size());
  }

  /** 返回顺序跟随 retained 顺序，不受传入集合顺序影响。 */
  @Test public void preservesRetainedOrder() {
    Set<String> urls = new HashSet<>(Arrays.asList("https://c.com", "https://a.com"));
    List<SourceRecord> out = RecheckPlanner.plan(retained(), urls);
    assertEquals("顺序应跟随 retained", "A", out.get(0).getName());
    assertEquals("C", out.get(1).getName());
  }
}