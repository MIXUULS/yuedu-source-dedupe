package com.mina.yuedu.check;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * 响应编码识别单元测试。
 *
 * 背景：校验引擎原来无条件用 UTF-8 解码响应体。国内大量老书源站是 GBK/GB2312，
 * 中文会被解成替换符，导致页面正文长度与关键词都判不出来，书源被误判成
 * "搜索失效 / 正文失效"。这里固化判定顺序：Content-Type → BOM → meta 嗅探 → 默认。
 */
public class HttpProbeDecodeTest {

  private static final Charset GB18030 = Charset.forName("GB18030");

  /** 中文在没有 charset 声明时会走嗅探：GBK 字节不是合法 UTF-8，应被判成 GB18030。 */
  @Test public void decodesGbkBytesWithoutCharsetHeader() {
    byte[] gbk = "中文书名测试内容".getBytes(GB18030);
    String decoded = HttpProbe.decode(gbk, null);
    assertEquals("中文书名测试内容", decoded);
    assertFalse("不应出现替换符", decoded.contains("\uFFFD"));
  }

  /** Content-Type 里声明了 charset 时以声明为准。 */
  @Test public void honorsCharsetFromContentType() {
    byte[] gbk = "笔趣阁小说网".getBytes(GB18030);
    assertEquals("笔趣阁小说网", HttpProbe.decode(gbk, "text/html; charset=gbk"));
    assertEquals("笔趣阁小说网", HttpProbe.decode(gbk, "text/html; charset=GB2312"));
    assertEquals("笔趣阁小说网", HttpProbe.decode(gbk, "text/html;charset=\"gbk\""));
  }

  /** UTF-8 内容不能被误判成 GBK。 */
  @Test public void keepsUtf8ContentIntact() {
    byte[] utf8 = "中文内容 UTF-8".getBytes(StandardCharsets.UTF_8);
    assertEquals("中文内容 UTF-8", HttpProbe.decode(utf8, null));
    assertEquals("中文内容 UTF-8", HttpProbe.decode(utf8, "text/html; charset=utf-8"));
  }

  /** BOM 优先级最高，即使 header 声明错误也能正确解码。 */
  @Test public void bomWinsOverWrongHeader() {
    byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
        "带 BOM 的内容".getBytes(StandardCharsets.UTF_8));
    assertEquals("带 BOM 的内容", HttpProbe.decode(withBom, "text/html; charset=gbk"));
  }

  /** HTML 头部 meta 声明 charset 时也能识别（很多老站只在 meta 里写）。 */
  @Test public void sniffsCharsetFromMetaTag() {
    String html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=gbk\">"
        + "</head><body>中文正文</body></html>";
    byte[] bytes = html.getBytes(GB18030);
    assertTrue(HttpProbe.decode(bytes, null).contains("中文正文"));
  }

  /** 纯 ASCII 内容不受编码影响。 */
  @Test public void plainAsciiDecodesFine() {
    byte[] ascii = "{\"name\":\"abc\"}".getBytes(StandardCharsets.US_ASCII);
    assertEquals("{\"name\":\"abc\"}", HttpProbe.decode(ascii, null));
  }

  /** 空输入不应抛异常。 */
  @Test public void emptyBodyIsSafe() {
    assertEquals("", HttpProbe.decode(new byte[0], null));
    assertEquals("", HttpProbe.decode(null, "text/html; charset=gbk"));
  }

  /** 无法识别的 charset 名称不能抛异常，要退回到嗅探/默认。 */
  @Test public void unknownCharsetNameFallsBack() {
    byte[] utf8 = "未知编码".getBytes(StandardCharsets.UTF_8);
    assertEquals("未知编码", HttpProbe.decode(utf8, "text/html; charset=x-unknown-enc"));
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
