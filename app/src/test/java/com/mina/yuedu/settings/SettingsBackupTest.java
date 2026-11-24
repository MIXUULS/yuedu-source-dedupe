package com.mina.yuedu.settings;

import com.mina.yuedu.check.CheckSourceSettings;
import com.mina.yuedu.model.DedupeMode;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * 设置备份 / 还原单元测试。
 *
 * 关键回归点：
 *  1. 20 多个字段一个都不能漏（漏了就是静默丢配置）
 *  2. 备份文件被手工编辑后（数字写成字符串）不能抛异常打断还原
 *  3. 非本工具的文件要能被识别出来并给出提示，而不是写进一半设置
 */
public class SettingsBackupTest {

  private static SettingsBackup.Snapshot sample() {
    SettingsBackup.Snapshot s = new SettingsBackup.Snapshot();
    s.mode = DedupeMode.AGGRESSIVE.name();
    s.clean = true;
    s.onlyUsable = true;
    s.concurrency = 5;
    s.timeout = 240;
    s.checkConcurrency = 16;
    s.keyword = "斗破";
    s.okStatus = "200,403,500-502";
    s.quickMode = true;
    s.search = false;
    s.discovery = false;
    s.info = true;
    s.category = false;
    s.content = false;
    s.kindNovel = true;
    s.kindComic = false;
    s.kindVideo = false;
    s.kindAudio = true;
    s.kindFile = false;
    return s;
  }

  /** 往返：build → parse 后每个字段都要一致。 */
  @Test public void roundTripPreservesAllFields() {
    SettingsBackup.Snapshot in = sample();
    SettingsBackup.Snapshot out = SettingsBackup.parse(SettingsBackup.toJson(in));

    assertEquals(in.mode, out.mode);
    assertEquals(in.clean, out.clean);
    assertEquals(in.onlyUsable, out.onlyUsable);
    assertEquals(in.concurrency, out.concurrency);
    assertEquals(in.timeout, out.timeout);
    assertEquals(in.checkConcurrency, out.checkConcurrency);
    assertEquals(in.keyword, out.keyword);
    assertEquals(in.okStatus, out.okStatus);
    assertEquals(in.quickMode, out.quickMode);
    assertEquals("search", in.search, out.search);
    assertEquals("discovery", in.discovery, out.discovery);
    assertEquals("info", in.info, out.info);
    assertEquals("category", in.category, out.category);
    assertEquals("content", in.content, out.content);
    assertEquals("kindNovel", in.kindNovel, out.kindNovel);
    assertEquals("kindComic", in.kindComic, out.kindComic);
    assertEquals("kindVideo", in.kindVideo, out.kindVideo);
    assertEquals("kindAudio", in.kindAudio, out.kindAudio);
    assertEquals("kindFile", in.kindFile, out.kindFile);
  }

  /** 序列化结果必须带 version，否则还原时会判成"不是本工具的文件"。 */
  @Test public void jsonCarriesVersion() {
    Map<String, Object> map = SettingsBackup.toMap(sample());
    assertEquals(SettingsBackup.VERSION, map.get("version"));
    assertTrue(map.containsKey("checkSteps"));
    assertTrue(map.containsKey("checkKinds"));
  }

  /** 缺字段时用默认值补齐，不能报错。 */
  @Test public void missingFieldsFallBackToDefaults() {
    SettingsBackup.Snapshot s = SettingsBackup.parse("{\"version\":1}");
    SettingsBackup.Snapshot def = new SettingsBackup.Snapshot();
    assertEquals(def.mode, s.mode);
    assertEquals(def.concurrency, s.concurrency);
    assertEquals(def.timeout, s.timeout);
    assertEquals(def.keyword, s.keyword);
    assertEquals(def.search, s.search);
    assertEquals(def.kindNovel, s.kindNovel);
  }

  /** 回归：备份被手改成字符串数字时要能宽松解析，不能 ClassCastException。 */
  @Test public void numericStringsAreParsedLeniently() {
    SettingsBackup.Snapshot s = SettingsBackup.parse(
        "{\"version\":1,\"concurrency\":\"5\",\"timeout\":\"240\",\"checkConcurrency\":\"16\"}");
    assertEquals(5, s.concurrency);
    assertEquals(240, s.timeout);
    assertEquals(16, s.checkConcurrency);
  }

  /** 布尔值也接受字符串形式。 */
  @Test public void booleanStringsAreParsedLeniently() {
    SettingsBackup.Snapshot s = SettingsBackup.parse(
        "{\"version\":1,\"clean\":\"true\",\"onlyUsable\":0,\"quickMode\":1}");
    assertTrue(s.clean);
    assertFalse(s.onlyUsable);
    assertTrue(s.quickMode);
  }

  /** 无法解析的数字保留默认值，而不是抛异常。 */
  @Test public void unparsableNumbersKeepDefault() {
    SettingsBackup.Snapshot def = new SettingsBackup.Snapshot();
    SettingsBackup.Snapshot s = SettingsBackup.parse("{\"version\":1,\"timeout\":\"abc\"}");
    assertEquals(def.timeout, s.timeout);
  }

  /** 非本工具的文件要能被识别出来。 */
  @Test public void rejectsForeignFileWithoutVersion() {
    try {
      SettingsBackup.parse("{\"foo\":\"bar\"}");
      fail("缺少 version 应抛异常");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("不是本工具"));
    }
  }

  @Test public void rejectsNonObjectJson() {
    try {
      SettingsBackup.parse("[1,2,3]");
      fail("数组应被拒绝");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("格式不正确"));
    }
  }

  @Test public void rejectsMalformedJson() {
    try {
      SettingsBackup.parse("not json at all");
      fail("垃圾文本应被拒绝");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("解析失败"));
    }
  }

  /** 只带部分 checkSteps 时，未提及的步骤保持默认。 */
  @Test public void partialStepsOnlyOverrideMentioned() {
    SettingsBackup.Snapshot s = SettingsBackup.parse(
        "{\"version\":1,\"checkSteps\":{\"search\":false}}");
    assertFalse(s.search);
    assertTrue("未提及的步骤应保持默认", s.discovery);
    assertTrue(s.info);
  }

  /** 只带部分 checkKinds 时同样只覆盖提到的。 */
  @Test public void partialKindsOnlyOverrideMentioned() {
    SettingsBackup.Snapshot s = SettingsBackup.parse(
        "{\"version\":1,\"checkKinds\":{\"comic\":false}}");
    assertFalse(s.kindComic);
    assertTrue(s.kindNovel);
  }

  /** 默认值与界面首次启动一致。 */
  @Test public void defaultsMatchUiDefaults() {
    SettingsBackup.Snapshot s = new SettingsBackup.Snapshot();
    assertEquals(DedupeMode.STANDARD.name(), s.mode);
    assertEquals(CheckSourceSettings.DEFAULT_TIMEOUT, s.timeout);
    assertEquals(CheckSourceSettings.DEFAULT_CONCURRENCY, s.checkConcurrency);
    assertEquals(CheckSourceSettings.DEFAULT_KEYWORD, s.keyword);
    assertEquals(CheckSourceSettings.DEFAULT_OK_STATUS, s.okStatus);
    assertEquals(4, s.concurrency);
  }
}