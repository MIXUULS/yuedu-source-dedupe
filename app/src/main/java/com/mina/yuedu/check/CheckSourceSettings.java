package com.mina.yuedu.check;

public final class CheckSourceSettings {
  public static final int MAX_CONCURRENCY = 500;
  public static final int DEFAULT_TIMEOUT = 180;
  public static final int DEFAULT_CONCURRENCY = 8;
  public static final String DEFAULT_KEYWORD = "我的";
  public static final String DEFAULT_OK_STATUS = "200-399";

  public long timeoutSeconds = DEFAULT_TIMEOUT;
  public boolean checkSearch = true;
  public boolean checkDiscovery = true;
  public boolean checkInfo = true;
  public boolean checkCategory = true;
  public boolean checkContent = true;
  public String keyword = DEFAULT_KEYWORD;
  public int concurrency = DEFAULT_CONCURRENCY;
  public boolean checkNovel = true;
  public boolean checkComic = true;
  public boolean checkVideo = true;
  public boolean checkAudio = true;
  public boolean checkFile = true;
  /** 可用 HTTP 状态码区间，如 "200-399" 或 "200,403,500-502"。 */
  public String okStatusRanges = DEFAULT_OK_STATUS;
  /** 快速模式：只跑搜索+详情两步，跳过发现/目录/正文。 */
  public boolean quickMode = false;

  public void resetToDefaults() {
    timeoutSeconds = DEFAULT_TIMEOUT;
    checkSearch = true;
    checkDiscovery = true;
    checkInfo = true;
    checkCategory = true;
    checkContent = true;
    keyword = DEFAULT_KEYWORD;
    concurrency = DEFAULT_CONCURRENCY;
    checkNovel = true;
    checkComic = true;
    checkVideo = true;
    checkAudio = true;
    checkFile = true;
    okStatusRanges = DEFAULT_OK_STATUS;
  }

  public void normalize() {
    if (timeoutSeconds < 1) timeoutSeconds = DEFAULT_TIMEOUT;
    if (timeoutSeconds > 300) timeoutSeconds = 300;
    if (!checkSearch && !checkDiscovery) checkSearch = true;
    if (!checkInfo) { checkCategory = false; checkContent = false; }
    if (!checkCategory) checkContent = false;
    if (concurrency < 1) concurrency = 1;
    if (concurrency > MAX_CONCURRENCY) concurrency = MAX_CONCURRENCY;
    if (keyword == null || keyword.trim().isEmpty()) keyword = DEFAULT_KEYWORD;
    else keyword = keyword.trim();
    if (!checkNovel && !checkComic && !checkVideo && !checkAudio && !checkFile) {
      checkNovel = true;
      checkComic = true;
      checkVideo = true;
      checkAudio = true;
      checkFile = true;
    }
  }

  public boolean allows(SourceKind kind) {
    if (kind == SourceKind.COMIC) return checkComic;
    if (kind == SourceKind.VIDEO) return checkVideo;
    if (kind == SourceKind.AUDIO) return checkAudio;
    if (kind == SourceKind.FILE) return checkFile;
    return checkNovel;
  }

  /** 判断 HTTP 状态码是否在可用区间内（如 "200-399" 或 "200,403,500-502"）。 */
  public boolean isHttpOk(int code) {
    String r = okStatusRanges;
    if (r == null || r.trim().isEmpty()) r = DEFAULT_OK_STATUS;
    for (String part : r.split(",")) {
      part = part.trim();
      if (part.isEmpty()) continue;
      int dash = part.indexOf('-');
      if (dash > 0) {
        try { int lo = Integer.parseInt(part.substring(0, dash).trim()); int hi = Integer.parseInt(part.substring(dash + 1).trim()); if (code >= lo && code <= hi) return true; }
        catch (NumberFormatException ignored) {}
      } else {
        try { if (Integer.parseInt(part) == code) return true; }
        catch (NumberFormatException ignored) {}
      }
    }
    return false;
  }

  public long timeoutMillis() { return timeoutSeconds * 1000L; }
}
