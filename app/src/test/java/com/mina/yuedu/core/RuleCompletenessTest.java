package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuleCompletenessTest {
  @Test public void reportsOnlyMissingRequiredParts() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("searchUrl", "https://example.com/search?q={{key}}");
    raw.put("ruleSearch", "body");
    raw.put("ruleBookInfo", "body");
    raw.put("ruleToc", "body");
    raw.put("ruleContent", "body");
    SourceRecord complete = new SourceRecord(0, "完整", "https://example.com", raw);
    assertTrue(RuleCompleteness.isComplete(complete));

    raw.remove("ruleToc");
    raw.put("ruleContent", " ");
    SourceRecord incomplete = new SourceRecord(1, "缺失", "https://example.org", raw);
    assertEquals(2, RuleCompleteness.missingParts(incomplete).size());
    assertTrue(RuleCompleteness.missingParts(incomplete).contains("目录规则"));
    assertTrue(RuleCompleteness.missingParts(incomplete).contains("正文规则"));
  }
}
