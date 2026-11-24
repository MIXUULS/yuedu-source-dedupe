package com.mina.yuedu;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;

import com.mina.yuedu.core.SourceBuckets;
import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * MainActivity 的行为级回归测试（Robolectric 驱动真实 Activity 生命周期）。
 *
 * 目的：给重构阶段 2（抽控制器）建安全网。这里覆盖的都是本次审查修掉的那类
 * "状态互相踩踏"缺陷——它们是纯逻辑测试够不到、只有真的跑起 Activity 才能验证的。
 *
 * 命名规范：{@code 测什么_在什么条件下_应怎样}，失败时能立刻看出意图。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityBehaviorTest {

  private MainActivity activity;

  @Before public void setUp() {
    activity = Robolectric.buildActivity(MainActivity.class).setup().get();
  }

  @After public void tearDown() {
    if (activity != null && !activity.isFinishing()) activity.finish();
  }

  // ---------- 辅助 ----------

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(order, name, url, raw);
  }

  /** 反射读取私有字段，用于验证内部状态（行为测试只能这样观察私有状态）。 */
  private static Object field(Object target, String name) {
    try {
      java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (Exception e) {
      throw new AssertionError("读取字段 " + name + " 失败", e);
    }
  }

  private static int intField(Object target, String name) {
    Object v = field(target, name);
    return v instanceof Number ? ((Number) v).intValue() : 0;
  }

  @SuppressWarnings("unchecked")
  private static <T> T castField(Object target, String name) {
    return (T) field(target, name);
  }

  // ---------- 生命周期 ----------

  /** 冒烟：Activity 能创建且关键控件已绑定。 */
  @Test public void onCreate_bindsCriticalViews() {
    assertNotNull("去重页应已加载", field(activity, "dedupePage"));
    assertNotNull("URL 输入框应已绑定", field(activity, "etUrls"));
    assertNotNull("解析按钮应已绑定", field(activity, "btnParse"));
    assertNotNull("Tab 应已绑定", field(activity, "tabs"));
  }

  /**
   * 深色模式/旋转这类配置变更后，已导入的书源必须还在（本次修复的核心缺陷）。
   *
   * 用 {@code ActivityController.recreate()} 而不是"手动 finish + 新建"：
   * 只有 recreate() 才会走框架真实的配置变更流程，把
   * onRetainCustomNonConfigurationInstance 的返回值交给新实例。
   */
  @Test public void configChange_retainsImportedSources() {
    org.robolectric.android.controller.ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity first = controller.get();

    SourceBuckets buckets = castField(first, "buckets");
    synchronized (buckets) {
      buckets.addLocal(Arrays.asList(
          source(0, "A", "https://a.com"),
          source(1, "B", "https://b.com")));
    }
    assertEquals(2, buckets.localCount());

    controller.recreate();
    MainActivity second = controller.get();

    SourceBuckets restored = castField(second, "buckets");
    assertEquals("配置变更后书源必须还在", 2, restored.localCount());
    activity = second; // 交给 tearDown 收尾
  }

  /** 重建后还要能继续用：再导入一条应该变成 3 条，而不是从 0 开始。 */
  @Test public void configChange_allowsFurtherImport() {
    org.robolectric.android.controller.ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity first = controller.get();

    SourceBuckets buckets = castField(first, "buckets");
    synchronized (buckets) { buckets.addLocal(Arrays.asList(source(0, "A", "https://a.com"))); }

    controller.recreate();
    MainActivity second = controller.get();

    SourceBuckets restored = castField(second, "buckets");
    synchronized (restored) { restored.addLocal(Arrays.asList(source(1, "B", "https://b.com"))); }
    assertEquals("旧数据 + 新导入应累加", 2, restored.localCount());
    activity = second;
  }

  /**
   * 保留对象本身要携带数据（不依赖框架传递）。
   *
   * 这条与上面两条互补：即使某个运行环境的 recreate 流程不传递 custom non-config instance，
   * 也能确认我们交出去的快照是对的，缩小排查范围。
   */
  @Test public void retainInstance_carriesSourceSnapshot() {
    SourceBuckets buckets = castField(activity, "buckets");
    synchronized (buckets) {
      buckets.addLocal(Arrays.asList(source(0, "A", "https://a.com")));
      buckets.replaceNetwork(Arrays.asList(source(1, "B", "https://b.com")));
    }

    Object retained = activity.onRetainCustomNonConfigurationInstance();
    assertNotNull(retained);

    SourceBuckets snapshot = castField(retained, "buckets");
    assertEquals("本地书源应进快照", 1, snapshot.localCount());
    assertEquals("网络书源应进快照", 1, snapshot.networkCount());
  }

  // ---------- 代际守卫 ----------

  /** recomputeGen 必须是 volatile 且每次 recompute 自增（否则在途任务结果会覆盖新状态）。 */
  @Test public void recompute_incrementsGeneration() throws Exception {
    int before = intField(activity, "recomputeGen");
    java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("recompute", boolean.class);
    m.setAccessible(true);
    m.invoke(activity, false);
    int after = intField(activity, "recomputeGen");
    assertTrue("recompute 必须递增代际号，否则旧任务会写回结果", after > before);
  }

  /** clearAll 必须同时作废 fetch / recompute / check 三条链路的在途回调。 */
  @Test public void clearAll_invalidatesAllGenerations() throws Exception {
    int fetchBefore = intField(activity, "fetchGen");
    int recomputeBefore = intField(activity, "recomputeGen");
    int checkBefore = intField(activity, "checkGen");

    java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("clearAll");
    m.setAccessible(true);
    m.invoke(activity);

    assertTrue("fetchGen 应递增", intField(activity, "fetchGen") > fetchBefore);
    assertTrue("recomputeGen 应递增，否则清空后旧去重结果会复活",
        intField(activity, "recomputeGen") > recomputeBefore);
    assertTrue("checkGen 应递增", intField(activity, "checkGen") > checkBefore);
  }

  /** 清空后内部数据必须真的归零。 */
  @Test public void clearAll_emptiesState() throws Exception {
    SourceBuckets buckets = castField(activity, "buckets");
    synchronized (buckets) {
      buckets.addLocal(Arrays.asList(source(0, "A", "https://a.com")));
      buckets.replaceNetwork(Arrays.asList(source(1, "B", "https://b.com")));
    }
    assertEquals(1, buckets.localCount());
    assertEquals(1, buckets.networkCount());

    java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("clearAll");
    m.setAccessible(true);
    m.invoke(activity);

    assertEquals("本地书源应清空", 0, buckets.localCount());
    assertEquals("网络书源应清空", 0, buckets.networkCount());
    assertNull("去重结果应清空", field(activity, "result"));
  }

  // ---------- 校验并发守卫 ----------

  /** 校验进行中不允许再发起解析（否则两边状态互相踩踏）。 */
  @Test public void startParse_blockedWhileChecking() throws Exception {
    setBoolField("checkRunning", true);
    try {
      java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("startParse");
      m.setAccessible(true);
      m.invoke(activity);
      assertTrue("校验中触发的解析应被挡住，checkRunning 不应被改掉",
          (Boolean) field(activity, "checkRunning"));
    } finally {
      setBoolField("checkRunning", false);
    }
  }

  private void setBoolField(String name, boolean value) throws Exception {
    java.lang.reflect.Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.setBoolean(activity, value);
  }

  // ---------- 校验历史 / 设置持久化 ----------

  /** 设置写入后能被下一次启动读回（SharedPreferences 往返）。 */
  @Test public void preferencesSurviveRestart() {
    Context ctx = ApplicationProvider.getApplicationContext();
    SharedPreferences prefs = ctx.getSharedPreferences("yuedu_dedupe", Context.MODE_PRIVATE);
    prefs.edit().putString("mode", DedupeMode.AGGRESSIVE.name()).apply();

    activity.finish();
    activity = Robolectric.buildActivity(MainActivity.class).setup().get();

    SharedPreferences again = ctx.getSharedPreferences("yuedu_dedupe", Context.MODE_PRIVATE);
    assertEquals(DedupeMode.AGGRESSIVE.name(), again.getString("mode", null));
  }
}