package com.mina.yuedu;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.core.SourceBuckets;
import com.mina.yuedu.core.SourceHealthTracker;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.InvalidSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨配置变更（深色模式切换、字号/语言变化等）保留的内存状态。
 *
 * 为什么需要它：主题继承 {@code Theme.Material3.DayNight}，系统在深色/浅色间切换时
 * 会重建 Activity。原来的实现把这些数据都放在 Activity 字段里，也没有
 * {@code onSaveInstanceState}，于是切换深色模式后用户刚导入的几万条书源、
 * 去重结果和校验结果会全部消失。
 *
 * 为什么不走 Bundle：书源可达数万条，序列化进 Bundle 会触发
 * {@code TransactionTooLargeException}（Binder 1MB 上限）。这里改用
 * {@code onRetainCustomNonConfigurationInstance()}，直接传对象引用，零拷贝。
 *
 * 只在真正重建（旋转/深色模式）时保留；用户主动退出（finish）时系统会丢弃它。
 */
final class SessionState {

  final SourceBuckets buckets = new SourceBuckets();
  final List<InvalidSource> extraInvalid = new ArrayList<>();
  final List<String> discovered = new ArrayList<>();
  final List<CheckSourceResult> checkResults = new ArrayList<>();
  final SourceHealthTracker healthTracker = new SourceHealthTracker();

  DedupeResult result;
  int nextOrder;
  int localFileCount;
  boolean partial;
  /** 待写入文件的 JSON（用户点了保存后系统弹出文件选择器的场景）。 */
  String pendingSave;
  String backgroundTaskText = "";
  int currentTab;
  /** 版本对比的第一份文件 Uri：选完第一份后切深色模式，重建时要能继续选第二份。 */
  android.net.Uri compareUriA;

  private SessionState() {}

  static SessionState create() {
    return new SessionState();
  }
}
