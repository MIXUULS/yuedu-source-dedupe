package com.mina.yuedu.network;

import com.mina.yuedu.model.InvalidSource;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 网络响应归类测试。
 *
 * 这是"抓回来的东西到底是什么"的唯一决策点，出错的表现是
 * "某些 YCK 页面抓不到书源"或"错误信息被吞掉"，用户只看到成功数少了几条。
 */
public class FetchResponseClassifierTest {

  private static final String URL = "https://example.com/list.json";

  private static SourceParser.ParseResult parsedWithSources() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "A");
    raw.put("bookSourceUrl", "https://a.com");
    SourceRecord rec = new SourceRecord(0, "A", "https://a.com", raw);
    return SourceParser.ParseResult.of(
        Collections.singletonList(rec), Collections.<InvalidSource>emptyList());
  }

  private static SourceParser.ParseResult parsedEmpty() {
    return SourceParser.ParseResult.of(
        Collections.<SourceRecord>emptyList(), Collections.<InvalidSource>emptyList());
  }

  private static SourceParser.ParseResult parsedEmptyWithInvalid() {
    return SourceParser.ParseResult.of(
        Collections.<SourceRecord>emptyList(),
        Collections.singletonList(new InvalidSource(
            InvalidSource.Kind.NOT_JSON_ARRAY, "解析器给的具体原因")));
  }

  /** 已解析出书源时直接收下，不再扫描正文。 */
  @Test public void recordsTakePriority() {
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(
        URL, "随便什么正文", parsedWithSources());
    assertTrue(r.hasRecords());
    assertFalse("有书源时不应再去找更多地址", r.hasMore());
    assertFalse(r.isInvalid());
    assertEquals(1, r.parsed.getRecords().size());
  }

  /** 正文里有间接地址时，应作为待抓取地址返回。 */
  @Test public void extractsIndirectUrl() {
    String body = "<html><script>location.href='https://real.com/books.json'</script></html>";
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(URL, body, parsedEmpty());
    assertFalse(r.hasRecords());
    assertTrue("应提取到待抓取地址", r.hasMore());
    assertFalse(r.isInvalid());
  }

  /**
   * 正文里能扫出 JSON 地址时同样返回待抓取。
   *
   * 注意 discoverYckJsonUrls 只认 href / value / data-url 属性里的 .json 链接，
   * 不解析裸 JSON 字符串——这里用真实的 YCK 页面结构。
   */
  @Test public void discoversJsonUrlsInBody() {
    String body = "<html><body>"
        + "<a href=\"/yuedu/shuyuans/json/id/1289.json\">书源1</a>"
        + "<a href=\"/yuedu/shuyuans/json/id/1290.json\">书源2</a>"
        + "</body></html>";
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(URL, body, parsedEmpty());
    assertTrue("应扫出 JSON 地址", r.hasMore());
    assertEquals(2, r.discovered.size());
  }

  /** 提取到的地址要去重（间接地址与 JSON 扫描可能命中同一个）。 */
  @Test public void discoveredUrlsAreDeduplicated() {
    String body = "<a href=\"/yuedu/shuyuans/json/id/1289.json\">A</a>"
        + "<a href=\"/yuedu/shuyuans/json/id/1289.json\">A again</a>";
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(URL, body, parsedEmpty());
    assertTrue(r.hasMore());
    long distinct = r.discovered.stream().distinct().count();
    assertEquals("同一地址不应重复", distinct, r.discovered.size());
    assertEquals(1, r.discovered.size());
  }

  /** 什么都没有时归为无效源。 */
  @Test public void emptyResponseIsInvalid() {
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(
        URL, "<html>没有书源</html>", parsedEmpty());
    assertFalse(r.hasRecords());
    assertFalse(r.hasMore());
    assertTrue(r.isInvalid());
  }

  /** 解析器给出具体原因时优先用它，不要覆盖成通用文案。 */
  @Test public void prefersParserInvalidReason() {
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(
        URL, "无关正文", parsedEmptyWithInvalid());
    assertTrue(r.isInvalid());
    assertEquals("应保留解析器的具体原因", "解析器给的具体原因", r.invalid.getDetail());
  }

  /** 解析器没给原因时才用通用文案，并带上 URL 便于排查。 */
  @Test public void fallsBackToGenericReasonWithUrl() {
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(
        URL, "无关正文", parsedEmpty());
    assertTrue(r.isInvalid());
    assertTrue("应包含 URL，实际: " + r.invalid.getDetail(),
        r.invalid.getDetail().contains(URL));
  }

  /** null 输入不应崩（网络层可能只回传状态码）。 */
  @Test public void nullInputsAreSafe() {
    FetchResponseClassifier.Result r1 = FetchResponseClassifier.classify(URL, null, null);
    assertTrue(r1.isInvalid());

    FetchResponseClassifier.Result r2 = FetchResponseClassifier.classify(
        URL, null, parsedEmpty());
    assertTrue(r2.isInvalid());

    FetchResponseClassifier.Result r3 = FetchResponseClassifier.classify(URL, "", parsedWithSources());
    assertTrue(r3.hasRecords());
  }

  /** 空正文但解析器有记录时仍应视为成功。 */
  @Test public void emptyBodyWithRecordsIsSuccess() {
    FetchResponseClassifier.Result r = FetchResponseClassifier.classify(
        URL, "", parsedWithSources());
    assertTrue(r.hasRecords());
    assertFalse(r.isInvalid());
  }
}