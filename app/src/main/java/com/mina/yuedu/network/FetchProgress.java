package com.mina.yuedu.network;
public final class FetchProgress {
  private final int total, completed, succeeded, failed, discoveredSources;
  private final boolean running, cancelled;
  private final String currentUrl;
  private final long downloadedBytes, totalBytes;
  private final int activeDownloads;
  private final long speedBytesPerSec;
  public FetchProgress(int total,int completed,int succeeded,int failed,int discoveredSources,boolean running,boolean cancelled){
    this(total, completed, succeeded, failed, discoveredSources, running, cancelled, null, 0, -1, 0, 0);
  }
  public FetchProgress(int total,int completed,int succeeded,int failed,int discoveredSources,boolean running,boolean cancelled,
                       String currentUrl, long downloadedBytes, long totalBytes, int activeDownloads){
    this(total, completed, succeeded, failed, discoveredSources, running, cancelled, currentUrl, downloadedBytes, totalBytes, activeDownloads, 0);
  }
  public FetchProgress(int total,int completed,int succeeded,int failed,int discoveredSources,boolean running,boolean cancelled,
                       String currentUrl, long downloadedBytes, long totalBytes, int activeDownloads, long speedBytesPerSec){
    this.total=total; this.completed=completed; this.succeeded=succeeded; this.failed=failed;
    this.discoveredSources=discoveredSources; this.running=running; this.cancelled=cancelled;
    this.currentUrl=currentUrl; this.downloadedBytes=downloadedBytes; this.totalBytes=totalBytes;
    this.activeDownloads=activeDownloads; this.speedBytesPerSec=speedBytesPerSec;
  }
  public int getTotal(){return total;} public int getCompleted(){return completed;} public int getSucceeded(){return succeeded;}
  public int getFailed(){return failed;} public int getDiscoveredSources(){return discoveredSources;}
  public boolean isRunning(){return running;} public boolean isCancelled(){return cancelled;}
  /** 正在下载的 URL（无下载中任务时为 null）。 */
  public String getCurrentUrl(){return currentUrl;}
  /** 当前文件已接收字节数。 */
  public long getDownloadedBytes(){return downloadedBytes;}
  /** 当前文件总大小（Content-Length），未知为 -1。 */
  public long getTotalBytes(){return totalBytes;}
  /** 同时在下载/解析的文件数（并发度）。 */
  public int getActiveDownloads(){return activeDownloads;}
  /** 当前下载速度（字节/秒），无下载时为 0。 */
  public long getSpeedBytesPerSec(){return speedBytesPerSec;}
}
