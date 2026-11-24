package com.mina.yuedu.network;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * JSON 外层包裹清理单元测试。
 *
 * 回归：旧实现用 lastIndexOf(']') / lastIndexOf('}') 找结尾，
 * 页面或日志尾部只要多一个括号，就会把无关文本一起截进来导致解析失败。
 */
public class SourceParserUnwrapTest {

  @Test public void trimsTrailingGarbageAfterArray() {
    String raw = "[{\"bookSourceName\":\"A\"}]  <-- 服务端附带的说明文字 ]";
    String clean = SourceParser.unwrapJson(raw);
    assertTrue("应当以 ] 结尾", clean.endsWith("]"));
    assertEquals("[{\"bookSourceName\":\"A\"}]", clean);
  }

  @Test public void keepsBracketsInsideStrings() {
    String raw = "[{\"bookSourceName\":\"书源[合集]\"}]";
    assertEquals(raw, SourceParser.unwrapJson(raw));
  }

  @Test public void keepsEscapedQuotes() {
    String raw = "[{\"bookSourceName\":\"a\\\"b\"}]";
    assertEquals(raw, SourceParser.unwrapJson(raw));
  }

  @Test public void handlesJsonpPrefix() {
    String raw = ")]}'\n[{\"bookSourceName\":\"A\"}]";
    assertEquals("[{\"bookSourceName\":\"A\"}]", SourceParser.unwrapJson(raw));
  }

  @Test public void handlesByteOrderMark() {
    String raw = "\uFEFF[{\"bookSourceName\":\"A\"}]";
    assertEquals("[{\"bookSourceName\":\"A\"}]", SourceParser.unwrapJson(raw));
  }

  @Test public void handlesObjectRoot() {
    String raw = "some log line\n{\"bookSourceName\":\"A\"}\ntrailing";
    assertEquals("{\"bookSourceName\":\"A\"}", SourceParser.unwrapJson(raw));
  }

  @Test public void nonJsonTextIsReturnedTrimmed() {
    assertEquals("plain text", SourceParser.unwrapJson("  plain text  "));
    assertEquals("", SourceParser.unwrapJson(null));
  }

  @Test public void unbalancedInputDoesNotThrow() {
    String raw = "[{\"bookSourceName\":\"A\"}";
    String clean = SourceParser.unwrapJson(raw);
    assertNotNull(clean);
    assertTrue(clean.startsWith("["));
  }
}
