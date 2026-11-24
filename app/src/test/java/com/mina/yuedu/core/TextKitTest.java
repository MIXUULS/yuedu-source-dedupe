package com.mina.yuedu.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

/**
 * TextKit 单元测试。
 *
 * 重点：String.join / StringJoiner 是 API 26 才有的方法，本项目 minSdk = 24，
 * 直接调用在 Android 7.0/7.1 上会抛 NoSuchMethodError（Error 不被 catch Exception 捕获，
 * 表现为闪退）。这里保证 TextKit.join 的语义与 String.join 一致。
 */
public class TextKitTest {

  @Test public void joinIterableMatchesStringJoinSemantics() {
    assertEquals("a,b,c", TextKit.join(",", Arrays.asList("a", "b", "c")));
    assertEquals("小说、漫画", TextKit.join("、", Arrays.asList("小说", "漫画")));
  }

  @Test public void joinPreservesIterationOrderOfSet() {
    LinkedHashSet<String> steps = new LinkedHashSet<>(Arrays.asList("搜索", "发现", "详情"));
    assertEquals("搜索,发现,详情", TextKit.join(",", steps));
  }

  @Test public void joinSingleAndEmpty() {
    assertEquals("only", TextKit.join(",", Collections.singletonList("only")));
    assertEquals("", TextKit.join(",", Collections.emptyList()));
    assertEquals("", TextKit.join(",", (Iterable<?>) null));
  }

  @Test public void joinSkipsNullElementsWithoutBreakingDelimiter() {
    // 与 String.join 行为一致：null 元素按 "null" 输出；这里统一按空串处理，分隔符仍完整
    assertEquals("a,,c", TextKit.join(",", Arrays.asList("a", null, "c")));
  }

  @Test public void joinObjectArray() {
    assertEquals("1,2,3", TextKit.join(",", new Object[]{1, 2, 3}));
    assertEquals("", TextKit.join(",", (Object[]) null));
    assertEquals("x,,z", TextKit.join(",", new Object[]{"x", null, "z"}));
  }

  @Test public void isBlankHandlesNullAndWhitespace() {
    assertTrue(TextKit.isBlank(null));
    assertTrue(TextKit.isBlank(""));
    assertTrue(TextKit.isBlank("   \t\n"));
    assertTrue(TextKit.isBlank("\u3000\u00A0")); // 全角空格 / 不间断空格
    assertFalse(TextKit.isBlank(" x "));
  }

  @Test public void abbreviateTruncatesLongText() {
    assertEquals("abc", TextKit.abbreviate("abc", 10));
    assertEquals("abc…", TextKit.abbreviate("abcdef", 3));
    assertNull(TextKit.abbreviate(null, 3));
    assertEquals("", TextKit.abbreviate("abc", 0));
  }
}
