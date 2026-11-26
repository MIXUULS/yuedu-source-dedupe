package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 严格模式的 www / 手机站归一化测试（Issue #1 三.3）。
 *
 * 用户反馈：很多书源同时存在 www.site.com、m.site.com、site.com 三个版本，
 * 内容一样只是入口不同，严格模式下应视为同一书源。
 *
 * 只在 STRICT 模式启用——标准模式保持"不同主机就是不同源"的保守语义。
 */
public class StrictHostNormalizationTest {

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }

  // ---------- 主机归一化 ----------

  @Test public void stripsWwwPrefix() {
    assertEquals("a.com", UrlNormalizer.normalizeMobileHost("www.a.com"));
  }

  @Test public void stripsMobilePrefixes() {
    assertEquals("a.com", UrlNormalizer.normalizeMobileHost("m.a.com"));
    assertEquals("a.com", UrlNormalizer.normalizeMobileHost("wap.a.com"));
    assertEquals("a.com", UrlNormalizer.normalizeMobileHost("mobile.a.com"));
  }

  /** 裸域保持不变。 */
  @Test public void bareDomainUnchanged() {
    assertEquals("a.com", UrlNormalizer.normalizeMobileHost("a.com"));
    assertEquals("sub.a.com", UrlNormalizer.normalizeMobileHost("sub.a.com"));
  }

  /** 剥完会只剩顶级域的不剥（如 www.com 本身可能就是裸域）。 */
  @Test public void doesNotStripWhenResultWouldBeTldOnly() {
    assertEquals("www.com", UrlNormalizer.normalizeMobileHost("www.com"));
    assertEquals("m.com", UrlNormalizer.normalizeMobileHost("m.com"));
  }

  /** IP 地址不处理。 */
  @Test public void ipAddressUnchanged() {
    assertEquals("192.168.1.1", UrlNormalizer.normalizeMobileHost("192.168.1.1"));
  }

  @Test public void nullAndEmptySafe() {
    assertNull(UrlNormalizer.normalizeMobileHost(null));
    assertEquals("", UrlNormalizer.normalizeMobileHost(""));
  }

  // ---------- 严格模式 key ----------

  @Test public void strictKeyNormalizesHost() throws Exception {
    String a = UrlNormalizer.key("https://www.site.com/book", DedupeMode.STRICT);
    String b = UrlNormalizer.key("https://site.com/book", DedupeMode.STRICT);
    assertEquals("www 与裸域应产生相同 key", a, b);
  }

  @Test public void strictKeyNormalizesMobile() throws Exception {
    String a = UrlNormalizer.key("https://m.site.com/book", DedupeMode.STRICT);
    String b = UrlNormalizer.key("https://wap.site.com/book", DedupeMode.STRICT);
    String c = UrlNormalizer.key("https://site.com/book", DedupeMode.STRICT);
    assertEquals(a, b);
    assertEquals(a, c);
  }

  /** 路径不同仍然是不同源（归一化只作用于主机）。 */
  @Test public void strictKeyStillDistinguishesPaths() throws Exception {
    String a = UrlNormalizer.key("https://www.site.com/a", DedupeMode.STRICT);
    String b = UrlNormalizer.key("https://www.site.com/b", DedupeMode.STRICT);
    assertNotEquals("不同路径不应合并", a, b);
  }

  /** 身份标签仍然生效。 */
  @Test public void strictKeyKeepsIdentityTag() throws Exception {
    String a = UrlNormalizer.key("https://www.site.com#简体", DedupeMode.STRICT);
    String b = UrlNormalizer.key("https://site.com#简体", DedupeMode.STRICT);
    assertEquals("归一化后标签仍应匹配", a, b);

    String c = UrlNormalizer.key("https://www.site.com#繁体", DedupeMode.STRICT);
    assertNotEquals("不同标签不应合并", a, c);
  }

  // ---------- 模式隔离 ----------

  /** 标准模式不受影响：www 与裸域仍是不同源。 */
  @Test public void standardModeUnchanged() throws Exception {
    String a = UrlNormalizer.key("https://www.site.com", DedupeMode.STANDARD);
    String b = UrlNormalizer.key("https://site.com", DedupeMode.STANDARD);
    assertNotEquals("标准模式应保持原语义", a, b);
  }

  /** 激进模式本来就按 host 合并，不受影响。 */
  @Test public void aggressiveModeStillWorks() throws Exception {
    assertEquals(UrlNormalizer.key("https://a.com/x", DedupeMode.AGGRESSIVE),
        UrlNormalizer.key("https://a.com/y", DedupeMode.AGGRESSIVE));
  }

  // ---------- 端到端 ----------

  @Test public void strictModeMergesWwwAndMobile() {
    DedupeResult r = DedupeEngine.run(Arrays.asList(
        source(0, "PC站", "https://www.site.com"),
        source(1, "手机站", "https://m.site.com"),
        source(2, "裸域", "https://site.com")), DedupeMode.STRICT, false);

    assertEquals("三个版本应合并为 1 条", 1, r.getRetained().size());
    assertEquals("应记为 2 条重复", 2, r.getDuplicateCount());
  }

  @Test public void standardModeKeepsThemSeparate() {
    DedupeResult r = DedupeEngine.run(Arrays.asList(
        source(0, "PC站", "https://www.site.com"),
        source(1, "手机站", "https://m.site.com"),
        source(2, "裸域", "https://site.com")), DedupeMode.STANDARD, false);

    assertEquals("标准模式应保留 3 条", 3, r.getRetained().size());
    assertEquals(0, r.getDuplicateCount());
  }

  /** 不同站点不能被误合并。 */
  @Test public void strictModeDoesNotMergeDifferentSites() {
    DedupeResult r = DedupeEngine.run(Arrays.asList(
        source(0, "A站", "https://www.a.com"),
        source(1, "B站", "https://www.b.com")), DedupeMode.STRICT, false);

    assertEquals("不同站不应合并", 2, r.getRetained().size());
  }
}