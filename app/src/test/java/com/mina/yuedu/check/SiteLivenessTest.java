package com.mina.yuedu.check;

import org.junit.Test;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.IOException;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.*;

/**
 * 站点存活判定测试。
 *
 * 核心原则：只有「DNS 失败 / 连接被拒 / 无路由」才算站点已死。
 * 4xx/5xx、超时、SSL 失败、连接 reset 都按存活/不确定处理，避免误删可用书源。
 *
 * 依据：2257 条真实书源实测，1443 个唯一域名里 173 个返回 4xx/5xx
 * （403 占 106），这些站完全能用，只是拒绝了我们这个请求。
 */
public class SiteLivenessTest {

  // ---------- 状态码：有响应就说明站活着 ----------

  @Test public void successIsAlive() {
    assertEquals(SiteLiveness.Verdict.ALIVE, SiteLiveness.fromStatusCode(200));
    assertEquals(SiteLiveness.Verdict.ALIVE, SiteLiveness.fromStatusCode(301));
  }

  /** 403 是反爬最常见形态，站点完全活着。 */
  @Test public void forbiddenIsAlive() {
    assertEquals("403 反爬不算死站", SiteLiveness.Verdict.ALIVE,
        SiteLiveness.fromStatusCode(403));
  }

  /** 404 是路径变了，站点仍在。 */
  @Test public void notFoundIsAlive() {
    assertEquals("404 路径变化不算死站", SiteLiveness.Verdict.ALIVE,
        SiteLiveness.fromStatusCode(404));
  }

  /** 5xx 网关抖动，站点在线。 */
  @Test public void serverErrorIsAlive() {
    assertEquals(SiteLiveness.Verdict.ALIVE, SiteLiveness.fromStatusCode(500));
    assertEquals("Cloudflare 520", SiteLiveness.Verdict.ALIVE,
        SiteLiveness.fromStatusCode(520));
    assertEquals(SiteLiveness.Verdict.ALIVE, SiteLiveness.fromStatusCode(503));
  }

  @Test public void rateLimitIsAlive() {
    assertEquals(SiteLiveness.Verdict.ALIVE, SiteLiveness.fromStatusCode(429));
  }

  /** 收到任何合法 HTTP 码就说明服务器在响应。 */
  @Test public void anyHttpCodeIsAlive() {
    for (int code : new int[]{100, 200, 204, 301, 400, 401, 403, 404, 418, 429, 500, 502, 599}) {
      assertEquals("HTTP " + code + " 应判存活", SiteLiveness.Verdict.ALIVE,
          SiteLiveness.fromStatusCode(code));
    }
  }

  @Test public void zeroOrNegativeCodeIsUnknown() {
    assertEquals(SiteLiveness.Verdict.UNKNOWN, SiteLiveness.fromStatusCode(0));
    assertEquals(SiteLiveness.Verdict.UNKNOWN, SiteLiveness.fromStatusCode(-1));
  }

  // ---------- 异常：只有明确的死站信号才判 DEAD ----------

  @Test public void dnsFailureIsDead() {
    assertEquals("DNS 解析失败是死站", SiteLiveness.Verdict.DEAD,
        SiteLiveness.fromException(new UnknownHostException("unknown.host")));
  }

  @Test public void connectionRefusedIsDead() {
    assertEquals("连接被拒是死站", SiteLiveness.Verdict.DEAD,
        SiteLiveness.fromException(new ConnectException("Connection refused")));
  }

  @Test public void noRouteIsDead() {
    assertEquals(SiteLiveness.Verdict.DEAD,
        SiteLiveness.fromException(new NoRouteToHostException("no route")));
  }

  /** 超时判 UNKNOWN 而非 DEAD：服务端多半活着，只是慢。 */
  @Test public void timeoutIsUnknown() {
    assertEquals("超时不应判死，避免误删", SiteLiveness.Verdict.UNKNOWN,
        SiteLiveness.fromException(new SocketTimeoutException("timeout")));
  }

  /** SSL 握手失败说明 TCP 已通，服务在。 */
  @Test public void sslFailureIsUnknown() {
    assertEquals(SiteLiveness.Verdict.UNKNOWN,
        SiteLiveness.fromException(new SSLHandshakeException("handshake failed")));
  }

  /** 连接 reset 也是服务端主动行为，站点多半活。 */
  @Test public void connectionResetIsUnknown() {
    assertEquals(SiteLiveness.Verdict.UNKNOWN,
        SiteLiveness.fromException(new IOException("Connection reset by peer")));
  }

  /** 包装过的异常要沿 cause 链找到根因。 */
  @Test public void unwrapsCauseChain() {
    IOException wrapped = new IOException("request failed",
        new RuntimeException("wrapper", new UnknownHostException("no.such.host")));
    assertEquals("应沿 cause 链识别 DNS 失败", SiteLiveness.Verdict.DEAD,
        SiteLiveness.fromException(wrapped));
  }

  /** 文本兜底：某些封装会丢掉异常类型，只留 message。 */
  @Test public void detectsDnsFailureByMessage() {
    assertEquals(SiteLiveness.Verdict.DEAD,
        SiteLiveness.fromException(new IOException("Unable to resolve host \"x.com\"")));
    assertEquals(SiteLiveness.Verdict.DEAD,
        SiteLiveness.fromException(new IOException("getaddrinfo failed")));
  }

  @Test public void detectsRefusedByMessage() {
    assertEquals(SiteLiveness.Verdict.DEAD,
        SiteLiveness.fromException(new IOException("java.net.ConnectException: Connection refused")));
  }

  @Test public void nullExceptionIsUnknown() {
    assertEquals(SiteLiveness.Verdict.UNKNOWN, SiteLiveness.fromException(null));
  }

  // ---------- 分类辅助 ----------

  @Test public void rejectedCodesClassified() {
    assertTrue(SiteLiveness.isRejectedButAlive(403));
    assertTrue(SiteLiveness.isRejectedButAlive(429));
    assertTrue(SiteLiveness.isRejectedButAlive(503));
    assertFalse(SiteLiveness.isRejectedButAlive(200));
    assertFalse(SiteLiveness.isRejectedButAlive(404));
  }

  @Test public void missingPathCodesClassified() {
    assertTrue(SiteLiveness.isMissingPath(404));
    assertTrue(SiteLiveness.isMissingPath(410));
    assertTrue(SiteLiveness.isMissingPath(451));
    assertFalse(SiteLiveness.isMissingPath(403));
    assertFalse(SiteLiveness.isMissingPath(200));
  }

  // ---------- 文案 ----------

  @Test public void describeMentionsSiteIsOnline() {
    assertTrue("403 文案应说明站点在线",
        SiteLiveness.describe(403).contains("反爬"));
    assertTrue("404 文案应说明站点在线",
        SiteLiveness.describe(404).contains("站点在线"));
    assertTrue("5xx 文案应说明站点在线",
        SiteLiveness.describe(503).contains("站点在线"));
  }

  @Test public void describeHandlesPlainCode() {
    assertEquals("HTTP 200", SiteLiveness.describe(200));
  }

  /** 文案不能把「活站」说成「失效」。 */
  @Test public void describeNeverSaysDeadForAliveCodes() {
    for (int code : new int[]{401, 403, 404, 410, 429, 500, 503}) {
      String d = SiteLiveness.describe(code);
      assertFalse("HTTP " + code + " 的文案不应说「失效」: " + d, d.contains("失效"));
    }
  }
}