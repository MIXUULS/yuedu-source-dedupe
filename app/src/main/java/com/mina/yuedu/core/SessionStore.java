package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 会话持久化：把书源与校验结果写到应用私有目录，下次启动恢复。
 *
 * <h3>为什么要做</h3>
 * 现状（v3.7.4 之前）：所有数据只在内存。用户导入 2257 条书源、跑 25 分钟校验后，
 * 只要应用被系统杀掉或重装，全部归零，得重新导入并重跑一遍。
 *
 * <h3>内存考量（关键设计约束）</h3>
 * <pre>
 *   书源对象 2257 条 ≈ 5.5 MB，校验结果 ≈ 1.1 MB——这些**本来就在内存里**，
 *   持久化不会让常驻内存翻倍，只是多一份磁盘副本。
 *
 *   真正要避免的是「反序列化时的峰值」：
 *   现有导入路径是 ByteArrayOutputStream 收全部字节 → toString() → 解析，
 *   12 MB 文件会瞬间产生 12 MB 字节数组 + 24 MB UTF-16 字符串 + 解析后的对象。
 *
 *   这里用 GZIP + 流式写出，压缩后典型 2-3 MB，读时也先解压到内存（不可避免，
 *   MiniJson 需要完整字符串），但压缩使这块从 12 MB 降到 2-3 MB。
 * </pre>
 *
 * <h3>失败处理</h3>
 * 会话文件是可选的加速器：读失败就当没有，绝不让应用因为恢复失败而起不来。
 */
public final class SessionStore {

  /** 当前格式版本；不兼容时直接丢弃旧文件。 */
  private static final int VERSION = 1;
  /** 会话文件最大允许字节数，超过视为损坏。 */
  private static final long MAX_BYTES = 64L * 1024 * 1024;

  /** 恢复出来的会话数据。 */
  public static final class Snapshot {
    public final List<SourceRecord> sources;
    public final List<CheckSourceResult> checkResults;
    public final String mode;
    public final long savedAt;

    Snapshot(List<SourceRecord> sources, List<CheckSourceResult> checkResults,
             String mode, long savedAt) {
      this.sources = sources;
      this.checkResults = checkResults;
      this.mode = mode;
      this.savedAt = savedAt;
    }

    public boolean isEmpty() {
      return (sources == null || sources.isEmpty())
          && (checkResults == null || checkResults.isEmpty());
    }
  }

  private SessionStore() {}

  /**
   * 保存会话。必须在后台线程调用（有磁盘 IO）。
   *
   * @param file        目标文件
   * @param sources     书源（去重后的 retained，或全量）
   * @param checkResults 校验结果，可为空
   * @param mode        当前去重模式名
   * @return 是否成功
   */
  public static boolean save(File file, List<SourceRecord> sources,
                             List<CheckSourceResult> checkResults, String mode) {
    if (file == null) return false;
    if ((sources == null || sources.isEmpty())
        && (checkResults == null || checkResults.isEmpty())) {
      // 没数据就删掉旧文件，避免恢复出过时内容
      //noinspection ResultOfMethodCallIgnored
      file.delete();
      return true;
    }

    File tmp = new File(file.getAbsolutePath() + ".tmp");
    try (FileOutputStream fos = new FileOutputStream(tmp);
         BufferedOutputStream bos = new BufferedOutputStream(fos, 64 * 1024);
         GZIPOutputStream gz = new GZIPOutputStream(bos, 64 * 1024)) {

      writeLine(gz, "v=" + VERSION);
      writeLine(gz, "mode=" + (mode == null ? "" : mode));
      writeLine(gz, "at=" + System.currentTimeMillis());
      writeLine(gz, "sources=" + (sources == null ? 0 : sources.size()));
      writeLine(gz, "checks=" + (checkResults == null ? 0 : checkResults.size()));

      if (sources != null) {
        for (SourceRecord s : sources) {
          gz.write(MiniJson.stringify(sourceToMap(s)).getBytes(StandardCharsets.UTF_8));
          gz.write('\n');
        }
      }
      if (checkResults != null) {
        for (CheckSourceResult r : checkResults) {
          gz.write(MiniJson.stringify(checkToMap(r)).getBytes(StandardCharsets.UTF_8));
          gz.write('\n');
        }
      }
    } catch (Exception e) {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
      return false;
    }

    // 原子替换：先写 tmp 再改名，避免写一半被杀留下坏文件
    if (file.exists() && !file.delete()) {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
      return false;
    }
    return tmp.renameTo(file);
  }

