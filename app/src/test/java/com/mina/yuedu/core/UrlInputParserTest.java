package com.mina.yuedu.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** URL 输入解析与合并（按行切分 / 保序去重 / 剪贴板提取 / 序列化）单元测试。 */
public class UrlInputParserTest {

  @Test public void parseSplitsLinesAndTrims() {
    assertEquals(Arrays.asList("https://a.com", "https://b.com"),
        UrlInputParser.parse("  https://a.com  \n\n  https://b.com\n"));
  }

  @Test public void parseHandlesWindowsLineEndings() {
    assertEquals(Arrays.asList("https://a.com", "https://b.com"),
        UrlInputParser.parse("https://a.com\r\nhttps://b.com\r\n"));
  }

  @Test public void parseNullAndEmpty() {
    assertTrue(UrlInputParser.parse(null).isEmpty());
    assertTrue(UrlInputParser.parse("").isEmpty());
    assertTrue(UrlInputParser.parse("  \n \n ").isEmpty());
  }

  @Test public void mergeDeduplicatesPreservingOrder() {
    List<String> out = UrlInputParser.merge(
        Arrays.asList("https://a.com", "https://b.com"),
        Arrays.asList("https://b.com", "https://c.com"));
    assertEquals(Arrays.asList("https://a.com", "https://b.com", "https://c.com"), out);
  }

  @Test public void mergeDoesNotMutateInputs() {
    List<String> existing = new ArrayList<>(Arrays.asList("https://a.com"));
    List<String> added = new ArrayList<>(Arrays.asList("https://b.com"));
    UrlInputParser.merge(existing, added);
    assertEquals(1, existing.size());
    assertEquals(1, added.size());
  }

  @Test public void mergeHandlesNulls() {
    assertEquals(Collections.emptyList(), UrlInputParser.merge(null, null));
    assertEquals(Arrays.asList("https://a.com"),
        UrlInputParser.merge(null, Collections.singletonList("https://a.com")));
    assertEquals(Arrays.asList("https://a.com"),
        UrlInputParser.merge(Collections.singletonList("https://a.com"), null));
  }

  @Test public void mergeSkipsBlanks() {
    assertEquals(Arrays.asList("https://a.com"),
        UrlInputParser.merge(null, Arrays.asList("", "  ", "https://a.com", null)));
  }

  @Test public void formatRoundTrips() {
    List<String> urls = Arrays.asList("https://a.com", "https://b.com");
    assertEquals(urls, UrlInputParser.parse(UrlInputParser.format(urls)));
    assertEquals("", UrlInputParser.format(null));
    assertEquals("", UrlInputParser.format(Collections.<String>emptyList()));
  }

  /** 剪贴板文本里混着说明和空行时，只提取真正的 URL。 */
  @Test public void extractUrlsFromMessyClipboard() {
    String clipboard = "这是我收藏的书源：\n"
        + "https://a.com/json\n"
        + "\n"
        + "  https://b.com/json  \n"
        + "说明文字不是地址\n";
    assertEquals(Arrays.asList("https://a.com/json", "https://b.com/json"),
        UrlInputParser.extractUrls(clipboard));
  }

  /** 列表符号/引号包裹要能剥掉。 */
  @Test public void extractUrlsStripsListMarkers() {
    String text = "- https://a.com\n* https://b.com\n\"https://c.com\"\n[ https://d.com ]";
    assertEquals(Arrays.asList("https://a.com", "https://b.com", "https://c.com", "https://d.com"),
        UrlInputParser.extractUrls(text));
  }

  /** 重复地址只保留一次，保持首次出现的顺序。 */
  @Test public void extractUrlsDeduplicates() {
    String text = "https://a.com\nhttps://b.com\nhttps://a.com";
    assertEquals(Arrays.asList("https://a.com", "https://b.com"),
        UrlInputParser.extractUrls(text));
  }

  /** 只保留 http/https，其它协议与裸文本丢弃。 */
  @Test public void extractUrlsKeepsOnlyHttp() {
    String text = "ftp://a.com\nfile:///x\nhttps://ok.com\njavascript:alert(1)";
    assertEquals(Collections.singletonList("https://ok.com"), UrlInputParser.extractUrls(text));
  }

  @Test public void extractUrlsHandlesNullAndEmpty() {
    assertTrue(UrlInputParser.extractUrls(null).isEmpty());
    assertTrue(UrlInputParser.extractUrls("").isEmpty());
    assertTrue(UrlInputParser.extractUrls("没有地址").isEmpty());
  }
}