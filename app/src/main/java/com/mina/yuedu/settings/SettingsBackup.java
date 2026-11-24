package com.mina.yuedu.settings;

import com.mina.yuedu.check.CheckSourceSettings;
import com.mina.yuedu.core.MiniJson;
import com.mina.yuedu.model.DedupeMode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置一键备份 / 还原的数据层。
 *
 * 从 MainActivity 抽出来的原因：这里有 20 多个字段的映射，漏一个就是静默丢配置；
 * 而且还原逻辑原来是"边读边写 SharedPreferences"，中途类型不匹配抛异常时，
 * 前面已经写进去的设置会留在半成功状态。抽成「先完整解析成快照，再一次性写回」就没有这个问题。
 */
public final class SettingsBackup {

  public static final int VERSION = 1;

  /** 一份完整的设置快照。字段默认值与界面首次启动的默认值保持一致。 */
  public static final class Snapshot {
    public String mode = DedupeMode.STANDARD.name();
    public boolean clean = false;
    public boolean onlyUsable = false;
    public int concurrency = 4;

    public int timeout = CheckSourceSettings.DEFAULT_TIMEOUT;
    public int checkConcurrency = CheckSourceSettings.DEFAULT_CONCURRENCY;
    public String keyword = CheckSourceSettings.DEFAULT_KEYWORD;
    public String okStatus = CheckSourceSettings.DEFAULT_OK_STATUS;
    public boolean quickMode = false;

    public boolean search = true, discovery = true, info = true, category = true, content = true;
    public boolean kindNovel = true, kindComic = true, kindVideo = true, kindAudio = true, kindFile = true;
  }

  private SettingsBackup() {}

  /** 序列化成备份 JSON。 */
  public static String toJson(Snapshot s) {
    return MiniJson.stringify(toMap(s));
  }

  /** 序列化成 Map（便于测试直接断言字段）。 */
  public static Map<String, Object> toMap(Snapshot s) {
    Map<String, Object> cfg = new LinkedHashMap<>();
    cfg.put("version", VERSION);
    cfg.put("mode", s.mode);
    cfg.put("clean", s.clean);
    cfg.put("onlyUsable", s.onlyUsable);
    cfg.put("concurrency", s.concurrency);
    cfg.put("timeout", s.timeout);
    cfg.put("checkConcurrency", s.checkConcurrency);
    cfg.put("keyword", s.keyword);
    cfg.put("okStatus", s.okStatus);
    cfg.put("quickMode", s.quickMode);
    Map<String, Object> steps = new LinkedHashMap<>();
    steps.put("search", s.search);
    steps.put("discovery", s.discovery);
    steps.put("info", s.info);
    steps.put("category", s.category);
    steps.put("content", s.content);
    cfg.put("checkSteps", steps);
    Map<String, Object> kinds = new LinkedHashMap<>();
    kinds.put("novel", s.kindNovel);
    kinds.put("comic", s.kindComic);
    kinds.put("video", s.kindVideo);
    kinds.put("audio", s.kindAudio);
    kinds.put("file", s.kindFile);
    cfg.put("checkKinds", kinds);
    return cfg;
  }

  /**
   * 解析备份 JSON。
   *
   * @throws IllegalArgumentException 内容不是本工具的备份文件（调用方据此提示用户）
   */
  public static Snapshot parse(String text) {
    Object root;
    try {
      root = MiniJson.parse(text);
    } catch (Exception e) {
      throw new IllegalArgumentException("设置文件解析失败：" + e.getMessage());
    }
    if (!(root instanceof Map)) throw new IllegalArgumentException("设置文件格式不正确");
    Map<?, ?> m = (Map<?, ?>) root;
    if (m.get("version") == null) throw new IllegalArgumentException("不是本工具的设置备份文件");

    Snapshot s = new Snapshot();
    if (m.get("mode") != null) s.mode = String.valueOf(m.get("mode"));
    if (m.get("clean") != null) s.clean = toBool(m.get("clean"), s.clean);
    if (m.get("onlyUsable") != null) s.onlyUsable = toBool(m.get("onlyUsable"), s.onlyUsable);
    s.concurrency = toInt(m.get("concurrency"), s.concurrency);

    s.timeout = toInt(m.get("timeout"), s.timeout);
    s.checkConcurrency = toInt(m.get("checkConcurrency"), s.checkConcurrency);
    if (m.get("keyword") != null) s.keyword = String.valueOf(m.get("keyword"));
    if (m.get("okStatus") != null) s.okStatus = String.valueOf(m.get("okStatus"));
    if (m.get("quickMode") != null) s.quickMode = toBool(m.get("quickMode"), s.quickMode);

    Object steps = m.get("checkSteps");
    if (steps instanceof Map) {
      Map<?, ?> sm = (Map<?, ?>) steps;
      if (sm.get("search") != null) s.search = toBool(sm.get("search"), s.search);
      if (sm.get("discovery") != null) s.discovery = toBool(sm.get("discovery"), s.discovery);
      if (sm.get("info") != null) s.info = toBool(sm.get("info"), s.info);
      if (sm.get("category") != null) s.category = toBool(sm.get("category"), s.category);
      if (sm.get("content") != null) s.content = toBool(sm.get("content"), s.content);
    }
    Object kinds = m.get("checkKinds");
    if (kinds instanceof Map) {
      Map<?, ?> km = (Map<?, ?>) kinds;
      if (km.get("novel") != null) s.kindNovel = toBool(km.get("novel"), s.kindNovel);
      if (km.get("comic") != null) s.kindComic = toBool(km.get("comic"), s.kindComic);
      if (km.get("video") != null) s.kindVideo = toBool(km.get("video"), s.kindVideo);
      if (km.get("audio") != null) s.kindAudio = toBool(km.get("audio"), s.kindAudio);
      if (km.get("file") != null) s.kindFile = toBool(km.get("file"), s.kindFile);
    }
    return s;
  }

  /**
   * 宽松取整数：备份文件常被用户手工编辑，值可能是字符串 "4"。
   * 原来直接强转 {@code (Number)} 会抛 ClassCastException，把整次还原打断在半路。
   */
  static int toInt(Object o, int def) {
    if (o instanceof Number) return ((Number) o).intValue();
    if (o == null) return def;
    try {
      return Integer.parseInt(String.valueOf(o).trim());
    } catch (Exception e) {
      return def;
    }
  }

  /** 宽松取布尔：接受 true/false、1/0、"true"/"false"。 */
  static boolean toBool(Object o, boolean def) {
    if (o instanceof Boolean) return (Boolean) o;
    if (o instanceof Number) return ((Number) o).intValue() != 0;
    if (o == null) return def;
    String s = String.valueOf(o).trim();
    if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
    if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
    return def;
  }
}