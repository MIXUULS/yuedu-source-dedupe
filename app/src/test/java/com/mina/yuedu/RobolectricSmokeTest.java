package com.mina.yuedu;

import android.app.Activity;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Robolectric 冒烟测试：确认本地单元测试里能启动真实的 Activity。
 *
 * 这个用例的目的是守住"测试基建本身可用"——如果它挂了，说明 Robolectric 配置失效，
 * 下面所有 MainActivityTest 的行为测试都会静默失去意义。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RobolectricSmokeTest {

  @Test public void canLaunchMainActivity() {
    MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
    try {
      assertNotNull("Activity 应能正常创建", activity);
      assertFalse("创建后不应处于 finishing 状态", activity.isFinishing());
    } finally {
      activity.finish();
    }
  }

  @Test public void applicationContextIsAvailable() {
    Context ctx = ApplicationProvider.getApplicationContext();
    assertNotNull(ctx);
    assertNotNull("资源系统应可用", ctx.getResources());
  }
}