  /**
   * 读取会话。失败返回 null（调用方当没有会话处理）。
   */
  public static Snapshot load(File file) {
    if (file == null || !file.isFile() || file.length() <= 0) return null;
    if (file.length() > MAX_BYTES) return null;

    try (FileInputStream fis = new FileInputStream(file);
         BufferedInputStream bis = new BufferedInputStream(fis, 64 * 1024);
         GZIPInputStream gz = new GZIPInputStream(bis, 64 * 1024)) {

      ByteArrayOutputStream buf = new ByteArrayOutputStream(256 * 1024);
      byte[] chunk = new byte[32 * 1024];
      int n;
      while ((n = gz.read(chunk)) >= 0) {
        buf.write(chunk, 0, n);
        if (buf.size() > MAX_BYTES) return null;   // 解压炸弹保护
      }
      String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);

      List<String> lines = splitLines(text);
      if (lines.isEmpty()) return null;

      int version = 0;
      String mode = "";
      long savedAt = 0;
      int srcCount = 0, chkCount = 0;
      int headerEnd = 0;

      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (!line.startsWith("v=") && !line.startsWith("mode=") && !line.startsWith("at=")
            && !line.startsWith("sources=") && !line.startsWith("checks=")) {
          headerEnd = i;
          break;
        }
        if (line.startsWith("v=")) version = parseIntSafe(line.substring(2));
        else if (line.startsWith("mode=")) mode = line.substring(5);
        else if (line.startsWith("at=")) savedAt = parseLongSafe(line.substring(3));
        else if (line.startsWith("sources=")) srcCount = parseIntSafe(line.substring(8));
        else if (line.startsWith("checks=")) chkCount = parseIntSafe(line.substring(7));
        headerEnd = i + 1;
      }

      if (version != VERSION) return null;   // 版本不兼容：丢弃

      List<SourceRecord> sources = new ArrayList<>(Math.max(0, srcCount));
      List<CheckSourceResult> checks = new ArrayList<>(Math.max(0, chkCount));

