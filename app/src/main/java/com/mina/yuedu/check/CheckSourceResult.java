package com.mina.yuedu.check;
import com.mina.yuedu.model.SourceRecord;
import java.util.*;
public final class CheckSourceResult {
  public enum Status { SUCCESS, TIMEOUT, FAILED, SKIPPED }
  public final SourceRecord source;
  public final Status status;
  public final List<String> groups;
  public final String message;
  public final long respondTimeMs;
  public final SourceKind kind;
  /** 该源校验通过的步骤（"搜索"、"发现"、"详情"、"目录"、"正文"），用于合并同域校验结果。 */
  public final Set<String> succeededSteps;
  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message, long respondTimeMs) {
    this(source, status, groups, message, respondTimeMs, SourceKind.of(source), Collections.<String>emptySet());
  }
  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message, long respondTimeMs, SourceKind kind) {
    this(source, status, groups, message, respondTimeMs, kind, Collections.<String>emptySet());
  }
  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message, long respondTimeMs, SourceKind kind, Set<String> succeededSteps) {
    this.source = source; this.status = status;
    this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
    this.message = message; this.respondTimeMs = respondTimeMs;
    this.kind = kind == null ? SourceKind.NOVEL : kind;
    this.succeededSteps = Collections.unmodifiableSet(new LinkedHashSet<>(succeededSteps));
  }
  public boolean isUsable() { return status == Status.SUCCESS; }
}
