package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 验证 SourceCleaner 过滤逻辑：cleanLogin + deleteCaptcha。 */
public class SourceCleanerTest {

  @Test public void normalSourceNeedsLoginFalse() {
    assertFalse(SourceCleaner.needsLogin(SourceCleaner.normalSource()));
  }

  @Test public void loginSourceNeedsLoginTrue() {
    assertTrue(SourceCleaner.needsLogin(SourceCleaner.loginSource()));
  }

  @Test public void loginCheckSourceNeedsLoginTrue() {
    assertTrue(SourceCleaner.needsLogin(SourceCleaner.loginCheckSource()));
  }

  @Test public void normalSourceHasCaptchaFalse() {
    assertFalse(SourceCleaner.hasCaptcha(SourceCleaner.normalSource()));
  }

  @Test public void captchaSourceHasCaptchaTrue() {
    assertTrue(SourceCleaner.hasCaptcha(SourceCleaner.captchaSource()));
  }

  @Test public void verifyCodeSourceHasCaptchaTrue() {
    assertTrue(SourceCleaner.hasCaptcha(SourceCleaner.verifyCodeSource()));
  }

  @Test public void authUrlSourceHasCaptchaTrue() {
    assertTrue(SourceCleaner.hasCaptcha(SourceCleaner.authUrlSource()));
  }

  @Test public void cleanLoginFiltersLoginSources() {
    List<SourceRecord> all = Arrays.asList(
        SourceCleaner.normalSource(),
        SourceCleaner.loginSource(),
        SourceCleaner.normalSource(),
        SourceCleaner.loginCheckSource()
    );
    List<SourceRecord> result = SourceCleaner.cleanLogin(all);
    assertEquals(2, result.size());
    for (SourceRecord s : result) {
      assertFalse(SourceCleaner.needsLogin(s));
    }
  }

  @Test public void cleanCaptchaFiltersCaptchaSources() {
    List<SourceRecord> all = Arrays.asList(
        SourceCleaner.normalSource(),
        SourceCleaner.captchaSource(),
        SourceCleaner.verifyCodeSource(),
        SourceCleaner.authUrlSource()
    );
    List<SourceRecord> result = SourceCleaner.cleanCaptcha(all);
    assertEquals(1, result.size());
    assertFalse(SourceCleaner.hasCaptcha(result.get(0)));
  }

  @Test public void cleanLoginNullHandling() {
    assertTrue(SourceCleaner.cleanLogin(null).isEmpty());
  }

  @Test public void cleanCaptchaNullHandling() {
    assertTrue(SourceCleaner.cleanCaptcha(null).isEmpty());
  }

  @Test public void blankLoginAndCaptchaFieldsDoNotCauseFalseFiltering() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "正常源");
    raw.put("bookSourceUrl", "https://example.com");
    raw.put("loginUrl", " ");
    raw.put("captchaUrl", "");
    SourceRecord source = new SourceRecord(0, "正常源", "https://example.com", raw);

    assertFalse(SourceCleaner.needsLogin(source));
    assertFalse(SourceCleaner.hasCaptcha(source));
    assertEquals(1, SourceCleaner.cleanLogin(Arrays.asList(source)).size());
    assertEquals(1, SourceCleaner.cleanCaptcha(Arrays.asList(source)).size());
  }
}
