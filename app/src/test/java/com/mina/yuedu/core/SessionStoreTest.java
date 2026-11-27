package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 会话持久化测试。
 *
 * 关键要求：
 *  1. 书源与校验结果能完整往返
 *  2. 校验结果必须能找回对应的书源对象（否则 checkResults 里 source 为 null）
 *  3. 任何损坏/不兼容的文件都不能让应用起不来
 */
public class SessionStoreTest {

  private File tmpDir;
  private File file;

  @Before public void setUp() throws Exception {
    tmpDir = new File(System.getProperty("java.io.tmpdir"),
        "session-test-" + System.nanoTime());
    assertTrue(tmpDir.mkdirs());
    file = new File(tmpDir, "session.gz");
  }

  @After public void tearDown() {
    if (tmpDir != null) {
      File[] fs = tmpDir.listFiles();
      if (fs != null) for (File f : fs) f.delete();
      tmpDir.delete();
    }
  }

  private static SourceRecord src(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("bookSourceType", 0);
    raw.put("searchUrl", url + "/s?q={{key}}");
    Map<String, Object> rs = new LinkedHashMap<>();
    rs.put("bookList", "body");
    raw.put("ruleSearch", rs);
    return new SourceRecord(order, name, url, raw);
  }

  // ---------- 往返 ----------

