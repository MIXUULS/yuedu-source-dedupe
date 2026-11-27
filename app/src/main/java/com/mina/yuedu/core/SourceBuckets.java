package com.mina.yuedu.core;
import com.mina.yuedu.model.SourceRecord;
import java.util.*;

/**
 * 本地与网络两批书源的容器。
 *
 * 移除操作必须同时作用在这两个列表上（{@link #removeByUrls}），
 * 否则「移除死站后重新去重，被移除的源又回来」——
 * 因为 recompute 每次都从 {@link #all()} 重新算。
 */
public final class SourceBuckets {
  private final List<SourceRecord> local=new ArrayList<>(), network=new ArrayList<>();
  public void addLocal(List<SourceRecord> records){ local.addAll(records); }
  public void replaceNetwork(List<SourceRecord> records){ network.clear(); network.addAll(records); }
  public void addNetwork(List<SourceRecord> records){ network.addAll(records); }
  public boolean isEmpty(){ return local.isEmpty() && network.isEmpty(); }
  public int localCount(){ return local.size(); } public int networkCount(){ return network.size(); }
  public List<SourceRecord> all(){ List<SourceRecord> out=new ArrayList<>(local.size()+network.size()); out.addAll(local); out.addAll(network); return out; }
  /** 只读快照，供配置变更时保留数据；返回新列表，调用方改动不影响内部状态。 */
  public List<SourceRecord> localSnapshot(){ return new ArrayList<>(local); }
  public List<SourceRecord> networkSnapshot(){ return new ArrayList<>(network); }
  public void clearAll(){ local.clear(); network.clear(); }

  /**
   * 按 URL 从两批书源里移除，返回实际移除条数。
   *
   * 为什么要有这个方法：界面上的「移除选中」「移除站点已死的源」「合并重定向冗余」
   * 原来只改 result / checkResults，没动 buckets。于是用户点「重新去重」时，
   * recompute 又从 buckets.all() 全量算一遍，被移除的源全部复活。
   * 只能靠 toast 提示「可从「解析网络源」重新去重」来掩盖——但那正是会复活的路径。
   *
   * @param urls 要移除的 URL 集合；null 或空时不动
   */
  public int removeByUrls(Set<String> urls){
    if(urls == null || urls.isEmpty()) return 0;
    int removed = 0;
    removed += removeFrom(local, urls);
    removed += removeFrom(network, urls);
    return removed;
  }

  private static int removeFrom(List<SourceRecord> list, Set<String> urls){
    int before = list.size();
    // 用 iterator 显式删除：removeIf 在 API 24 上要 desugar，这里更稳
    Iterator<SourceRecord> it = list.iterator();
    while(it.hasNext()){
      SourceRecord s = it.next();
      if(s != null && s.getUrl() != null && urls.contains(s.getUrl())) it.remove();
    }
    return before - list.size();
  }
}