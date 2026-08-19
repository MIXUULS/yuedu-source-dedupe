package com.mina.yuedu.model;
import java.util.*;
public final class SourceRecord {
  private final int order; private final String name,url; private final Map<String,Object> raw;
  public SourceRecord(int order,String name,String url,Map<String,Object> raw){
    this.order=order; this.name=name; this.url=url;
    // 不再复制 raw：调用方均传入独立 map（parse 树节点 / 新建 map），且外部只能读（unmodifiableMap 兜底）。
    // 万级书源时避免每记录复制一份 LinkedHashMap，显著降低内存峰值（修复大包 OOM）。
    this.raw=Collections.unmodifiableMap(raw);
  }
  public int getOrder(){return order;} public String getName(){return name;} public String getUrl(){return url;} public Map<String,Object> getRaw(){return raw;}
  public SourceRecord withName(String n){Map<String,Object> m=new LinkedHashMap<>(raw);m.put("bookSourceName",n);return new SourceRecord(order,n,url,m);}
  /** 返回 order 改变、其余字段相同的副本（共享不可变 raw，不复制 map；用于在加锁区间内统一分配顺序号）。 */
  public SourceRecord withOrder(int o){ return new SourceRecord(o, name, url, raw); }
  @SuppressWarnings("unchecked") public Map<String,Object> ruleMap(String key){
    Object v=raw.get(key); if(v instanceof Map) return (Map<String,Object>)v; return Collections.emptyMap();
  }
  public String rawString(String key){Object v=raw.get(key); return v==null?null:String.valueOf(v);}
}
