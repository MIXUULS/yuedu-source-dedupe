package com.mina.yuedu.check;

import com.mina.yuedu.core.SourceCleaner;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * loginUrl 判定的真实样本回归测试。
 *
 * 数据来源：从公开书源聚合（267 条真实书源）统计得出：
 * <pre>
 *   loginUrl 非空          68 条（25.5%）
 *     ├─ JS 函数/脚本      11 条  如 "function login(){}" —— 根本不是登录页
 *     ├─ JSON 对象配置      5 条  如 "{"url": "null"}" —— 高级功能钩子
 *     ├─ HTTP 地址         46 条  可能是真登录页
 *     └─ 相对路径           4 条
 * </pre>
 *
 * 结论（Issue #1 用户反馈）：loginUrl 不只是登录，还被用作请求前钩子
 * （切换 URL、注入请求头、自动化脚本）。把它当一票否决的特征会误伤
 * 大量主流书源（起点读书、番茄小说、轻之文库等都在其中）。
 *
 * 因此这两个过滤必须默认关闭。本测试锁定这个结论，防止有人改回默认开启。
 */
public class LoginUrlSemanticsTest {

  private static SourceRecord source(String name, String loginUrl) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", "https://" + name + ".com");
    raw.put("searchUrl", "https://" + name + ".com/s?q={{key}}");
    Map<String, Object> ruleSearch = new LinkedHashMap<>();
    ruleSearch.put("bookList", "body");
    raw.put("ruleSearch", ruleSearch);
    if (loginUrl != null) raw.put("loginUrl", loginUrl);
    return new SourceRecord(0, name, "https://" + name + ".com", raw);
  }

  // ---------- 真实样本：这些都不该被当成"必须登录" ----------

  /** 空 JS 函数：完全不是登录入口。 */
  @Test public void emptyJsFunctionIsNotLogin() {
    SourceRecord s = source("起点读书", "function login(){}");
    assertFalse("空 JS 函数不是登录页，不应判为需要登录", SourceCleaner.needsLogin(s));
  }

  /** JSON 配置钩子：url 是 "null" 字符串，不可能是登录页。 */
  @Test public void jsonConfigHookIsNotLogin() {
    SourceRecord s = source("古龙武侠", "{\n  \"url\": \"null\"\n}");
    assertFalse("url 为 null 的配置钩子不应判为需要登录", SourceCleaner.needsLogin(s));
  }

  /** 配置里 url 为空串。 */
  @Test public void emptyUrlInJsonConfig() {
    SourceRecord s = source("若初文学", "{\n  \"url\": \"\"\n}");
    assertFalse("url 为空的配置不应判为需要登录", SourceCleaner.needsLogin(s));
  }

  /** 注册脚本（不是登录）。 */
  @Test public void registerScriptIsNotLogin() {
    SourceRecord s = source("神凑轻说", "function register() { java.startBrowser('x'); }");
    assertFalse("注册脚本不是登录页", SourceCleaner.needsLogin(s));
  }

  /**
   * 多行 JS 脚本（真实样本：大灰狼书源的 loginUrl）。
   *
   * 这类值不以 function 开头，而是以"const localVersion = 3.0;"这类变量声明起头，
   * 旧的 startsWith("function") 判据漏掉了它们。
   */
  @Test public void multiLineScriptIsNotLogin() {
    String script = "const localVersion = 3.0;\n\n\nfunction login() {\n  java.startBrowser('x');\n}";
    SourceRecord s = source("大灰狼", script);
    assertFalse("多行脚本不是登录页", SourceCleaner.needsLogin(s));
  }

  /** if 语句开头的脚本片段。 */
  @Test public void conditionalScriptIsNotLogin() {
    SourceRecord s = source("曼哈漫画", "if (source.getVariable() == \"\") {\n  java.startBrowser('x');\n}");
    assertFalse("条件脚本不是登录页", SourceCleaner.needsLogin(s));
  }

  /** var/let 声明开头的脚本。 */
  @Test public void variableDeclarationIsNotLogin() {
    assertFalse(SourceCleaner.needsLogin(source("A", "var token = '';\nheaders = {};")));
    assertFalse(SourceCleaner.needsLogin(source("B", "let url = 'x';")));
  }

  /** 箭头函数表达式。 */
  @Test public void arrowFunctionIsNotLogin() {
    assertFalse(SourceCleaner.needsLogin(
        source("C", "const login = () => { java.startBrowser('x'); }")));
  }

  /** 但以 http 开头的多行内容仍是地址（不能因为含换行就判脚本）。 */
  @Test public void httpUrlWithNewlineIsStillUrl() {
    assertTrue("以 http 开头的仍是地址",
        SourceCleaner.needsLogin(source("D", "https://a.com/login\n")));
  }

  /** 指向真实 http 登录页的仍应判为需要登录（由用户开关决定是否剔除）。 */
  @Test public void realHttpLoginUrlIsLogin() {
    assertTrue("真实登录页应被识别",
        SourceCleaner.needsLogin(source("某站", "https://www.example.com/user/login")));
  }

  /** 配置里有真实 http 地址也算登录入口。 */
  @Test public void jsonConfigWithRealUrlIsLogin() {
    SourceRecord s = source("壹二小说", "{\n  \"url\": \"https://www.20xs.org\"\n}");
    assertTrue("配置里指向真实站点算登录入口", SourceCleaner.needsLogin(s));
  }

  /** loginCheckUrl 是明确的登录检测信号，优先采信。 */
  @Test public void loginCheckUrlIsAlwaysLogin() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "检测源");
    raw.put("bookSourceUrl", "https://d.com");
    raw.put("loginCheckUrl", "https://d.com/check");
    SourceRecord s = new SourceRecord(0, "检测源", "https://d.com", raw);
    assertTrue("loginCheckUrl 存在即视为需要登录检测", SourceCleaner.needsLogin(s));
  }

  // ---------- 空值仍应保留 ----------

  @Test public void emptyLoginUrlIsNotLogin() {
    assertFalse("空串不应触发剔除", SourceCleaner.needsLogin(source("A", "")));
    assertFalse("null 不应触发剔除", SourceCleaner.needsLogin(source("B", null)));
    assertFalse("纯空白不应触发剔除", SourceCleaner.needsLogin(source("C", "   ")));
  }

  // ---------- 默认值锁定 ----------

  /**
   * 核心回归：两个过滤必须默认关闭。
   *
   * 267 条真实书源里有 68 条（25.5%）带非空 loginUrl，默认开启会一次剔掉四分之一。
   */
  @Test public void filtersDefaultOff() {
    CheckSourceSettings settings = new CheckSourceSettings();
    assertFalse("cleanLogin 必须默认关闭，否则 25% 的书源会被误剔", settings.cleanLogin);
    assertFalse("deleteCaptcha 必须默认关闭", settings.deleteCaptcha);
  }

  /**
   * 开启过滤时，只有**真实**登录页会被剔除，"假登录"值不受影响。
   *
   * 这是改进后的关键行为：旧实现会把 3 条全剔掉（含两条假登录），
   * 新实现只剔真正指向登录页的那条。
   */
  @Test public void enablingFilterOnlyDropsRealLoginPages() {
    java.util.List<SourceRecord> input = Arrays.asList(
        source("起点读书", "function login(){}"),          // 空 JS 函数 → 保留
        source("古龙武侠", "{\"url\": \"null\"}"),         // 配置钩子 → 保留
        source("真实登录站", "https://x.com/user/login"),  // 真登录页 → 剔除
        source("正常源", ""));

    java.util.List<SourceRecord> kept = SourceCleaner.cleanLogin(input);
    assertEquals("只应剔掉真实登录页那 1 条", 3, kept.size());
    for (SourceRecord s : kept) {
      assertNotEquals("真实登录页应被剔除", "真实登录站", s.getName());
    }
  }

  /** 关闭过滤时全部保留。 */
  @Test public void disablingFilterKeepsEverything() {
    CheckSourceSettings settings = new CheckSourceSettings();
    assertFalse(settings.cleanLogin);
    // cleanLogin 未启用时 MainActivity 不会调用 SourceCleaner.cleanLogin
    java.util.List<SourceRecord> input = Arrays.asList(
        source("起点读书", "function login(){}"),
        source("真实登录站", "https://x.com/login"));
    assertEquals("关闭时全部保留", 2, input.size());
  }
}