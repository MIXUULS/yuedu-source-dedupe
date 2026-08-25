package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 校验前的静态规则诊断；仅提示，不会删除或判定书源失效。 */
public final class RuleCompleteness {
  private RuleCompleteness() {}

  public static List<String> missingParts(SourceRecord source) {
    if (source == null) return Collections.singletonList("书源数据为空");
    List<String> out = new ArrayList<>();
    Map<String, Object> raw = source.getRaw();
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
