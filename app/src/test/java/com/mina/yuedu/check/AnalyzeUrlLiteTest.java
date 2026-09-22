package com.mina.yuedu.check;

import org.junit.Test;

import static org.junit.Assert.*;

/** legado 搜索 URL 选项解析的回归测试。 */
public class AnalyzeUrlLiteTest {
  private static final String BASE = "https://a.com/";

  @Test public void splitsTrailingOptionEvenWhenBodyContainsBraces() {
    // body 值内含嵌套对象数组：旧实现 lastIndexOf(",{") 会命中 body 里的 },{，整段 JSON 被当 URL
    String raw = "https://a.com/search,{\"method\":\"POST\",\"body\":\"{\\\"kw\\\":\\\"{{key}}\\\",\\\"list\\\":[{\\\"a\\\":1},{\\\"b\\\":2}]}\"}";
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(raw, BASE, "词", 1);
    assertEquals("https://a.com/search", r.url);
    assertEquals("POST", r.method);
    assertNotNull(r.body);
    // 既有行为：body 内的 {{key}} 替换为 URL 编码后的关键词
    assertTrue(r.body.contains(java.net.URLEncoder.encode("词")));
  }

  @Test public void methodKeyWinsOverPostSubstring() {
    // 旧实现 contains("post") 扫整个选项串，body 里出现 post 一词就把 GET 误翻成 POST
    String raw = "https://a.com/list,{\"method\":\"GET\",\"body\":\"action=post\"}";
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(raw, BASE, "词", 1);
    assertEquals("GET", r.method);
  }

  @Test public void bodyWithoutMethodDefaultsToPost() {
    String raw = "https://a.com/api,{\"body\":\"kw={{key}}\"}";
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(raw, BASE, "词", 1);
    assertEquals("POST", r.method);
    // 既有行为：body 内的 {{key}} 替换为 URL 编码后的关键词
    assertEquals("kw=" + java.net.URLEncoder.encode("词"), r.body);
  }

  @Test public void nestedHeadersObjectIsExtracted() {
    // legado 标准格式把请求头放在嵌套 "headers":{...} 里，旧实现只读顶层字符串键会整个丢掉
    String raw = "https://a.com/search,{\"method\":\"POST\",\"body\":\"q={{key}}\","
        + "\"headers\":{\"Referer\":\"https://r.com/page\",\"Cookie\":\"a=b\",\"User-Agent\":\"UA/1.0\"}}";
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(raw, BASE, "词", 1);
    assertEquals("POST", r.method);
    assertEquals("https://r.com/page", r.headers.get("Referer"));
    assertEquals("a=b", r.headers.get("Cookie"));
    assertEquals("UA/1.0", r.headers.get("User-Agent"));
  }

  @Test public void topLevelHeaderKeysStillWork() {
    String raw = "https://a.com/search,{\"method\":\"POST\",\"body\":\"q=1\",\"Referer\":\"https://r.com\"}";
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(raw, BASE, "词", 1);
    assertEquals("https://r.com", r.headers.get("Referer"));
  }

  @Test public void plainQueryCommaBraceIsNotSplit() {
    // URL 参数里出现 ,{ 但不含 method/body/header 键：不应切分
    String raw = "https://a.com/api?list=1,{\"x\":2}";
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(raw, BASE, "词", 1);
    assertEquals(raw, r.url);
    assertEquals("GET", r.method);
    assertNull(r.body);
  }

  @Test public void simpleGetUrlUnchanged() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse("https://a.com/s?q={{key}}&p={{page}}", BASE, "词", 3);
    assertEquals("https://a.com/s?q=" + java.net.URLEncoder.encode("词") + "&p=3", r.url);
    assertEquals("GET", r.method);
    assertNull(r.body);
  }
}
