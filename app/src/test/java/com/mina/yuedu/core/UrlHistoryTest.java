package com.mina.yuedu.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** URL 历史（去重 / 置顶 / 上限 10 / 按行序列化）单元测试。 */
public class UrlHistoryTest {

  @Test public void parseDropsBlankLinesAndTrims() {
    assertEquals(Arrays.asList("https://a.com", "https://b.com"),
        UrlHistory.parse("https://a.com\n   \n  https://b.com  \n"));
    assertTrue(UrlHistory.parse(null).isEmpty());
    assertTrue(UrlHistory.parse("").isEmpty());
  }

  @Test public void mergePutsNewestFirst() {
    List<String> out = UrlHistory.merge(
        Collections.singletonList("https://old.com"),
        Collections.singletonList("https://new.com"));
    assertEquals(Arrays.asList("https://new.com", "https://old.com"), out);
  }

  @Test public void mergeDeduplicatesAndMovesToFront() {
    List<String> current = Arrays.asList("https://a.com", "https://b.com", "https://c.com");
    List<String> out = UrlHistory.merge(current, Collections.singletonList("https://c.com"));
    assertEquals("已存在的要提到最前，不能重复", Arrays.asList("https://c.com", "https://a.com", "https://b.com"), out);
  }

  @Test public void mergeCapsAtTen() {
    List<String> current = new ArrayList<>();
    for (int i = 0; i < 12; i++) current.add("https://site" + i + ".com");
    List<String> out = UrlHistory.merge(current, Collections.singletonList("https://fresh.com"));
    assertEquals(UrlHistory.MAX, out.size());
    assertEquals("最新的在最前", "https://fresh.com", out.get(0));
    assertFalse("最旧的应被挤掉", out.contains("https://site10.com"));
  }

  @Test public void mergeHandlesNullAndEmpty() {
    assertEquals(Collections.emptyList(), UrlHistory.merge(null, null));
    assertEquals(Arrays.asList("https://a.com"), UrlHistory.merge(null, Collections.singletonList("https://a.com")));
    // 空的新增不改变现有历史
    assertEquals(Arrays.asList("https://a.com"), UrlHistory.merge(Collections.singletonList("https://a.com"), null));
  }

  @Test public void mergeIgnoresNullEntries() {
    List<String> out = UrlHistory.merge(Collections.singletonList("https://a.com"),
        Arrays.asList(null, "https://b.com", null));
    assertEquals(Arrays.asList("https://b.com", "https://a.com"), out);
  }

  @Test public void formatRoundTripsThroughParse() {
    List<String> list = Arrays.asList("https://a.com", "https://b.com");
    assertEquals(list, UrlHistory.parse(UrlHistory.format(list)));
    assertEquals("", UrlHistory.format(null));
    assertEquals("", UrlHistory.format(Collections.<String>emptyList()));
  }
}