package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 校验结果搜索过滤单元测试。 */
public class CheckResultFilterTest {

  private static CheckSourceResult result(String name, String url, CheckSourceResult.Status st) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    SourceRecord s = new SourceRecord(0, name, url, raw);
    return new CheckSourceResult(s, st, Collections.<String>emptyList(), st.name(), 10, SourceKind.NOVEL);
  }

  private static List<CheckSourceResult> sample() {
    return new ArrayList<>(Arrays.asList(
        result("笔趣阁", "https://biquge.com", CheckSourceResult.Status.SUCCESS),
        result("顶点小说", "https://dingdian.com", CheckSourceResult.Status.FAILED),
        result("Biquge English", "https://biquge.net", CheckSourceResult.Status.SUCCESS)));
  }

  /** 空关键词返回 null，表示"未启用过滤"，界面据此显示全量。 */
  @Test public void emptyKeywordMeansNoFilter() {
    assertNull(CheckResultFilter.filter(sample(), ""));
    assertNull(CheckResultFilter.filter(sample(), null));
  }

  /** 按中文名称匹配。 */
  @Test public void matchesByChineseName() {
    List<CheckSourceResult> out = CheckResultFilter.filter(sample(), "笔趣阁");
    assertEquals(1, out.size());
    assertEquals("笔趣阁", out.get(0).source.getName());
  }

  /** 按 URL 匹配。 */
  @Test public void matchesByUrl() {
    List<CheckSourceResult> out = CheckResultFilter.filter(sample(), "dingdian");
    assertEquals(1, out.size());
    assertEquals("顶点小说", out.get(0).source.getName());
  }

  /** 名称匹配不区分大小写。 */
  @Test public void nameMatchIsCaseInsensitive() {
    assertEquals(2, CheckResultFilter.filter(sample(), "biquge").size());
    assertEquals(2, CheckResultFilter.filter(sample(), "BIQUGE").size());
  }

  /** 一个关键词命中多条时全部返回。 */
  @Test public void returnsAllMatches() {
    List<CheckSourceResult> out = CheckResultFilter.filter(sample(), "biquge");
    assertEquals(2, out.size());
  }

  /** 没有命中时返回空列表（不是 null）——界面要区分"过滤后无结果"和"未过滤"。 */
  @Test public void noMatchReturnsEmptyListNotNull() {
    List<CheckSourceResult> out = CheckResultFilter.filter(sample(), "不存在的关键词");
    assertNotNull(out);
    assertTrue(out.isEmpty());
  }

  /** 空结果集与 null 输入都要安全。 */
  @Test public void handlesEmptyAndNullInput() {
    assertTrue(CheckResultFilter.filter(Collections.<CheckSourceResult>emptyList(), "x").isEmpty());
    assertTrue(CheckResultFilter.filter(null, "x").isEmpty());
  }

  /** 名称为空的书源按 URL 仍可被搜到。 */
  @Test public void blankNameStillSearchableByUrl() {
    List<CheckSourceResult> list = Collections.singletonList(
        result("", "https://onlyurl.com", CheckSourceResult.Status.SUCCESS));
    assertEquals(1, CheckResultFilter.filter(list, "onlyurl").size());
    assertTrue(CheckResultFilter.filter(list, "name").isEmpty());
  }
}