package com.mina.yuedu.network;
public interface FetchListener {
  void onProgress(FetchProgress p);
  /** parsed 为 FetchManager 已解析好的结果，避免下载后又重复解析一遍大 JSON。 */
  void onItem(String url, String body, SourceParser.ParseResult parsed);
  void onFailure(String url, String message);
  void onFinished(boolean cancelled, boolean keepLoaded);
}
