package com.mina.yuedu.model;
public enum DedupeMode {
  STANDARD("标准", "规范化完整 URL，保留 #身份标签并剥离常见跟踪参数；同域不同路径保留。"),
  STRICT("严格", "规范化完整 URL，保留全部 query 与 #身份标签；最接近阅读中的原始身份。"),
  AGGRESSIVE("激进", "同一域名只保留一个书源；去重最多，也最可能误删。"),
  NAME("名称", "按名称相似度合并（Levenshtein 距离 + 关键词归一化）；适合合并同名镜像站，URL 不同但名称相似的同站源。");
  private final String label, description;
  DedupeMode(String label, String description){this.label=label;this.description=description;}
  public String label(){return label;} public String description(){return description;}
}
