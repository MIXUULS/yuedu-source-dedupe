package com.mina.yuedu.export;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.core.MiniJson;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 导出 payload 构建的回归测试。
 *
 * 这段逻辑历史上出过两次 bug：
 *  1. 日期标记被分类标签覆盖（两处各自 put("bookSourceGroup", …)）
 *  2. 旧日期标记层层堆叠
 * 抽取成 ExportPayloadBuilder 后由这些用例守住。
 */
public class ExportPayloadBuilderTest {

  private static final String STAMP = "2026-11-24";

  private static SourceRecord novel(String order, String name, String url, String group) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    if (group != null) raw.put("bookSourceGroup", group);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(Integer.parseInt(order), name, url, raw);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> first(List<Object> payload) {
    return (Map<String, Object>) payload.get(0);
  }

  @Test public void emptyListProducesEmptyPayload() {
    assertTrue(ExportPayloadBuilder.build(Collections.emptyList(), null, STAMP).isEmpty());
    assertTrue(ExportPayloadBuilder.build(null, null, STAMP).isEmpty());
    assertNull("空列表导出 JSON 应返回 null，调用方据此提示无数据",
        ExportPayloadBuilder.toJson(Collections.emptyList(), null, STAMP));
  }

  /** 基本规则：原分组 + 分类标签 + 日期标记。 */
  @Test public void groupGetsKindLabelAndDateMark() {
    List<Object> payload = ExportPayloadBuilder.build(
        Collections.singletonList(novel("0", "A", "https://a.com", "自用")), null, STAMP);
    String group = String.valueOf(first(payload).get("bookSourceGroup"));
    assertEquals("自用,小说,✔" + STAMP + "小说（1）", group);
  }

  /** 回归 1：日期标记不能被分类标签那一步覆盖掉。 */
  @Test public void dateMarkSurvivesAlongsideKindLabel() {
    List<Object> payload = ExportPayloadBuilder.build(
        Collections.singletonList(novel("0", "A", "https://a.com", null)), null, STAMP);
    String group = String.valueOf(first(payload).get("bookSourceGroup"));
    assertTrue("必须含日期标记", group.contains("✔" + STAMP));
    assertTrue("必须含分类标签", group.contains("小说"));
  }

  /** 原分组里已经有分类标签时不重复追加。 */
  @Test public void existingKindLabelIsNotDuplicated() {
    List<Object> payload = ExportPayloadBuilder.build(
        Collections.singletonList(novel("0", "A", "https://a.com", "小说")), null, STAMP);
    String group = String.valueOf(first(payload).get("bookSourceGroup"));
    assertEquals("小说,✔" + STAMP + "小说（1）", group);
  }

  /** 回归 2：旧日期标记要清掉，不能层层堆叠。 */
  @Test public void oldDateMarkIsReplacedNotStacked() {
    List<Object> payload = ExportPayloadBuilder.build(
        Collections.singletonList(novel("0", "A", "https://a.com", "自用,小说,✔2025-1-1小说（9）")),
        null, STAMP);
    String group = String.valueOf(first(payload).get("bookSourceGroup"));
    assertFalse("旧日期标记必须被清除", group.contains("2025-1-1"));
    assertTrue("新日期标记必须在", group.contains("✔" + STAMP));
    assertEquals("自用,小说,✔" + STAMP + "小说（1）", group);
  }

  /** 空分组不应产生前导逗号。 */
  @Test public void emptyGroupHasNoLeadingComma() {
    List<Object> payload = ExportPayloadBuilder.build(
        Collections.singletonList(novel("0", "A", "https://a.com", "")), null, STAMP);
    String group = String.valueOf(first(payload).get("bookSourceGroup"));
    assertFalse("不应有前导逗号", group.startsWith(","));
    assertFalse("不应有连续逗号", group.contains(",,"));
  }

  /** 每个类型各自一个标记，数量按该类型统计。 */
  @Test public void kindMarksCountPerKind() {
    List<SourceRecord> list = new ArrayList<>();
    list.add(novel("0", "N1", "https://a.com", null));
    list.add(novel("1", "N2", "https://b.com", null));
    Map<String, Object> comicRaw = new LinkedHashMap<>();
    comicRaw.put("bookSourceName", "C1");
    comicRaw.put("bookSourceUrl", "https://c.com");
    comicRaw.put("bookSourceType", 2); // COMIC
    list.add(new SourceRecord(2, "C1", "https://c.com", comicRaw));

    List<Object> payload = ExportPayloadBuilder.build(list, null, STAMP);
    String novelGroup = String.valueOf(((Map<?, ?>) payload.get(0)).get("bookSourceGroup"));
    String comicGroup = String.valueOf(((Map<?, ?>) payload.get(2)).get("bookSourceGroup"));
    assertTrue("小说应统计 2 条", novelGroup.contains("小说（2）"));
    assertTrue("漫画应统计 1 条", comicGroup.contains("漫画（1）"));
  }

