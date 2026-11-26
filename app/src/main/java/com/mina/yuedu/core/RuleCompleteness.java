package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 校验前的静态规则诊断；仅提示，不会删除或判定书源失效。
 *
 * v2 老格式（占真实书源的 73.5%）字段名不同，这里先经 {@link V2FieldAdapter}
 * 适配成 v3 再判断，否则会把起点、创世、晋江这类主流源全报成"规则缺失"。
 */
public final class RuleCompleteness {
  private RuleCompleteness() {}

  public static List<String> missingParts(SourceRecord source) {
    if (source == null) return Collections.singletonList("书源数据为空");
    // v2 字段先补成 v3
    SourceRecord s = V2FieldAdapter.adapt(source);
    List<String> out = new ArrayList<>();
    Map<String, Object> raw = s.getRaw();
    if (!hasText(raw.get("searchUrl"))) out.add("搜索链接");
    if (!hasRule(raw.get("ruleSearch"))) out.add("搜索规则");
    if (!hasRule(raw.get("ruleBookInfo"))) out.add("详情规则");
    if (!hasRule(raw.get("ruleToc"))) out.add("目录规则");
    if (!hasRule(raw.get("ruleContent"))) out.add("正文规则");
    return out;
  }

  public static boolean isComplete(SourceRecord source) { return missingParts(source).isEmpty(); }

  private static boolean hasText(Object value) {
    return value != null && !String.valueOf(value).trim().isEmpty();
  }

  private static boolean hasRule(Object value) {
    if (value instanceof Map) return !((Map<?, ?>) value).isEmpty();
    return hasText(value);
  }
}
