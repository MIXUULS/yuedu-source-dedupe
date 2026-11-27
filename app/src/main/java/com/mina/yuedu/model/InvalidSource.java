package com.mina.yuedu.model;
public final class InvalidSource {
  /**
   * EMPTY_SHELL：搜索链路与发现链路同时无效的空壳书源（Issue #1 二.1/2.2）。
   * 用户在阅读里打开这种源什么也搜不到，导出去也没用。
   */
  public enum Kind { NOT_OBJECT,MISSING_URL,EMPTY_URL,INVALID_URL,NOT_JSON_ARRAY,NETWORK_FAILURE,NO_YCK_JSON,EMPTY_SHELL }
  private final Kind kind; private final String detail;
  public InvalidSource(Kind kind,String detail){this.kind=kind;this.detail=detail;}
  public Kind getKind(){return kind;} public String getDetail(){return detail;}
}