  /** 校验失败步骤写入 bookSourceComment。 */
  @Test public void checkFailureGroupsGoIntoComment() {
    SourceRecord s = novel("0", "A", "https://a.com", null);
    CheckSourceResult r = new CheckSourceResult(s, CheckSourceResult.Status.FAILED,
        Arrays.asList("搜索失效", "正文失效"), "搜索失效,正文失效", 120, SourceKind.NOVEL);

    List<Object> payload = ExportPayloadBuilder.build(Collections.singletonList(s),
        Collections.singletonList(r), STAMP);
    String comment = String.valueOf(first(payload).get("bookSourceComment"));
    assertTrue(comment.contains("小说:搜索失效,正文失效"));
  }

  /** 备注里已经有同一段说明时不重复追加（避免多次导出越堆越长）。 */
  @Test public void existingCommentIsNotDuplicated() {
    SourceRecord s = novel("0", "A", "https://a.com", null);
    Map<String, Object> raw = new LinkedHashMap<>(s.getRaw());
    raw.put("bookSourceComment", "小说:搜索失效");
    SourceRecord withComment = new SourceRecord(0, "A", "https://a.com", raw);
    CheckSourceResult r = new CheckSourceResult(withComment, CheckSourceResult.Status.FAILED,
        Collections.singletonList("搜索失效"), "搜索失效", 100, SourceKind.NOVEL);

    List<Object> payload = ExportPayloadBuilder.build(Collections.singletonList(withComment),
        Collections.singletonList(r), STAMP);
    String comment = String.valueOf(first(payload).get("bookSourceComment"));
    assertEquals("应保持原样，不重复追加", "小说:搜索失效", comment);
  }

  /** 追加备注时保留原有内容。 */
  @Test public void appendsToExistingComment() {
    SourceRecord s = novel("0", "A", "https://a.com", null);
    Map<String, Object> raw = new LinkedHashMap<>(s.getRaw());
    raw.put("bookSourceComment", "人工备注");
    SourceRecord withComment = new SourceRecord(0, "A", "https://a.com", raw);
    CheckSourceResult r = new CheckSourceResult(withComment, CheckSourceResult.Status.FAILED,
        Collections.singletonList("搜索失效"), "搜索失效", 100, SourceKind.NOVEL);

    List<Object> payload = ExportPayloadBuilder.build(Collections.singletonList(withComment),
        Collections.singletonList(r), STAMP);
    String comment = String.valueOf(first(payload).get("bookSourceComment"));
    assertEquals("人工备注 | 小说:搜索失效", comment);
  }

  /** 无校验结果时不写备注。 */
  @Test public void noCheckResultMeansNoComment() {
    List<Object> payload = ExportPayloadBuilder.build(
        Collections.singletonList(novel("0", "A", "https://a.com", null)), null, STAMP);
    assertNull(first(payload).get("bookSourceComment"));
  }

  /** 生成的 JSON 必须可被自己的解析器读回，且字段完整。 */
  @SuppressWarnings("unchecked")
  @Test public void generatedJsonIsParsable() throws Exception {
    List<Object> payload = ExportPayloadBuilder.build(
        Collections.singletonList(novel("0", "A", "https://a.com", "自用")), null, STAMP);
    String json = MiniJson.stringify(payload);
    Object root = MiniJson.parse(json);
    assertTrue(root instanceof List);
    Map<String, Object> item = (Map<String, Object>) ((List<Object>) root).get(0);
    assertEquals("A", item.get("bookSourceName"));
    assertEquals("https://a.com", item.get("bookSourceUrl"));
    assertNotNull(item.get("bookSourceGroup"));
  }

  /** toJson 与 build 结果一致（同一份数据）。 */
  @Test public void toJsonMatchesBuild() {
    List<SourceRecord> list = Collections.singletonList(novel("0", "A", "https://a.com", null));
    String json = ExportPayloadBuilder.toJson(list, null, STAMP);
    assertNotNull(json);
    assertEquals(MiniJson.stringify(ExportPayloadBuilder.build(list, null, STAMP)), json);
  }
}