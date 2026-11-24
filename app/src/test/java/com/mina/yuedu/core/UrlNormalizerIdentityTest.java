package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class UrlNormalizerIdentityTest {
  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(order, name, url, raw);
  }

  @Test public void unicodeAndEmojiIdentityTagsRemainValid() throws Exception {
    assertEquals("https://manwane.cc/#大改",
        UrlNormalizer.standardKey("https://manwane.cc#大改"));
    assertEquals("https://tybook.taoyuewenhua.net/#🎃",
        UrlNormalizer.key("https://tybook.taoyuewenhua.net#🎃", DedupeMode.STRICT));
    assertEquals("https://so.html5.qq.com/#🎃",
        UrlNormalizer.completeKey("https://so.html5.qq.com#🎃"));
  }

  @Test public void identityTagsDistinguishSourcesButRequestUsesOrigin() throws Exception {
    String plain = "https://example.com";
    String tagged = "https://example.com#简体";
    assertNotEquals(UrlNormalizer.standardKey(plain), UrlNormalizer.standardKey(tagged));
    assertEquals("https://example.com", UrlNormalizer.requestUrl(tagged));
    assertEquals("https://example.com", UrlNormalizer.requestUrl("https://example.com#🎃"));

    DedupeResult result = DedupeEngine.run(Arrays.asList(
        source(0, "原站", plain), source(1, "简体", tagged)), DedupeMode.STANDARD, false);
    assertEquals(2, result.getRetained().size());
    assertTrue(result.getInvalid().isEmpty());
  }

  @Test public void aggressiveModeStillMergesSameHost() {
    DedupeResult result = DedupeEngine.run(Arrays.asList(
        source(0, "原站", "https://example.com"),
        source(1, "简体", "https://example.com#简体")), DedupeMode.AGGRESSIVE, false);
    assertEquals(1, result.getRetained().size());
  }

  /**
   * 回归：原来直接用 URI.getHost() 做非空校验。它只认 RFC 3986 reg-name，
   * 遇到中文域名（例子.com）或带下划线的域名（a_b.com）会返回 null，
   * 这些书源就被整条判成"非法 URL"丢掉。这里保证它们能正常参与去重。
   */
  @Test public void chineseDomainSourcesAreNotDropped() throws Exception {
    String key = UrlNormalizer.standardKey("https://www.例子.com/book");
    assertNotNull(key);
    assertFalse("中文域名不应被判无效", key.isEmpty());
    assertTrue(key.contains("例子.com") || key.toLowerCase(Locale.ROOT).contains("xn--"));
  }

  @Test public void underscoreHostSourcesAreNotDropped() throws Exception {
    String key = UrlNormalizer.standardKey("http://a_b.com/x");
    assertTrue(key.contains("a_b.com"));
  }

  @Test public void chineseDomainSourcesSurviveDedupe() {
    DedupeResult result = DedupeEngine.run(Arrays.asList(
        source(0, "中文站", "https://www.例子.com/book"),
        source(1, "下划线站", "http://a_b.com/x"),
        source(2, "普通站", "https://example.com")), DedupeMode.STANDARD, false);
    assertEquals("合法书源都不应被判无效", 0, result.getInvalid().size());
    assertEquals(3, result.getRetained().size());
  }

  /** 真正畸形的地址仍然要能识别成无效，不能因为放宽校验就把垃圾放进来。 */
  @Test public void malformedUrlsStillRejected() {
    DedupeResult result = DedupeEngine.run(Arrays.asList(
        source(0, "畸形", "https:///nohost"),
        source(1, "无主机", "http://")), DedupeMode.STANDARD, false);
    assertEquals(2, result.getInvalid().size());
  }
}
