package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.InvalidSource;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** URL 模式去重的无效源统计回归测试（曾因 buildResult 新建空列表导致无效源凭空消失）。 */
public class DedupeEngineTest {

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }

  @Test public void urlModesReportMissingEmptyAndInvalidUrls() {
    for (DedupeMode mode : new DedupeMode[]{DedupeMode.STANDARD, DedupeMode.STRICT, DedupeMode.AGGRESSIVE}) {
      List<SourceRecord> sources = Arrays.asList(
          source(0, "正常", "https://a.com"),
          source(1, "缺URL", null),
          source(2, "空URL", "   "),
          source(3, "非法URL", "not a url"));
      DedupeResult r = DedupeEngine.run(sources, mode, false);
      assertEquals(mode.name(), 3, r.getInvalid().size());
      assertEquals(mode.name(), 1, r.getRetained().size());
      // 计数守恒：原始 = 保留 + 重复 + 无效
      assertEquals(mode.name(), r.getOriginalCount(),
          r.getRetained().size() + r.getDuplicateCount() + r.getInvalid().size());
      boolean hasMissing = false, hasEmpty = false, hasInvalid = false;
      for (InvalidSource inv : r.getInvalid()) {
        if (inv.getKind() == InvalidSource.Kind.MISSING_URL) hasMissing = true;
        if (inv.getKind() == InvalidSource.Kind.EMPTY_URL) hasEmpty = true;
        if (inv.getKind() == InvalidSource.Kind.INVALID_URL) hasInvalid = true;
      }
      assertTrue(mode.name(), hasMissing && hasEmpty && hasInvalid);
    }
  }

  @Test public void duplicateUrlsStillGroupedAfterInvalidFix() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "A", "https://a.com"),
        source(1, "B", "https://a.com/"),
        source(2, "缺URL", null));
    DedupeResult r = DedupeEngine.run(sources, DedupeMode.STANDARD, false);
    assertEquals(1, r.getRetained().size());
    assertEquals(1, r.getDuplicateGroups().size());
    assertEquals(1, r.getInvalid().size());
  }

  @Test public void recencyBonusFavorsRecentlyUpdatedSource() {
    long now = System.currentTimeMillis();
    List<SourceRecord> sources = Arrays.asList(
        source(0, "A", "https://a.com"),
        source(1, "B", "https://a.com"));
    // 用 raw 注入 lastUpdateTime：新近更新的源应排在前面（旧公式任何时间戳都恒 +50，无区分度）
    Map<String, Object> rawNew = new LinkedHashMap<>(sources.get(0).getRaw());
    rawNew.put("lastUpdateTime", now);
    sources.set(0, new SourceRecord(0, "A", "https://a.com", rawNew));
    Map<String, Object> rawOld = new LinkedHashMap<>(sources.get(1).getRaw());
    rawOld.put("lastUpdateTime", now - 400L * 365L * 86_400_000L); // 很老
    sources.set(1, new SourceRecord(1, "B", "https://a.com", rawOld));
    DedupeResult r = DedupeEngine.run(sources, DedupeMode.STANDARD, false);
    assertEquals("A", r.getRetained().get(0).getName());
    assertTrue(DedupeEngine.recencyBonus(now) > DedupeEngine.recencyBonus(now - 400L * 365L * 86_400_000L));
  }
}