  @Test public void savesAndLoadsSources() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A站", "https://a.com"),
        src(1, "B站", "https://b.com"));

    assertTrue(SessionStore.save(file, sources, null, "STANDARD"));
    assertTrue("文件应存在", file.isFile());

    SessionStore.Snapshot snap = SessionStore.load(file);
    assertNotNull(snap);
    assertEquals(2, snap.sources.size());
    assertEquals("A站", snap.sources.get(0).getName());
    assertEquals("https://a.com", snap.sources.get(0).getUrl());
    assertEquals(1, snap.sources.get(1).getOrder());
    assertEquals("STANDARD", snap.mode);
    assertTrue(snap.savedAt > 0);
  }

  /** 原始字段必须完整保留（否则导出的书源会丢规则）。 */
  @Test public void preservesRawFields() {
    SessionStore.save(file, Collections.singletonList(src(0, "A", "https://a.com")), null, "STANDARD");
    SessionStore.Snapshot snap = SessionStore.load(file);

    Map<String, Object> raw = snap.sources.get(0).getRaw();
    assertEquals("https://a.com/s?q={{key}}", raw.get("searchUrl"));
    assertNotNull("ruleSearch 应保留", raw.get("ruleSearch"));
    assertFalse("内部字段 __order 不应泄漏", raw.containsKey("__order"));
  }

  // ---------- 校验结果 ----------

  @Test public void savesAndLoadsCheckResults() {
    List<SourceRecord> sources = Arrays.asList(
        src(0, "A", "https://a.com"), src(1, "B", "https://b.com"));
    List<CheckSourceResult> checks = Arrays.asList(
        new CheckSourceResult(sources.get(0), CheckSourceResult.Status.SUCCESS,
            Collections.<String>emptyList(), "校验成功", 120, SourceKind.NOVEL),
        new CheckSourceResult(sources.get(1), CheckSourceResult.Status.FAILED,
            Arrays.asList("站点在线但连接异常", "搜索失效"), "失败", 8000, SourceKind.NOVEL));

    assertTrue(SessionStore.save(file, sources, checks, "STRICT"));
    SessionStore.Snapshot snap = SessionStore.load(file);

    assertEquals(2, snap.checkResults.size());
    CheckSourceResult first = snap.checkResults.get(0);
    assertEquals(CheckSourceResult.Status.SUCCESS, first.status);
    assertEquals(120, first.respondTimeMs);
    assertNotNull("必须找回对应的书源对象", first.source);
    assertEquals("https://a.com", first.source.getUrl());

    CheckSourceResult second = snap.checkResults.get(1);
    assertEquals(CheckSourceResult.Status.FAILED, second.status);
    assertEquals(2, second.groups.size());
    assertEquals("站点在线但连接异常", second.groups.get(0));
  }

  /** 多步成功记录也要保留。 */
  @Test public void preservesSucceededSteps() {
    SourceRecord s = src(0, "A", "https://a.com");
    java.util.Set<String> steps = new java.util.LinkedHashSet<>(Arrays.asList("搜索", "详情"));
    List<CheckSourceResult> checks = Collections.singletonList(
        new CheckSourceResult(s, CheckSourceResult.Status.SUCCESS,
            Collections.<String>emptyList(), "ok", 100, SourceKind.NOVEL, steps));

    SessionStore.save(file, Collections.singletonList(s), checks, "STANDARD");
    SessionStore.Snapshot snap = SessionStore.load(file);
    assertEquals(2, snap.checkResults.get(0).succeededSteps.size());
    assertTrue(snap.checkResults.get(0).succeededSteps.contains("搜索"));
  }

  /** 书源已被移除时，对应校验结果丢弃（否则 source 会是 null）。 */
  @Test public void dropsCheckResultsWhoseSourceIsGone() {
    List<SourceRecord> sources = Collections.singletonList(src(0, "A", "https://a.com"));
    // 校验结果里有一条书源不在 sources 里
    SourceRecord orphan = src(9, "已删", "https://deleted.com");
    List<CheckSourceResult> checks = Arrays.asList(
        new CheckSourceResult(sources.get(0), CheckSourceResult.Status.SUCCESS,
            Collections.<String>emptyList(), "ok", 100, SourceKind.NOVEL),
        new CheckSourceResult(orphan, CheckSourceResult.Status.FAILED,
            Collections.singletonList("x"), "fail", 200, SourceKind.NOVEL));

    SessionStore.save(file, sources, checks, "STANDARD");
    SessionStore.Snapshot snap = SessionStore.load(file);
    assertEquals("孤儿校验结果应被丢弃", 1, snap.checkResults.size());
    assertNotNull(snap.checkResults.get(0).source);
  }

  // ---------- 边界与容错 ----------

  /** 空数据应删除旧文件，避免恢复出过时内容。 */
  @Test public void emptySaveDeletesOldFile() {
    SessionStore.save(file, Collections.singletonList(src(0, "A", "https://a.com")), null, "STANDARD");
    assertTrue(file.isFile());

    assertTrue(SessionStore.save(file, Collections.<SourceRecord>emptyList(),
        Collections.<CheckSourceResult>emptyList(), "STANDARD"));
    assertFalse("旧文件应被删除", file.isFile());
  }

  @Test public void loadMissingFileReturnsNull() {
    assertNull(SessionStore.load(new File(tmpDir, "nonexistent.gz")));
    assertNull(SessionStore.load(null));
  }

  /** 损坏的文件不能让应用起不来。 */
  @Test public void corruptFileReturnsNull() throws Exception {
    java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
    fos.write("this is not gzip".getBytes("UTF-8"));
    fos.close();
    assertNull("损坏文件应返回 null 而非抛异常", SessionStore.load(file));
  }

  /** 空文件返回 null。 */
  @Test public void emptyFileReturnsNull() throws Exception {
    assertTrue(file.createNewFile());
    assertNull(SessionStore.load(file));
  }

  @Test public void saveToNullFileReturnsFalse() {
    assertFalse(SessionStore.save(null, Collections.singletonList(src(0, "A", "https://a.com")),
        null, "STANDARD"));
  }

  /** 往返后数据量一致（模拟 2257 条规模的缩小版）。 */
  @Test public void roundTripManySources() {
    List<SourceRecord> sources = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      sources.add(src(i, "源" + i, "https://site" + i + ".com"));
    }
    List<CheckSourceResult> checks = new ArrayList<>();
    for (SourceRecord s : sources) {
      checks.add(new CheckSourceResult(s,
          s.getOrder() % 2 == 0 ? CheckSourceResult.Status.SUCCESS : CheckSourceResult.Status.FAILED,
          Collections.<String>emptyList(), "msg", 100, SourceKind.NOVEL));
    }

    assertTrue(SessionStore.save(file, sources, checks, "STANDARD"));
    SessionStore.Snapshot snap = SessionStore.load(file);
    assertEquals(200, snap.sources.size());
    assertEquals(200, snap.checkResults.size());
    // 抽查顺序与内容
    assertEquals("源0", snap.sources.get(0).getName());
    assertEquals("源199", snap.sources.get(199).getName());
    assertEquals(CheckSourceResult.Status.SUCCESS, snap.checkResults.get(0).status);
    assertEquals(CheckSourceResult.Status.FAILED, snap.checkResults.get(1).status);
  }

  /** 压缩后应显著小于原始 JSON（内存考量的一部分）。 */
  @Test public void fileIsCompressed() {
    List<SourceRecord> sources = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      Map<String, Object> raw = new LinkedHashMap<>();
      raw.put("bookSourceName", "源" + i);
      raw.put("bookSourceUrl", "https://site" + i + ".com");
      raw.put("searchUrl", "https://site" + i + ".com/s?q={{key}}&page={{page}}");
      StringBuilder big = new StringBuilder();
      for (int j = 0; j < 20; j++) big.append("class.rule").append(j).append("@tag.a@href##$##,{};");
      raw.put("ruleToc", big.toString());
      sources.add(new SourceRecord(i, "源" + i, "https://site" + i + ".com", raw));
    }

    assertTrue(SessionStore.save(file, sources, null, "STANDARD"));
    long gzSize = file.length();
    // GZIP 对这种重复性强的文本压缩率很高
    assertTrue("压缩后应小于 200KB，实际 " + gzSize, gzSize < 200 * 1024);
    assertNotNull(SessionStore.load(file));
  }
}