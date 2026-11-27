package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * SourceBuckets 移除测试。
 *
 * 核心回归：移除后的源必须真的从数据源头消失，
 * 否则 recompute（每次都从 buckets.all() 重算）会让被删的源复活。
 *
 * 这是真机上暴露的缺陷：用户移除 91 条站点已死的源后，
 * 只要切换一下去重模式或重新解析，那 91 条又全回来了。
 */
public class SourceBucketsTest {

  private static SourceRecord src(String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(0, name, url, raw);
  }

  @Test public void removesFromLocal() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Arrays.asList(src("A", "https://a.com"), src("B", "https://b.com")));
    int removed = b.removeByUrls(new HashSet<>(Collections.singletonList("https://a.com")));
    assertEquals(1, removed);
    assertEquals(1, b.localCount());
    assertEquals("B", b.all().get(0).getName());
  }

  @Test public void removesFromNetwork() {
    SourceBuckets b = new SourceBuckets();
    b.addNetwork(Arrays.asList(src("A", "https://a.com"), src("B", "https://b.com")));
    int removed = b.removeByUrls(new HashSet<>(Collections.singletonList("https://b.com")));
    assertEquals(1, removed);
    assertEquals(1, b.networkCount());
    assertEquals("A", b.all().get(0).getName());
  }

  /** 两批里都有同一个 URL 时都要删掉。 */
  @Test public void removesFromBothBuckets() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Collections.singletonList(src("A-local", "https://a.com")));
    b.addNetwork(Collections.singletonList(src("A-net", "https://a.com")));
    int removed = b.removeByUrls(new HashSet<>(Collections.singletonList("https://a.com")));
    assertEquals("两批都应删除", 2, removed);
    assertTrue("应清空", b.isEmpty());
  }

  /** 不存在的 URL 不报错，返回 0。 */
  @Test public void removingAbsentUrlIsNoop() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Collections.singletonList(src("A", "https://a.com")));
    assertEquals(0, b.removeByUrls(
        new HashSet<>(Collections.singletonList("https://nonexistent.com"))));
    assertEquals(1, b.localCount());
  }

  @Test public void nullAndEmptySafe() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Collections.singletonList(src("A", "https://a.com")));
    assertEquals(0, b.removeByUrls(null));
    assertEquals(0, b.removeByUrls(Collections.<String>emptySet()));
    assertEquals(1, b.localCount());
  }

  /** url 为 null 的源不应被误删。 */
  @Test public void nullUrlSourceSurvives() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Arrays.asList(src("无URL", null), src("A", "https://a.com")));
    b.removeByUrls(new HashSet<>(Collections.singletonList("https://a.com")));
    assertEquals("无 URL 的源不该被删", 1, b.localCount());
    assertEquals("无URL", b.all().get(0).getName());
  }

  /** 核心回归：移除后 all() 不再包含它，recompute 用的就是这个。 */
  @Test public void removalAffectsAllForRecompute() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Arrays.asList(
        src("A", "https://a.com"),
        src("B", "https://b.com"),
        src("C", "https://c.com")));

    List<SourceRecord> before = b.all();
    assertEquals(3, before.size());

    Set<String> dead = new HashSet<>(Arrays.asList("https://a.com", "https://c.com"));
    b.removeByUrls(dead);

    List<SourceRecord> after = b.all();
    assertEquals("重新去重时只应看到剩下的", 1, after.size());
    assertEquals("B", after.get(0).getName());
  }

  /** 清除仍然工作。 */
  @Test public void clearAllStillWorks() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Collections.singletonList(src("A", "https://a.com")));
    b.addNetwork(Collections.singletonList(src("B", "https://b.com")));
    b.clearAll();
    assertTrue(b.isEmpty());
    assertEquals(0, b.all().size());
  }

  /** 快照不被内部移除影响（跨配置变更保留数据的路径）。 */
  @Test public void snapshotIndependentOfRemoval() {
    SourceBuckets b = new SourceBuckets();
    b.addLocal(Collections.singletonList(src("A", "https://a.com")));
    List<SourceRecord> snap = b.localSnapshot();
    b.removeByUrls(new HashSet<>(Collections.singletonList("https://a.com")));
    assertEquals("快照是独立列表", 1, snap.size());
    assertEquals(0, b.localCount());
  }
}