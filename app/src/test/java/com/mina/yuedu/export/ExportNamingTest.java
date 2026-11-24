package com.mina.yuedu.export;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 导出文件名生成与清洗单元测试。
 *
 * 重点：分类名/日期里可能含文件系统非法字符（如用户自定义标签带 "/"），
 * 未清洗会导致保存失败且提示不明确。
 */
public class ExportNamingTest {

  // ---------- sanitize ----------

  @Test public void stripsIllegalFilesystemChars() {
    assertFalse(ExportNaming.sanitize("a/b").contains("/"));
    assertFalse(ExportNaming.sanitize("a\\b").contains("\\"));
    assertFalse(ExportNaming.sanitize("a:b").contains(":"));
    assertFalse(ExportNaming.sanitize("a*b").contains("*"));
    assertFalse(ExportNaming.sanitize("a?b").contains("?"));
    assertFalse(ExportNaming.sanitize("a\"b").contains("\""));
    assertFalse(ExportNaming.sanitize("a<b>c").contains("<"));
    assertFalse(ExportNaming.sanitize("a|b").contains("|"));
  }

  @Test public void replacesIllegalWithUnderscore() {
    assertEquals("a_b", ExportNaming.sanitize("a/b"));
    assertEquals("a_b_c", ExportNaming.sanitize("a:b?c"));
  }

  /** 连续下划线要压缩，避免 a___b 这种难看结果。 */
  @Test public void collapsesRepeatedUnderscores() {
    assertEquals("a_b", ExportNaming.sanitize("a//b"));
    assertEquals("a_b", ExportNaming.sanitize("a:::b"));
  }

  /** 换行/制表符转空格。 */
  @Test public void convertsWhitespaceControlsToSpace() {
    assertEquals("a b", ExportNaming.sanitize("a\nb"));
    assertEquals("a b", ExportNaming.sanitize("a\tb"));
  }

  /** 其它控制字符直接丢弃。 */
  @Test public void dropsOtherControlChars() {
    assertEquals("ab", ExportNaming.sanitize("a\u0000\u0001b"));
  }

  /** 全非法或空输入要有兜底名，不能生成 ".json" / " .json" 这种隐藏或异常文件名。 */
  @Test public void emptyOrAllIllegalGetsFallback() {
    assertEquals("export", ExportNaming.sanitize(""));
    assertEquals("export", ExportNaming.sanitize(null));
    assertEquals("export", ExportNaming.sanitize("   "));
    // 全非法字符清洗后只剩分隔符，同样走兜底
    assertEquals("export", ExportNaming.sanitize("///"));
    assertEquals("export", ExportNaming.sanitize(":::")); 
  }

  /** 超长名称要截断。 */
  @Test public void truncatesLongNames() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) sb.append('a');
    assertTrue(ExportNaming.sanitize(sb.toString()).length() <= 80);
  }

  /** 正常中文名不受影响。 */
  @Test public void keepsNormalChinese() {
    assertEquals("可用", ExportNaming.sanitize("可用"));
    assertEquals("非HTTP", ExportNaming.sanitize("非HTTP"));
    assertEquals("2026-11-24", ExportNaming.sanitize("2026-11-24"));
  }

  // ---------- 具体文件名 ----------

  @Test public void dedupeJsonName() {
    assertEquals("去重_1200_2026-11-24.json", ExportNaming.dedupeJson(1200, "2026-11-24"));
  }

  @Test public void categoryJsonName() {
    assertEquals("去重_可用_1200_2026-11-24.json",
        ExportNaming.categoryJson("可用", 1200, "2026-11-24"));
  }

  /** 分类名含非法字符时不能破坏文件名。 */
  @Test public void categoryWithIllegalCharsIsSanitized() {
    String name = ExportNaming.categoryJson("a/b", 10, "2026-11-24");
    assertEquals("去重_a_b_10_2026-11-24.json", name);
    assertFalse(name.contains("/"));
  }

  @Test public void checkCsvName() {
    assertEquals("校验结果_500_2026-11-24.csv", ExportNaming.checkCsv(500, "2026-11-24"));
  }

  @Test public void shareJsonName() {
    assertEquals("分享书源_50_2026-11-24.json", ExportNaming.shareJson(50, "2026-11-24"));
  }

  @Test public void settingsBackupName() {
    assertEquals("书源工具设置备份_2026-11-24.json", ExportNaming.settingsBackup("2026-11-24"));
  }

  @Test public void changeReportName() {
    assertEquals("变更报告_2026-11-24.json", ExportNaming.changeReport("2026-11-24"));
  }

  /** 日期参数也走清洗（防止调用方传了带斜杠的怪值）。 */
  @Test public void dateParameterIsSanitized() {
    assertFalse(ExportNaming.dedupeJson(1, "2026/11/24").contains("/"));
  }

  /** today() 格式稳定。 */
  @Test public void todayHasExpectedFormat() {
    assertTrue(ExportNaming.today().matches("\\d{4}-\\d{2}-\\d{2}"));
  }
}