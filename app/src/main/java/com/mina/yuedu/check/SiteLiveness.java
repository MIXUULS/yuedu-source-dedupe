package com.mina.yuedu.check;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 判断「站点本身是否还活着」——区分「站点已死」与「只是这次路径/反爬失败」。
 *
 * 为什么需要这个类：
 * 原实现把所有非 2xx/3xx 的响应都判成「网站失效」（{@code bad.add("网站失效")}），
 * 于是 403 反爬、404 路径改版、429 限流、5xx 网关抖动全被当成死站。
 * 用 2257 条真实书源实测：1443 个唯一域名里有 173 个（12%）返回 4xx/5xx，
 * 但其中 403 占 106 个——这些站**完全能用**，只是拒绝了我们这个请求。
 *
 * 判定原则（参考用户 Issue #1 提供的工具源码里 NetworkValidator 的思路）：
 * <pre>
 *   只有这些才算「站点已死」：
 *     DNS 解析失败（UnknownHostException）
 *     连接被拒（ConnectException / ECONNREFUSED）
 *     无路由（NoRouteToHostException）
 *
 *   这些都算「站点活着」：
 *     401/403 访问控制、429 限流
 *     5xx 网关抖动（Cloudflare 的 520-526 等）
 *     404/410/451 路径改版或被墙
 *     1xx-5xx 任何有 HTTP 响应的状态
 *     SSL/握手失败（TCP 已通，说明服务在）
 *     读超时、连接 reset、EOF（服务在但慢/主动断）
 * </pre>
 *
 * 注意：这不是「书源可用性」，只是「站点存活」。书源可用与否仍由校验五步决定，
 * 本类只用来避免把「活站」误报成「死站」。
 */
public final class SiteLiveness {

  private SiteLiveness() {}

  /** 站点存活判定的结果。 */
  public enum Verdict {
    /** 站点确定还活着（有 HTTP 响应，或只是反爬/限流/路径变化）。 */
    ALIVE,
    /** 站点确定不可用（DNS 失败、连接被拒、无路由）。 */
    DEAD,
    /** 无法确定（超时、连接 reset、未知异常）——按存活处理，不误杀。 */
    UNKNOWN
  }

  /**
   * 根据 HTTP 状态码判定站点是否存活。
   *
   * 只要收到 HTTP 响应，站点就是活的——哪怕它是 500。真正的死站连响应都给不出来。
   */
  public static Verdict fromStatusCode(int code) {
    if (code <= 0) return Verdict.UNKNOWN;
    // 任何合法的 HTTP 状态码都说明服务器在响应
    if (code >= 100 && code <= 599) return Verdict.ALIVE;
    return Verdict.UNKNOWN;
  }

  /**
   * 根据异常判定站点是否存活。
   *
   * 关键取舍：超时、连接 reset、SSL 失败都判 UNKNOWN 而不是 DEAD——
   * 这些情况服务端通常是活着的（TCP 都可能已建立），
   * 用「不确定」避免误删可用书源。
   */
  public static Verdict fromException(Throwable e) {
    if (e == null) return Verdict.UNKNOWN;

    // 沿 cause 链找根因（OkHttp 常把真实异常包一层）
    Throwable root = e;
    for (int i = 0; i < 8 && root.getCause() != null && root.getCause() != root; i++) {
      root = root.getCause();
    }

    // —— 明确的死站信号 ——
    if (root instanceof UnknownHostException || e instanceof UnknownHostException) {
      return Verdict.DEAD;   // DNS 解析不出来
    }
    if (root instanceof ConnectException || e instanceof ConnectException) {
      return Verdict.DEAD;   // 连接被拒
    }
    if (root instanceof NoRouteToHostException || e instanceof NoRouteToHostException) {
      return Verdict.DEAD;   // 无路由
    }

    String msg = messageOf(root) + " " + messageOf(e);
    String lower = msg.toLowerCase(Locale.ROOT);

    // 文本兜底：某些封装会丢掉异常类型
    if (lower.contains("unable to resolve host") || lower.contains("nodename nor servname")
        || lower.contains("no address associated") || lower.contains("getaddrinfo")) {
      return Verdict.DEAD;
    }
    if (lower.contains("connection refused") || lower.contains("econnrefused")) {
      return Verdict.DEAD;
    }

    // —— 站点活着但这次没成功 ——
    // SSL/TLS 握手失败说明 TCP 已通；超时/reset/EOF 都可能是服务端主动行为
    if (e instanceof SocketTimeoutException || root instanceof SocketTimeoutException) {
      return Verdict.UNKNOWN;
    }
    return Verdict.UNKNOWN;
  }

  /**
   * 该状态码是否属于「站点活着但这次请求不被接受」。
   *
   * 用于在结果里给出更准确的说明，而不是笼统报「网站失效」。
   */
  public static boolean isRejectedButAlive(int code) {
    return code == 401 || code == 403 || code == 405 || code == 406
        || code == 407 || code == 418 || code == 421 || code == 425
        || code == 429
        || (code >= 500 && code <= 599)
        || code == 999;   // 部分站点的自定义拒绝码
  }

  /** 该状态码是否是「路径已变」（站活但资源不在）。 */
  public static boolean isMissingPath(int code) {
    return code == 404 || code == 410 || code == 451;
  }

  /**
   * 把状态码翻译成用户能看懂的原因文案。
   */
  public static String describe(int code) {
    if (isRejectedButAlive(code)) {
      if (code == 403) return "站点拒绝访问（403，可能反爬）";
      if (code == 429) return "站点限流（429）";
      if (code >= 500) return "站点服务异常（" + code + "，但站点在线）";
      return "站点拒绝本次请求（" + code + "）";
    }
    if (isMissingPath(code)) {
      if (code == 404) return "路径不存在（404，站点在线）";
      if (code == 410) return "内容已删除（410，站点在线）";
      return "访问受限（451）";
    }
    return "HTTP " + code;
  }

  private static String messageOf(Throwable t) {
    if (t == null) return "";
    String m = t.getMessage();
    return m == null ? "" : m;
  }
}