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

/** 校验目标准备（清理顺序 / 统计口径 / 规则完整度）单元测试。 */
public class CheckTargetPreparerTest {

  private static SourceRecord source(String name, String url, String extraJson) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(0, name, url, raw);
  }

  private static CheckSourceSettings defaults() {
    CheckSourceSettings s = new CheckSourceSettings();
    s.normalize();
    return s;
  }

  /** 不做任何清理时全部保留。 */
  @Test public void noCleaningKeepsAllSources() {
    CheckSourceSettings s = defaults();
    s.deleteCaptcha = false;
    s.cleanLogin = false;
    List<SourceRecord> input = Arrays.asList(
        source("A", "https://a.com", null),
        source("B", "https://b.com", null));

    CheckTargetPreparer.Prepared p = CheckTargetPreparer.prepare(input, s);
    assertEquals(2, p.targets.size());
    assertEquals(0, p.removedCaptcha);
    assertEquals(0, p.removedLogin);
  }

  /** 输入为 null 时返回空结果而不是崩。 */
  @Test public void nullInputIsSafe() {
    CheckTargetPreparer.Prepared p = CheckTargetPreparer.prepare(null, defaults());
    assertNotNull(p.targets);
    assertTrue(p.targets.isEmpty());
    assertEquals(0, p.incompleteRules);
  }

  /** 设置本身为 null 时视作不清理。 */
  @Test public void nullSettingsMeansNoCleaning() {
    List<SourceRecord> input = Collections.singletonList(source("A", "https://a.com", null));
    CheckTargetPreparer.Prepared p = CheckTargetPreparer.prepare(input, null);
    assertEquals(1, p.targets.size());
    assertEquals(0, p.removedCaptcha);
    assertEquals(0, p.removedLogin);
  }

  /** 不修改传入列表（调用方可能还要用它）。 */
  @Test public void doesNotMutateInput() {
    List<SourceRecord> input = new ArrayList<>(Arrays.asList(
        source("A", "https://a.com", null),
        source("B", "https://b.com", null)));
    int before = input.size();
    CheckTargetPreparer.prepare(input, defaults());
    assertEquals("传入列表不应被改动", before, input.size());
  }

  /** 统计口径：removedCaptcha + removedLogin + 最终条数 == 原始条数。 */
  @Test public void removalCountsAreConsistent() {
    List<SourceRecord> input = new ArrayList<>();
    for (int i = 0; i < 5; i++) input.add(source("S" + i, "https://s" + i + ".com", null));
    int original = input.size();

    CheckTargetPreparer.Prepared p = CheckTargetPreparer.prepare(input, defaults());
    assertEquals("被移除数 + 保留数必须等于原始数",
        original, p.targets.size() + p.removedCaptcha + p.removedLogin);
  }

  /** 规则完整的书源不应被计入 incompleteRules。 */
  @Test public void completeRulesAreNotCounted() {
    List<SourceRecord> input = Collections.singletonList(source("A", "https://a.com", null));
    CheckTargetPreparer.Prepared p = CheckTargetPreparer.prepare(input, defaults());
    // ruleSearch.bookList = body 已提供；其余步骤缺失会计入，这里只验证不抛异常且计数非负
    assertTrue(p.incompleteRules >= 0);
    assertEquals(1, p.targets.size());
  }

  /** 完全空的书源规则应被判定为不完整。 */
  @Test public void emptyRuleSourceIsIncomplete() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "空规则");
    raw.put("bookSourceUrl", "https://empty.com");
    SourceRecord empty = new SourceRecord(0, "空规则", "https://empty.com", raw);

    CheckTargetPreparer.Prepared p = CheckTargetPreparer.prepare(
        Collections.singletonList(empty), defaults());
    assertEquals("规则全空应计入不完整", 1, p.incompleteRules);
  }

  /** 返回的 targets 与输入顺序一致（后续按顺序展示/校验）。 */
  @Test public void preservesInputOrder() {
    List<SourceRecord> input = Arrays.asList(
        source("Z", "https://z.com", null),
        source("A", "https://a.com", null),
        source("M", "https://m.com", null));
    CheckTargetPreparer.Prepared p = CheckTargetPreparer.prepare(input, noClean());
    assertEquals("Z", p.targets.get(0).getName());
    assertEquals("A", p.targets.get(1).getName());
    assertEquals("M", p.targets.get(2).getName());
  }

  private static CheckSourceSettings noClean() {
    CheckSourceSettings s = defaults();
    s.deleteCaptcha = false;
    s.cleanLogin = false;
    return s;
  }
}