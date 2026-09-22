package com.mina.yuedu.core;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** MiniJson 解析健壮性测试。 */
public class MiniJsonTest {

  @Test public void deepNestingThrowsIllegalArgumentNotStackOverflow() {
    // 回归：递归无深度上限时，深层嵌套输入抛 StackOverflowError（Error），
    // 绕过所有调用方的 catch(Exception) 直接闪退
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) sb.append('[');
    for (int i = 0; i < 5000; i++) sb.append(']');
    try {
      MiniJson.parse(sb.toString());
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertNotNull(expected.getMessage());
    }
  }

  @Test public void nestingBelowLimitStillParses() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 100; i++) sb.append('[');
    for (int i = 0; i < 100; i++) sb.append(']');
    Object v = MiniJson.parse(sb.toString());
    assertTrue(v instanceof List);
    assertEquals(1, ((List<?>) v).size());
  }

  @Test public void trailingCommaGivesDescriptiveError() {
    // 回归：旧实现把 "]"/"}" 落到 number()，抛的是裸 NumberFormatException("empty String")
    try {
      MiniJson.parse("[1,]");
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("unexpected character"));
    }
  }

  @Test public void malformedNumberGivesDescriptiveError() {
    try {
      MiniJson.parse("1e");
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("bad number"));
    }
  }

  @Test public void basicTypesParse() {
    Object v = MiniJson.parse("[1,-2.5,\"a\\\"b\",true,false,null,{\"k\":[1]}]");
    assertTrue(v instanceof List);
    List<?> list = (List<?>) v;
    assertEquals(7, list.size());
    assertEquals(1L, list.get(0));
    assertEquals(-2.5, list.get(1));
    assertEquals("a\"b", list.get(2));
    assertEquals(Boolean.TRUE, list.get(3));
    assertEquals(Boolean.FALSE, list.get(4));
    assertNull(list.get(5));
    assertTrue(list.get(6) instanceof Map);
  }

  @Test public void stringifyParseRoundTrip() {
    String json = "[{\"n\":\"名字\",\"ok\":true,\"v\":1.5}]";
    Object parsed = MiniJson.parse(json);
    String out = MiniJson.stringify(parsed);
    Object reparsed = MiniJson.parse(out);
    assertEquals(parsed, reparsed);
  }
}
