package com.mina.yuedu.export;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** CSV 导出（转义规则 + BOM + 表头 + 行拼装）单元测试。 */
public class CsvBuilderTest {

  private static CheckSourceResult row(String name, String url, String msg,
                                      CheckSourceResult.Status st, long ms) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    SourceRecord s = new SourceRecord(0, name, url, raw);
    return new CheckSourceResult(s, st,
        Collections.<String>emptyList(), msg, ms, SourceKind.NOVEL);
  }

  /** BOM 是 Excel 正确识别 UTF-8 的前提，缺了中文会乱码。 */
  @Test public void startsWithUtf8Bom() {
    String csv = CsvBuilder.build(Collections.singletonList(
        row("测试", "https://a.com", "", CheckSourceResult.Status.SUCCESS, 10)));
    assertNotNull(csv);
    assertTrue("必须以 UTF-8 BOM 开头", csv.startsWith("\uFEFF"));
  }

  @Test public void containsHeader() {
    String csv = CsvBuilder.build(Collections.singletonList(
        row("A", "https://a.com", "", CheckSourceResult.Status.SUCCESS, 10)));
    assertTrue(csv.contains("名称,网址,结果,说明,耗时(ms),类型"));
  }

  /** 各状态映射成可读中文。 */
  @Test public void mapsStatusLabels() {
    List<CheckSourceResult> list = new ArrayList<>();
    list.add(row("S", "https://s.com", "", CheckSourceResult.Status.SUCCESS, 1));
    list.add(row("T", "https://t.com", "", CheckSourceResult.Status.TIMEOUT, 2));
    list.add(row("F", "https://f.com", "", CheckSourceResult.Status.FAILED, 3));
    String csv = CsvBuilder.build(list);
    assertTrue(csv.contains("可用"));
    assertTrue(csv.contains("超时"));
    assertTrue(csv.contains("失败"));
  }

  /** 含逗号的字段必须整体加引号，否则列会错位。 */
  @Test public void commaIsQuoted() {
    assertEquals("\"a,b\"", CsvBuilder.escape("a,b"));
  }

  /** 内部双引号要翻倍。 */
  @Test public void quoteIsDoubled() {
    assertEquals("\"say \"\"hi\"\"\"", CsvBuilder.escape("say \"hi\""));
  }

  /** 换行要加引号。 */
  @Test public void newlineIsQuoted() {
    assertEquals("\"line1\nline2\"", CsvBuilder.escape("line1\nline2"));
  }

  /** 回归：回车也要加引号。原来漏了 \r，含回车的失败原因会把 CSV 行拆断。 */
  @Test public void carriageReturnIsQuoted() {
    assertEquals("\"line1\r\nline2\"", CsvBuilder.escape("line1\r\nline2"));
    assertEquals("\"only\\r\"".replace("\\r", "\r"), CsvBuilder.escape("only\r"));
  }

  @Test public void plainFieldIsUnquoted() {
    assertEquals("https://a.com", CsvBuilder.escape("https://a.com"));
    assertEquals("", CsvBuilder.escape(null));
  }

  /** 完整行：字段顺序与数量正确。 */
  @Test public void buildsWellFormedRow() {
    String csv = CsvBuilder.build(Collections.singletonList(
        row("书源A", "https://a.com", "搜索失效", CheckSourceResult.Status.FAILED, 1234)));
    String[] lines = csv.substring(1).split("\n");
    assertEquals("表头 + 1 行数据", 2, lines.length);
    String[] cols = lines[1].split(",");
    assertEquals(6, cols.length);
    assertEquals("书源A", cols[0]);
    assertEquals("https://a.com", cols[1]);
    assertEquals("失败", cols[2]);
    assertEquals("搜索失效", cols[3]);
    assertEquals("1234", cols[4]);
    assertEquals("小说", cols[5]);
  }

  /** 空列表返回 null，调用方据此提示"没有可导出的数据"。 */
  @Test public void emptyListReturnsNull() {
    assertNull(CsvBuilder.build(null));
    assertNull(CsvBuilder.build(Collections.<CheckSourceResult>emptyList()));
  }

  /** 含逗号的名称不会破坏列数。 */
  @Test public void commaInNameKeepsColumnCount() {
    String csv = CsvBuilder.build(Collections.singletonList(
        row("书源,A", "https://a.com", "", CheckSourceResult.Status.SUCCESS, 5)));
    String dataLine = csv.substring(1).split("\n")[1];
    // 引号内的逗号不应被当作分隔符；这里用简单计数确认整行仍是 6 列
    int commasOutsideQuotes = 0;
    boolean inQuotes = false;
    for (char c : dataLine.toCharArray()) {
      if (c == '"') inQuotes = !inQuotes;
      else if (c == ',' && !inQuotes) commasOutsideQuotes++;
    }
    assertEquals(5, commasOutsideQuotes);
  }
}