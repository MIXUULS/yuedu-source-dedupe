package com.mina.yuedu.check;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CheckSourceSettingsTest {
  @Test public void concurrencyIsNormalizedToOneThroughFiveHundred() {
    assertEquals(500, CheckSourceSettings.MAX_CONCURRENCY);

    CheckSourceSettings belowMinimum = new CheckSourceSettings();
    belowMinimum.concurrency = 0;
    belowMinimum.normalize();
    assertEquals(1, belowMinimum.concurrency);

    CheckSourceSettings atMaximum = new CheckSourceSettings();
    atMaximum.concurrency = 500;
    atMaximum.normalize();
    assertEquals(500, atMaximum.concurrency);

    CheckSourceSettings aboveMaximum = new CheckSourceSettings();
    aboveMaximum.concurrency = 501;
    aboveMaximum.normalize();
    assertEquals(500, aboveMaximum.concurrency);
  }

  @Test public void defaultConcurrencyRemainsEight() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.normalize();
    assertEquals(8, settings.concurrency);
    assertTrue(settings.concurrency <= CheckSourceSettings.MAX_CONCURRENCY);
  }

  @Test public void blankKeywordFallsBackToDefault() {
    CheckSourceSettings empty = new CheckSourceSettings();
    empty.keyword = "";
    empty.normalize();
    assertEquals("我的", empty.keyword);

    CheckSourceSettings spaces = new CheckSourceSettings();
    spaces.keyword = "   ";
    spaces.normalize();
    assertEquals("我的", spaces.keyword);

    CheckSourceSettings nul = new CheckSourceSettings();
    nul.keyword = null;
    nul.normalize();
    assertEquals("我的", nul.keyword);
  }

  @Test public void customKeywordIsTrimmedAndKept() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.keyword = "  斗破苍穹  ";
    settings.normalize();
    assertEquals("斗破苍穹", settings.keyword);
  }

  @Test public void allKindFiltersOffRestoreAllOn() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.checkNovel = false;
    settings.checkComic = false;
    settings.checkVideo = false;
    settings.checkAudio = false;
    settings.checkFile = false;
    settings.normalize();
    assertTrue(settings.checkNovel);
    assertTrue(settings.checkComic);
    assertTrue(settings.checkVideo);
    assertTrue(settings.checkAudio);
    assertTrue(settings.checkFile);
  }

  @Test public void allowsHonorsKindFilters() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.checkNovel = false;
    settings.checkComic = true;
    settings.checkVideo = false;
    settings.checkAudio = true;
    settings.checkFile = false;
    settings.normalize();
    assertFalse(settings.allows(SourceKind.NOVEL));
    assertTrue(settings.allows(SourceKind.COMIC));
    assertFalse(settings.allows(SourceKind.VIDEO));
    assertTrue(settings.allows(SourceKind.AUDIO));
    assertFalse(settings.allows(SourceKind.FILE));
  }

  @Test public void resetToDefaultsRestoresKeywordTimeoutAndKinds() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.timeoutSeconds = 30;
    settings.concurrency = 100;
    settings.keyword = "斗破苍穹";
    settings.checkSearch = false;
    settings.checkNovel = false;
    settings.checkVideo = false;
    settings.resetToDefaults();
    assertEquals(180, settings.timeoutSeconds);
    assertEquals(8, settings.concurrency);
    assertEquals("我的", settings.keyword);
    assertTrue(settings.checkSearch);
    assertTrue(settings.checkNovel);
    assertTrue(settings.checkVideo);
    assertTrue(settings.checkComic);
  }

  @Test public void resetToDefaultsRestoresQuickAndFilterOptions() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.quickMode = true;
    settings.deleteCaptcha = true;
    settings.cleanLogin = true;

    settings.resetToDefaults();

    assertFalse(settings.quickMode);
    // 过滤选项默认关闭：有没有 loginUrl / captchaUrl 不足以判定书源不可用
    // （Issue #1 用户反馈：这些字段在 legado 里还用于切换 URL 等高级功能）
    assertFalse("默认不应剔除含登录入口的源", settings.deleteCaptcha);
    assertFalse("默认不应剔除含验证码配置的源", settings.cleanLogin);
  }

  /** 默认值本身必须是关闭的，避免用户什么都没做就丢书源。 */
  @Test public void filtersDefaultOff() {
    CheckSourceSettings settings = new CheckSourceSettings();
    assertFalse("新建设置的 deleteCaptcha 应默认关闭", settings.deleteCaptcha);
    assertFalse("新建设置的 cleanLogin 应默认关闭", settings.cleanLogin);
  }
}