      int idx = headerEnd;
      for (int i = 0; i < srcCount && idx < lines.size(); i++, idx++) {
        SourceRecord s = mapToSource(lines.get(idx), i);
        if (s != null) sources.add(s);
      }
      // 校验结果需要按 URL 找回对应的书源对象，否则 CheckSourceResult.source 为 null
      Map<String, SourceRecord> byUrl = new LinkedHashMap<>();
      for (SourceRecord s : sources) {
        if (s.getUrl() != null) byUrl.put(s.getUrl(), s);
      }
      for (int i = 0; i < chkCount && idx < lines.size(); i++, idx++) {
        CheckSourceResult r = mapToCheck(lines.get(idx), byUrl);
        if (r != null) checks.add(r);
      }
      return new Snapshot(sources, checks, mode, savedAt);
    } catch (Exception e) {
      return null;
    }
  }

  // ---------- 序列化 ----------

  private static Map<String, Object> sourceToMap(SourceRecord s) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (s == null) return m;
    m.put("__order", s.getOrder());
    Map<String, Object> raw = s.getRaw();
    if (raw != null) m.putAll(raw);
    // 名称与 URL 用 SourceRecord 上的值兜底（raw 里可能没有）
    m.put("bookSourceName", s.getName());
    if (s.getUrl() != null) m.put("bookSourceUrl", s.getUrl());
    else m.remove("bookSourceUrl");
    return m;
  }

  private static SourceRecord mapToSource(String line, int fallbackOrder) {
    try {
      Object parsed = MiniJson.parse(line);
      if (!(parsed instanceof Map)) return null;
      @SuppressWarnings("unchecked")
      Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) parsed);
      int order = fallbackOrder;
      Object o = m.remove("__order");
      if (o instanceof Number) order = ((Number) o).intValue();
      String name = str(m.get("bookSourceName"));
      String url = str(m.get("bookSourceUrl"));
      return new SourceRecord(order, name, url, m);
    } catch (Exception e) {
      return null;
    }
  }

  private static Map<String, Object> checkToMap(CheckSourceResult r) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (r == null) return m;
    if (r.source != null && r.source.getUrl() != null) m.put("url", r.source.getUrl());
    m.put("status", r.status == null ? "" : r.status.name());
    m.put("message", r.message == null ? "" : r.message);
    m.put("cost", r.respondTimeMs);
    m.put("kind", r.kind == null ? SourceKind.NOVEL.name() : r.kind.name());
    if (r.groups != null && !r.groups.isEmpty()) m.put("groups", TextKit.join("|", r.groups));
    if (r.succeededSteps != null && !r.succeededSteps.isEmpty()) {
      m.put("steps", TextKit.join("|", r.succeededSteps));
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  private static CheckSourceResult mapToCheck(String line, Map<String, SourceRecord> byUrl) {
    try {
      Object parsed = MiniJson.parse(line);
      if (!(parsed instanceof Map)) return null;
      Map<String, Object> m = (Map<String, Object>) parsed;

      String url = str(m.get("url"));
      SourceRecord src = url == null ? null : byUrl.get(url);
      if (src == null) return null;   // 书源已被移除，校验结果也丢弃

      CheckSourceResult.Status status;
      try {
        status = CheckSourceResult.Status.valueOf(str(m.get("status")));
      } catch (Exception e) {
        status = CheckSourceResult.Status.FAILED;
      }
      List<String> groups = new ArrayList<>();
      String g = str(m.get("groups"));
      if (g != null && !g.isEmpty()) Collections.addAll(groups, g.split("\\|"));

      Set<String> steps = new LinkedHashSet<>();
      String st = str(m.get("steps"));
      if (st != null && !st.isEmpty()) Collections.addAll(steps, st.split("\\|"));

      SourceKind kind;
      try {
        kind = SourceKind.valueOf(str(m.get("kind")));
      } catch (Exception e) {
        kind = SourceKind.of(src);
      }
      long cost = 0;
      Object c = m.get("cost");
      if (c instanceof Number) cost = ((Number) c).longValue();

      return new CheckSourceResult(src, status, groups, str(m.get("message")),
          cost, kind, steps);
    } catch (Exception e) {
      return null;
    }
  }

  // ---------- 工具 ----------

  private static void writeLine(GZIPOutputStream gz, String line) throws IOException {
    gz.write(line.getBytes(StandardCharsets.UTF_8));
    gz.write('\n');
  }

  /** 按行切分，跳过空行；不依赖 String.split 以省一次大数组分配。 */
  private static List<String> splitLines(String text) {
    List<String> out = new ArrayList<>();
    int start = 0;
    int len = text.length();
    for (int i = 0; i < len; i++) {
      if (text.charAt(i) == '\n') {
        if (i > start) out.add(text.substring(start, i));
        start = i + 1;
      }
    }
    if (start < len) out.add(text.substring(start));
    return out;
  }

  private static String str(Object o) {
    if (o == null) return null;
    String s = String.valueOf(o);
    return s.isEmpty() ? null : s;
  }

  private static int parseIntSafe(String s) {
    try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
  }

  private static long parseLongSafe(String s) {
    try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
  }
}