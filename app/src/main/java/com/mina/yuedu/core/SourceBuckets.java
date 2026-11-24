package com.mina.yuedu.core;
import com.mina.yuedu.model.SourceRecord;
import java.util.*;
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
}
