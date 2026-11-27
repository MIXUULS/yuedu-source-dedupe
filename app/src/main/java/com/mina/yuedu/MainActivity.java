package com.mina.yuedu;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import androidx.core.content.ContextCompat;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mina.yuedu.check.*;
import com.mina.yuedu.core.*;
import com.mina.yuedu.export.*;
import com.mina.yuedu.model.*;
import com.mina.yuedu.settings.SettingsBackup;
import com.mina.yuedu.network.*;
import com.mina.yuedu.ui.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
  private View dedupePage;
  private WebView yck;
  private TextView yckSiteButton, yckRefreshButton, backgroundTaskChip;
  private FrameLayout pageContainer;
  private TabLayout tabs;
  private TextInputEditText etUrls;
  private TextView tvLocalStatus, tvModeDesc, tvProgress, tvStatus, tvStats, tvCheckStats;
  private TextView tvFailReasons;
  private MaterialButton btnCheckDetail, btnUrlHistory, btnExport;
  private TextInputLayout tilCheckSearch;
  private TextInputEditText etCheckSearch;
  /** 搜索过滤后的校验结果列表，null 表示不使用过滤。 */
  private List<CheckSourceResult> filteredCheckResults;
  private View cardRunning, cardResult;
  private LinearLayout boxKindExport, rowCheckTiles;
  private TextView tvExportPreview;
  private final Map<SourceKind, KindRow> kindRows = new EnumMap<>(SourceKind.class);
  private LinearProgressIndicator progressBar;
  private MaterialSwitch switchCleanNames, switchOnlyUsable, switchCleanLogin, switchDeleteCaptcha;
  private MaterialButtonToggleGroup modeGroup;
  private Slider sliderConcurrency;
  private MaterialButton btnParse, btnStop, btnCheck, btnImport, btnSave;

  // 这几个容器在配置变更（深色模式切换）时要整体保留，因此不能是 final：
  // onCreate 里若拿到保留的 SessionState 就直接接管它的实例，避免重新导入。
  private SourceBuckets buckets = new SourceBuckets();
  private List<InvalidSource> extraInvalid = new ArrayList<>();
  private List<String> discovered = new ArrayList<>();
  private static final int MAX_LOCAL_IMPORT_BYTES = 64 * 1024 * 1024; // 本地导入单文件上限 64MB
  private final OperationMode operationMode = new OperationMode(DedupeMode.STANDARD);
  private DedupeMode mode = DedupeMode.STANDARD;
  private DedupeResult result;
  private List<CheckSourceResult> checkResults = new ArrayList<>();
  private FetchManager fetchManager;
  private CheckSourceEngine checkEngine;
  /** 重定向探测句柄：用于 onDestroy 时取消，避免退出后还在跑网络请求。 */
  private RedirectProbe redirectProbe;

  /**
   * 会话持久化：把书源与校验结果写到应用私有目录。
   *
   * 现状痛点是应用被杀或重装后 2257 条书源与 25 分钟校验结果全部丢失。
   * 只在后台线程读写，绝不阻塞 UI。
   */
  private java.util.concurrent.ExecutorService sessionExecutor;
  private volatile boolean sessionRestored;

  /**
   * 已处理过的启动 Intent 标识，防止同一个 Intent 被重复处理。
   *
   * Activity 不销毁时再次 am start / onNewIntent 会复用同一个 Intent 对象，
   * 而 onCreate 里 getIntent() 也可能拿到上次遗留的 Intent——
   * 不去重就会把同一个地址反复填进输入框。
   */
  private String handledIntentKey;
  private boolean cleanNames, partial, onlyUsable;

  /**
   * 并发状态与任务代际统一收在 MainState 里。
   *
   * 这三类状态原本散落成 8 个字段（destroyed / fetchRunning / checkRunning /
   * checkCancelRequested / discard / fetchGen / recomputeGen / checkGen），
   * 它们之间有隐含约束——例如"清空时必须同时作废三条链路的代际"——
   * 但约束只写在注释里。v3.7.1 修的 30 个缺陷里有 6 个就是这类状态踩踏。
   *
   * 收进 MainState 后，作废代际与查询运行状态都只有一条路径。
   */
  private final MainState state = new MainState();

  private int concurrency = 4, localFileCount, currentTab;
  /** 全局顺序号分配器：自带锁，避免"必须在 buckets 锁内调用"这条隐式约定被漏掉。 */
  private final OrderAssigner orderAssigner = new OrderAssigner();
  /** 去重计算串行执行器：连续切换模式/开关时不并发跑多份万级去重（避免 CPU/内存打满）。 */
  private java.util.concurrent.ExecutorService recomputeExecutor;
  private CheckSourceSettings checkSettings = new CheckSourceSettings();
  private SourceHealthTracker healthTracker = new SourceHealthTracker();
  private String pendingSave, backgroundTaskText = "";
  /** 校验历史：每次校验完成后记录摘要 + 失败/超时书源 URL（供增量重验）。 */
  private final java.util.List<CheckHistoryStore.Entry> checkHistory = new ArrayList<>();
  /** 被停止的校验尚未处理的 URL，供下次从断点继续。 */
  private final java.util.List<String> pendingCheckUrls = new ArrayList<>();
  private YckSite yckSite = YckSite.MAIN;
  private YckWebClient yckClient;
  private boolean yckLoaded, yckAutoFellBack;

  private ActivityResultLauncher<String[]> openDocs;
  private ActivityResultLauncher<String> createDoc, csvDoc;
  private ActivityResultLauncher<String[]> configOpen, compareOpen, compareOpenB;

  @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    SystemProxy.init(getApplicationContext());
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    setContentView(R.layout.activity_main);
    MaterialToolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setTitle(R.string.app_name);
      getSupportActionBar().setSubtitle("v" + BuildConfig.VERSION_NAME);
    }
    toolbar.setNavigationOnClickListener(null);
    // 右上角「更多」菜单：收纳不常用功能
    // 菜单项通过 onCreateOptionsMenu 加载，onOptionsItemSelected 处理点击
    // 将「关于」移入菜单，移除 toolbar 整体点击监听
    tabs = findViewById(R.id.tabs);
    pageContainer = findViewById(R.id.pageContainer);
    applySystemBarInsets(findViewById(R.id.rootCoordinator), findViewById(R.id.appBar), pageContainer);
    if (tabs != null) {
      tabs.addTab(tabs.newTab().setText(R.string.tab_dedupe));
      tabs.addTab(tabs.newTab().setText(R.string.tab_yck));
    }

    yckSite = YckSite.fromPreference(getSharedPreferences("yck", MODE_PRIVATE).getString("site", "main"));
    loadCheckSettings();
    loadCheckHistory();
    loadPendingCheckUrls();

    // 先接管配置变更保留下来的会话数据，再决定是否从磁盘加载健康数据：
    // 顺序颠倒会让刚校验完的健康记录被旧存档覆盖。
    SessionState kept = (SessionState) getLastCustomNonConfigurationInstance();
    if (kept != null) {
      buckets = kept.buckets;
      extraInvalid = kept.extraInvalid;
      discovered = kept.discovered;
      checkResults = kept.checkResults;
      healthTracker = kept.healthTracker;
      result = kept.result;
      orderAssigner.set(kept.nextOrder);
      localFileCount = kept.localFileCount;
      partial = kept.partial;
      pendingSave = kept.pendingSave;
      backgroundTaskText = kept.backgroundTaskText;
      currentTab = kept.currentTab;
      compareUriA = kept.compareUriA;
    } else {
      loadHealthData();
    }

    recomputeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "dedupe-recompute");
      t.setDaemon(true);
      return t;
    });

    dedupePage = getLayoutInflater().inflate(R.layout.page_dedupe, pageContainer, false);
    pageContainer.addView(dedupePage);
    bindDedupe(dedupePage);
    restorePrefs();

    sessionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "session-io");
      t.setDaemon(true);
      return t;
    });
    // 配置变更（如切深色模式）时数据已由 SessionState 接管，不需要再读盘；
    // 只有真正的冷启动（kept == null）才恢复。
    if (kept == null) scheduleSessionRestore();

    yck = new WebView(this);
    yck.setVisibility(View.GONE);
    pageContainer.addView(yck, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setupYck();
    addYckSiteButton();
    addYckRefreshButton();
    addBackgroundTaskChip();

    tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
      @Override public void onTabSelected(TabLayout.Tab tab) {
        if (tab.getPosition() == 0) showDedupe(); else showYck();
      }
      @Override public void onTabUnselected(TabLayout.Tab tab) {}
      @Override public void onTabReselected(TabLayout.Tab tab) {}
    });

    openDocs = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
      if (uris == null) return;
      for (Uri u : uris) readChosen(u);
    });
    createDoc = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
      if (uri == null || pendingSave == null) return;
      final String payload = pendingSave;
      try (OutputStream o = getContentResolver().openOutputStream(uri)) {
        o.write(payload.getBytes(StandardCharsets.UTF_8));
        // 写完即清空：否则用户下次在选择器里取消时，pendingSave 仍是上一份内容，
        // 会被另一条保存路径（如设置备份）误用。
        pendingSave = null;
        toast("已保存");
      } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
    });
    csvDoc = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
      if (uri == null || pendingSave == null) return;
      final String payload = pendingSave;
      try (OutputStream o = getContentResolver().openOutputStream(uri)) {
        o.write(payload.getBytes(StandardCharsets.UTF_8));
        pendingSave = null;
        toast("已导出 CSV");
      } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
    });

    configOpen = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
      if (uris == null || uris.isEmpty()) return;
      new Thread(() -> {
        final Uri u = uris.get(0);
        String text;
        try (InputStream in = getContentResolver().openInputStream(u); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
          byte[] b = new byte[8192]; int n; int total = 0;
          while ((n = in.read(b)) >= 0) {
            total += n;
            // 与本地导入统一上限：不设限时选到超大文件会直接 OOM
            if (total > MAX_LOCAL_IMPORT_BYTES) throw new IOException("文件过大（超过 64MB）");
            o.write(b, 0, n);
          }
          text = o.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
          runOnUiThread(() -> { if (!state.isDestroyed()) toast("读取设置文件失败：" + e.getMessage()); });
          return;
        }
        runOnUiThread(() -> { if (!state.isDestroyed()) restoreSettings(text); });
      }, "config-restore").start();
    });

    compareOpen = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
      if (uris == null || uris.isEmpty()) return;
      // 第二份也可单独选择：收集 all Docs 后交给 compareOpenB 拼接
      readCompareFileA(uris);
    });
    compareOpenB = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
      if (uris == null || uris.isEmpty()) return;
      readCompareFileB(uris);
    });

    updateModeDesc();
    // 配置变更后把旧实例的界面状态补回来（数据已在上面接管，这里只刷新展示）
    if (kept != null) restoreSessionUi();
    // 首次创建时处理外部传入的 URL（legado:// 或 ACTION_VIEW/SEND 带的地址）
    if (savedInstanceState == null) handleLaunchIntent(getIntent());
  }

  /**
   * 处理外部拉起时带的数据。
   *
   * Manifest 早已声明 {@code legado://} scheme，但 MainActivity 从不读 getIntent()——
   * 用户在浏览器点书源链接、选择本应用后什么都不会发生，属于「声明了没实现」。
   * 这里补上：把 URL 填进输入框（不自动去重，由用户确认后点解析）。
   *
   * 支持形式：
   * <pre>
   *   legado://import?a=https://...     （legado 官方约定）
   *   legado://import/https://...
   *   ACTION_VIEW  http(s)://...         （浏览器打开）
   *   ACTION_SEND  text 里含 http 地址   （其他应用分享）
   * </pre>
   */
  private void handleLaunchIntent(android.content.Intent intent) {
    if (intent == null) return;
    // 同一个 Intent 只处理一次：onCreate 与 onNewIntent 可能拿到同一个，
    // 系统在应用未销毁时也可能重放上次的 Intent。
    String key = String.valueOf(intent.getAction()) + "|" + intent.getData()
        + "|" + intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
    if (key.equals(handledIntentKey)) {
      return;
    }
    handledIntentKey = key;
    try {
      List<String> urls = new ArrayList<>();

      Uri data = intent.getData();
      if (data != null) {
        String s = data.toString();
        if (s != null && s.startsWith("legado://")) {
          String q = data.getQueryParameter("a");
          if (q == null) q = data.getQueryParameter("url");
          if (q == null) q = data.getQueryParameter("import");
          if (q != null) {
            urls.add(q);
          } else {
            // legado://import/https://xxx 这种路径里带地址
            String path = data.getPath();
            if (path != null) {
              int i = path.indexOf("http");
              if (i >= 0) urls.add(path.substring(i));
            }
          }
        } else if (s != null && (s.startsWith("http://") || s.startsWith("https://"))) {
          urls.add(s);
        }
      }

      String action = intent.getAction();
      if (android.content.Intent.ACTION_SEND.equals(action)) {
        String text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
        if (text != null) {
          for (String part : text.split("\\s+")) {
            if (part.startsWith("http://") || part.startsWith("https://")) urls.add(part);
          }
        }
      }

      if (urls.isEmpty()) {
        return;
      }
      // 去重：onCreate 与 onNewIntent 可能拿到同一个 Intent（launchMode=singleTop），
      // 不去重就会把同一个地址填两遍。
      CharSequence cur = etUrls.getText();
      String existing = cur == null ? "" : cur.toString();
      java.util.Set<String> existLines = new java.util.LinkedHashSet<>();
      for (String line : existing.split("\\r?\\n")) {
        String t = line.trim();
        if (!t.isEmpty()) existLines.add(t);
      }
      int added = 0;
      for (String u : urls) {
        if (existLines.add(u.trim())) added++;
      }
      if (added == 0) {
        // 已经在输入框里了，不重复填也不打扰用户
        return;
      }
      etUrls.setText(TextKit.join("\n", existLines));
      etUrls.setSelection(etUrls.getText() == null ? 0 : etUrls.getText().length());
      toast("已填入 " + added + " 个地址，点「解析网络源」开始");
    } catch (Exception e) {
      // 外部数据不可信，解析失败就当没传
    }
  }

  /**
   * 应用已在运行时再次被拉起（比如浏览器里点了另一个书源链接）。
   *
   * 单例模式下走这里而不是 onCreate，所以也要处理。
   */
  @Override protected void onNewIntent(android.content.Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleLaunchIntent(intent);
  }

  /** 会话文件：应用私有目录下的 session.gz。 */
  private java.io.File sessionFile() {
    return new java.io.File(getFilesDir(), "session.gz");
  }

  /**
   * 冷启动时在后台读会话并恢复到界面。
   *
   * 读盘 2-3 MB（GZIP 压缩后）在后台线程完成，不阻塞启动。
   * 恢复失败就当没有会话——绝不能因为会话文件坏了让应用起不来。
   */
  private void scheduleSessionRestore() {
    if (sessionRestored) return;
    sessionRestored = true;
    final java.util.concurrent.ExecutorService ex = sessionExecutor;
    if (ex == null) return;
    ex.execute(() -> {
      final SessionStore.Snapshot snap = SessionStore.load(sessionFile());
      if (snap == null || snap.isEmpty()) return;
      runOnUiThread(() -> {
        if (state.isDestroyed() || isFinishing()) return;
        try {
          applySessionSnapshot(snap);
        } catch (Throwable t) {
        }
      });
    });
  }

  /** 把读到的会话数据灌进内存与界面。 */
  private void applySessionSnapshot(SessionStore.Snapshot snap) {
    if (snap == null || snap.isEmpty()) return;
    // 用户已经手动导入过数据就不覆盖（恢复是"锦上添花"，不能盖掉更新的输入）
    boolean alreadyHasData;
    synchronized (buckets) { alreadyHasData = !buckets.isEmpty(); }
    if (alreadyHasData || result != null) return;

    synchronized (buckets) { buckets.addLocal(snap.sources); }
    localFileCount = 1;   // 让「已添加本地 JSON」那行有内容可显示
    // 顺序号接着恢复的数据往下排，否则新导入的源会从 0 开始撞号
    int maxOrder = 0;
    for (SourceRecord s : snap.sources) maxOrder = Math.max(maxOrder, s.getOrder() + 1);
    orderAssigner.set(maxOrder);

    if (snap.checkResults != null && !snap.checkResults.isEmpty()) {
      checkResults.clear();
      checkResults.addAll(snap.checkResults);
    }

    // 直接同步算一次去重（2257 条毫秒级），不走 recompute()：
    // recompute 是异步的且带代际守卫，冷启动时容易被后续调用抢先作废，
    // 导致恢复"看起来没生效"。这里在主线程同步算完再刷新界面，结果确定。
    final List<SourceRecord> all;
    synchronized (buckets) { all = buckets.all(); }
    DedupeResult restored = DedupeEngine.run(all, mode, cleanNames, true);
    result = new DedupeResult(all.size(), restored.getRetained(),
        restored.getDuplicateGroups(), restored.getInvalid());
    partial = false;

    // 刷新界面
    cardResult.setVisibility(View.VISIBLE);
    setStatValue(R.id.tileOriginal, result.getOriginalCount());
    setStatValue(R.id.tileDup, result.getDuplicateCount());
    setStatValue(R.id.tileKept, result.getRetained().size());
    setStatValue(R.id.tileBad, result.getInvalid().size());
    tvStats.setText(ResultSummary.format(mode, buckets.localCount(),
        buckets.networkCount(), result, false));
    btnImport.setEnabled(!result.getRetained().isEmpty());
    btnSave.setEnabled(!result.getRetained().isEmpty());
    tvLocalStatus.setVisibility(View.VISIBLE);
    tvLocalStatus.setText("已恢复上次会话 · " + snap.sources.size() + " 条书源");
    if (!checkResults.isEmpty()) {
      renderCheckStats();
      switchOnlyUsable.setVisibility(View.VISIBLE);
    }
    renderKindExportSwitches();
    refreshExportPreview();

    toast("已恢复上次会话：" + snap.sources.size() + " 条书源"
        + (snap.checkResults.isEmpty() ? "" : "、" + snap.checkResults.size() + " 条校验结果"));
  }

  /**
   * 在后台把当前会话写盘。
   *
   * 什么时候调用：校验结束、去重结果更新、书源变多/变少之后。
   * 不放在 onDestroy —— 系统杀进程时 onDestroy 不保证执行，
   * 而且那时再写盘也来不及。
   */
  private void persistSessionAsync() {
    final java.util.concurrent.ExecutorService ex = sessionExecutor;
    if (ex == null || state.isDestroyed()) return;

    final List<SourceRecord> sources;
    synchronized (buckets) {
      sources = result != null ? new ArrayList<>(result.getRetained()) : buckets.all();
    }
    if (sources.isEmpty() && checkResults.isEmpty()) return;

    final List<CheckSourceResult> checks = new ArrayList<>(checkResults);
    final String modeName = mode.name();
    final java.io.File f = sessionFile();

    ex.execute(() -> SessionStore.save(f, sources, checks, modeName));
  }

  /** 配置变更重建后恢复界面：本地文件计数、结果卡片、统计数字、校验结果、当前 Tab。 */
  private void restoreSessionUi() {
    if (localFileCount > 0) {
      tvLocalStatus.setVisibility(View.VISIBLE);
      tvLocalStatus.setText("已添加本地 JSON：" + localFileCount + " 个文件 · " + buckets.localCount() + " 条书源");
    }
    if (result != null) {
      cardResult.setVisibility(View.VISIBLE);
      setStatValue(R.id.tileOriginal, result.getOriginalCount());
      setStatValue(R.id.tileDup, result.getDuplicateCount());
      setStatValue(R.id.tileKept, result.getRetained().size());
      setStatValue(R.id.tileBad, result.getInvalid().size());
      tvStats.setText(ResultSummary.format(mode, buckets.localCount(), buckets.networkCount(), result, partial));
      btnImport.setEnabled(!result.getRetained().isEmpty());
      btnSave.setEnabled(!result.getRetained().isEmpty());
      renderKindExportSwitches();
      if (!checkResults.isEmpty()) {
        refreshCheckSearchFilter();
        renderCheckStats();
      }
    }
    updateBackgroundTask(backgroundTaskText);
    if (currentTab == 1 && tabs != null && tabs.getTabAt(1) != null) tabs.selectTab(tabs.getTabAt(1));
  }

  @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
    getMenuInflater().inflate(R.menu.menu_main, menu);
    return true;
  }

  /**
   * 配置变更（深色模式切换等）时把内存里的书源/校验结果交给下一个 Activity 实例。
   *
   * 用这个而不是 onSaveInstanceState + Bundle：书源可达数万条，
   * 序列化进 Bundle 会超过 Binder 1MB 上限直接 TransactionTooLargeException。
   */
  @Override public Object onRetainCustomNonConfigurationInstance() {
    SessionState state = SessionState.create();
    synchronized (buckets) {
      state.buckets.addLocal(buckets.localSnapshot());
      state.buckets.replaceNetwork(buckets.networkSnapshot());
    }
    synchronized (extraInvalid) { state.extraInvalid.addAll(extraInvalid); }
    synchronized (discovered) { state.discovered.addAll(discovered); }
    state.checkResults.addAll(checkResults);
    state.healthTracker.fromJsonList(healthTracker.toJsonList());
    state.result = result;
    state.nextOrder = orderAssigner.peek();
    state.localFileCount = localFileCount;
    state.partial = partial;
    state.pendingSave = pendingSave;
    state.backgroundTaskText = backgroundTaskText;
    state.currentTab = currentTab;
    state.compareUriA = compareUriA;
    return state;
  }

  @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_history) showCheckHistory();
    else if (id == R.id.action_recheck) recheckFailedSources();
    else if (id == R.id.action_preset) showDedupePresetMenu();
    else if (id == R.id.action_backup) showConfigMenu();
    else if (id == R.id.action_compare) showCompareMenu();
    else if (id == R.id.action_report) showChangeReport();
    else if (id == R.id.action_health) showHealthDashboard();
    else if (id == R.id.action_about) showAbout();
    else return super.onOptionsItemSelected(item);
    return true;
  }

  @Override protected void onDestroy() {
    state.markDestroyed();
    savePrefs(); // 记住最后输入的 URL 等状态
    // 无论正常退出还是配置变更都要停掉后台任务：任务回调绑定的是本实例，
    // 放它继续跑只会变成没人收结果的孤儿线程（白耗流量和电量）。
    // 正在解析/校验的进度会丢失，但已经收进 buckets 的书源由 SessionState 完整保留，
    // 重建后 restoreSessionUi 会重新算一遍结果。
    if (fetchManager != null) fetchManager.cancel(true);
    if (checkEngine != null) checkEngine.cancel();
    // 重定向探测是纯网络等待，不取消会让线程池在 Activity 销毁后继续跑几分钟
    if (redirectProbe != null) { redirectProbe.cancel(); redirectProbe = null; }
    recomputeExecutor.shutdownNow();
    if (sessionExecutor != null) {
      sessionExecutor.shutdownNow();
      sessionExecutor = null;
    }
    // 释放 WebView：JS 桥与 Client 都持有本 Activity 的引用，不销毁会把整个视图树一起泄漏
    if (yck != null) {
      yck.stopLoading();
      ViewGroup parent = (ViewGroup) yck.getParent();
      if (parent != null) parent.removeView(yck);
      yck.setWebViewClient(null);
      yck.destroy();
    }
    super.onDestroy();
  }

  private void applySystemBarInsets(View root, View appBar, View content) {
    final int appLeft = appBar.getPaddingLeft();
    final int appTop = appBar.getPaddingTop();
    final int appRight = appBar.getPaddingRight();
    final int appBottom = appBar.getPaddingBottom();
    final int contentLeft = content.getPaddingLeft();
    final int contentTop = content.getPaddingTop();
    final int contentRight = content.getPaddingRight();
    final int contentBottom = content.getPaddingBottom();
    ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
      appBar.setPadding(appLeft, appTop + bars.top, appRight, appBottom);
      content.setPadding(contentLeft, contentTop, contentRight, contentBottom + bars.bottom);
      return windowInsets;
    });
    ViewCompat.requestApplyInsets(root);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void bindDedupe(View root) {
    etUrls = root.findViewById(R.id.etUrls);
    tvLocalStatus = root.findViewById(R.id.tvLocalStatus);
    tvModeDesc = root.findViewById(R.id.tvModeDesc);
    tvProgress = root.findViewById(R.id.tvProgress);
    tvStatus = root.findViewById(R.id.tvStatus);
    tvStats = root.findViewById(R.id.tvStats);
    tvCheckStats = root.findViewById(R.id.tvCheckStats);
    tvFailReasons = root.findViewById(R.id.tvFailReasons);
    btnCheckDetail = root.findViewById(R.id.btnCheckDetail);
    boxKindExport = root.findViewById(R.id.boxKindExport);
    rowCheckTiles = root.findViewById(R.id.rowCheckTiles);
    tvExportPreview = root.findViewById(R.id.tvExportPreview);
    kindRows.clear();
    bindKindRow(root, R.id.rowKindNovel, SourceKind.NOVEL, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindComic, SourceKind.COMIC, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindVideo, SourceKind.VIDEO, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindAudio, SourceKind.AUDIO, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindFile, SourceKind.FILE, R.color.md_theme_primary);
    bindStatTile(root.findViewById(R.id.tileOriginal), "原始", R.color.md_theme_on_surface);
    bindStatTile(root.findViewById(R.id.tileDup), "重复", R.color.md_theme_secondary);
    bindStatTile(root.findViewById(R.id.tileKept), "有效", R.color.md_theme_success);
    bindStatTile(root.findViewById(R.id.tileBad), "错误", R.color.md_theme_error);
    root.findViewById(R.id.tileDup).setOnClickListener(v -> showDuplicateDetail());
    bindStatTile(root.findViewById(R.id.tileOk), "可用", R.color.md_theme_success);
    bindStatTile(root.findViewById(R.id.tileFail), "失败", R.color.md_theme_error);
    bindStatTile(root.findViewById(R.id.tileTimeout), "超时", R.color.md_theme_secondary);
    cardRunning = root.findViewById(R.id.cardRunning);
    cardResult = root.findViewById(R.id.cardResult);
    progressBar = root.findViewById(R.id.progressBar);
    switchCleanNames = root.findViewById(R.id.switchCleanNames);
    switchOnlyUsable = root.findViewById(R.id.switchOnlyUsable);
    switchCleanLogin = root.findViewById(R.id.switchCleanLogin);
    switchDeleteCaptcha = root.findViewById(R.id.switchDeleteCaptcha);
    modeGroup = root.findViewById(R.id.modeGroup);
    sliderConcurrency = root.findViewById(R.id.sliderConcurrency);
    btnParse = root.findViewById(R.id.btnParse);
    btnStop = root.findViewById(R.id.btnStop);
    btnCheck = root.findViewById(R.id.btnCheck);
    btnImport = root.findViewById(R.id.btnImport);
    btnSave = root.findViewById(R.id.btnSave);
    btnCheckDetail.setOnClickListener(v -> showCheckDetail());
    btnUrlHistory = root.findViewById(R.id.btnUrlHistory);
    btnUrlHistory.setOnClickListener(v -> showUrlHistory());
    btnExport = root.findViewById(R.id.btnExport);
    btnExport.setOnClickListener(v -> showExportMenu());
    tilCheckSearch = root.findViewById(R.id.tilCheckSearch);
    etCheckSearch = root.findViewById(R.id.etCheckSearch);
    if (etCheckSearch != null) {
      etCheckSearch.addTextChangedListener(new android.text.TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
          applyCheckSearch(s == null ? "" : s.toString().trim());
        }
        @Override public void afterTextChanged(android.text.Editable s) {}
      });
    }

    root.findViewById(R.id.btnChooseJson).setOnClickListener(v -> openDocs.launch(new String[]{"application/json", "text/*", "*/*"}));
    root.findViewById(R.id.btnClear).setOnClickListener(v -> clearAll());
    modeGroup.addOnButtonCheckedListener((g, id, checked) -> {
      if (!checked) return;
      if (id == R.id.modeStandard) mode = DedupeMode.STANDARD;
      else if (id == R.id.modeStrict) mode = DedupeMode.STRICT;
      else if (id == R.id.modeName) mode = DedupeMode.NAME;
      else mode = DedupeMode.AGGRESSIVE;
      operationMode.select(mode);
      updateModeDesc();
      savePrefs();
      if (result != null) recompute(partial);
    });
    sliderConcurrency.addOnChangeListener((s, value, fromUser) -> concurrency = Math.round(value));
    switchCleanNames.setOnCheckedChangeListener((b, on) -> { cleanNames = on; savePrefs(); if (result != null) recompute(partial); });
    switchOnlyUsable.setOnCheckedChangeListener((b, on) -> { onlyUsable = on; savePrefs(); refreshExportPreview(); });
    switchCleanLogin.setOnCheckedChangeListener((b, on) -> {
      checkSettings.cleanLogin = on; saveCheckSettings();
      if (result != null) recompute(partial);
    });
    switchDeleteCaptcha.setOnCheckedChangeListener((b, on) -> {
      checkSettings.deleteCaptcha = on; saveCheckSettings();
      if (result != null) recompute(partial);
    });
    btnParse.setOnClickListener(v -> startParse());
    btnStop.setOnClickListener(v -> stopCurrentTask());
    btnCheck.setOnClickListener(v -> showCheckDialog());
    btnImport.setOnClickListener(v -> importReader());
    btnSave.setOnClickListener(v -> saveJson());

    root.findViewById(R.id.btnShare).setOnClickListener(v -> showShareMenu());
    // 剪贴板导入已合并到「分享/导入 ▼」菜单中
  }

  private void updateModeDesc() { tvModeDesc.setText(mode.description()); }

  /** 为记录分配互不冲突的全局顺序号（委托给自带锁的 OrderAssigner，见其类注释）。 */
  private List<SourceRecord> reorder(List<SourceRecord> records) {
    return orderAssigner.assign(records);
  }

  private List<String> getUrls() {
    CharSequence cs = etUrls.getText();
    return UrlInputParser.parse(cs == null ? null : cs.toString());
  }

  private void startParse() {
    // 解析会清空校验结果并重算去重：校验进行中直接放行会让两边的状态互相踩踏
    // （旧引擎结束时又把上一轮结果写回来，pendingCheckUrls / 健康数据也被旧批次污染）。
    if (state.isCheckRunning()) { toast("校验进行中，请先停止校验再解析"); return; }
    operationMode.select(mode); operationMode.start(); mode = operationMode.resultMode();
    cleanNames = switchCleanNames.isChecked();
    concurrency = Math.round(sliderConcurrency.getValue());
    savePrefs();
    partial = false; state.setDiscard(false); checkResults.clear();
    state.invalidateCheck(); // 作废可能仍在收尾的上一轮校验回调
    refreshCheckSearchFilter();
    synchronized (buckets) { buckets.replaceNetwork(Collections.emptyList()); }
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    List<String> urls = getUrls();
    if (!ParseRequestDecision.shouldRun(buckets.localCount(), urls.size())) { toast("请选择本地文件或输入网络地址"); return; }
    if (urls.isEmpty()) { recompute(false); return; }
    // 解析前预检：重复地址、非 http 行原本都是静默处理，用户只能从失败数猜问题
    UrlPreflight.Report preflight = UrlPreflight.analyze(etUrls.getText() == null ? "" : etUrls.getText().toString());
    String preflightMsg = UrlPreflight.summary(preflight);
    if (preflightMsg != null) toast(preflightMsg);
    // 用去重后的列表发起解析，避免同一个地址被重复请求（浪费流量与时间）
    if (!preflight.unique.isEmpty()) urls = preflight.unique;
    rememberUrls(urls);
    final int gen = state.startFetch(); // 新一轮任务代际：旧任务的数据/收尾不再生效
    startFetch(urls, false, gen);
  }

  private void startFetch(List<String> urls, boolean followUp, final int gen) {
    state.setFetchRunning(true);
    // 抓取期间不应有校验在跑；若上一轮校验残留了 running 标志，这里清掉
    if (state.isCheckRunning()) state.finishCheck();
    btnStop.setEnabled(true);
    btnStop.setText("停止解析");
    updateBackgroundTask("后台解析 0 / " + urls.size());
    setTaskRunning(true);
    cardRunning.setVisibility(View.VISIBLE);
    cardResult.setVisibility(View.GONE);
    fetchManager = new FetchManager();
    fetchManager.start(urls, concurrency, new FetchListener() {
      @Override public void onProgress(FetchProgress p) {
        runOnUiThread(() -> {
          if (state.isDestroyed() || gen != state.fetchGeneration()) return;
          int total = Math.max(1, p.getTotal());
          progressBar.setProgressCompat(p.getCompleted() * 100 / total, true);
          String cur = p.getCurrentUrl();
          if (cur != null && p.getDownloadedBytes() > 0) {
            String speed = p.getSpeedBytesPerSec() > 0 ? " · " + fmtBytes(p.getSpeedBytesPerSec()) + "/s" : "";
            tvProgress.setText("并发下载 " + p.getActiveDownloads() + " 个 · " + fmtBytes(p.getDownloadedBytes())
                + (p.getTotalBytes() > 0 ? " / " + fmtBytes(p.getTotalBytes()) : "")
                + speed + "   " + p.getCompleted() + "/" + p.getTotal());
          } else if (cur != null) {
            tvProgress.setText("并发下载 " + p.getActiveDownloads() + " 个   " + p.getCompleted() + "/" + p.getTotal());
          } else {
            tvProgress.setText("正在解析书源    " + p.getCompleted() + " / " + p.getTotal());
          }
          tvStatus.setText("成功 " + p.getSucceeded() + " · 失败 " + p.getFailed() + " · 已发现 " + p.getDiscoveredSources() + " 条");
          updateBackgroundTask("后台解析 " + p.getCompleted() + " / " + p.getTotal());
        });
      }
      @Override public void onItem(String url, String body, SourceParser.ParseResult p) {
        // 归类逻辑抽到 FetchResponseClassifier（可单测），这里只做状态写入
        FetchResponseClassifier.Result cls = FetchResponseClassifier.classify(url, body, p);
        synchronized (buckets) {
          // gen 检查必须在锁内：clearAll 会先加锁清空再自增 gen，
          // 锁外检查过的旧回调可能在清空之后进入，把刚删除的数据"复活"
          if (gen != state.fetchGeneration() || state.isDiscard()) return;
          if (cls.hasRecords()) {
            buckets.addNetwork(reorder(cls.parsed.getRecords()));
          } else if (cls.hasMore()) {
            synchronized (discovered) { discovered.addAll(cls.discovered); }
          } else if (cls.isInvalid()) {
            synchronized (extraInvalid) { extraInvalid.add(cls.invalid); }
          }
        }
      }
      @Override public void onFailure(String u, String m) {
        synchronized (extraInvalid) {
          if (gen != state.fetchGeneration() || state.isDiscard()) return;
          extraInvalid.add(new InvalidSource(InvalidSource.Kind.NETWORK_FAILURE, u + " · " + m));
        }
      }
      @Override public void onFinished(boolean cancelled, boolean keep) {
        runOnUiThread(() -> {
          if (state.isDestroyed() || gen != state.fetchGeneration()) return;
          if (state.isDiscard()) {
            state.setDiscard(false);
            synchronized (buckets) { buckets.replaceNetwork(Collections.emptyList()); }
            synchronized (extraInvalid) { extraInvalid.clear(); }
            synchronized (discovered) { discovered.clear(); }
            state.setFetchRunning(false);
            fetchManager = null;
            updateBackgroundTask("");
            setTaskRunning(false);
            cardRunning.setVisibility(View.GONE);
            recompute(false);
            return;
          }
          List<String> more;
          synchronized (discovered) { more = new ArrayList<>(new LinkedHashSet<>(discovered)); discovered.clear(); }
          if (!cancelled && !followUp && !more.isEmpty()) { startFetch(more, true, gen); return; }
          partial = cancelled && keep;
          recompute(partial);
        });
      }
    });
  }

  private void stopCurrentTask() {
    if (state.isCheckRunning()) {
      stopCheck();
      return;
    }
    if (state.isFetchRunning()) stopParse();
  }

  private void stopCheck() {
    if (checkEngine == null || state.isCheckCancelRequested()) return;
    state.requestCheckCancel();
    btnStop.setEnabled(false);
    btnStop.setText("正在停止…");
    tvStatus.setText("正在停止校验，请稍候…");
    checkEngine.cancel();
  }

  private void stopParse() {
    if (fetchManager == null) return;
    new MaterialAlertDialogBuilder(this)
      .setTitle("停止解析？")
      .setItems(new String[]{"处理已加载数据", "全部放弃", "继续解析"}, (d, w) -> {
        if (w == 0) { partial = true; fetchManager.cancel(true); }
        else if (w == 1) { state.setDiscard(true); fetchManager.cancel(false); }
      }).show();
  }

  private void recompute(boolean isPartial) {
    final int gen = state.nextRecomputeGeneration();
    // 只有确实没有校验在跑时才清这两个标志：
    // 之前无条件重置会让 runCheck 的并发守卫失效，用户可以再启动第二个校验引擎，
    // 两个引擎的 onFinished 互相覆盖 checkResults，先完成的那个还会变成无法取消的残留引擎。
    if (checkEngine == null) {
      state.finishCheck();
    }
    state.setFetchRunning(false);
    updateBackgroundTask("");
    List<SourceRecord> copy; synchronized (buckets) { copy = buckets.all(); }
    final int localCount = buckets.localCount();
    final int networkCount = buckets.networkCount();
    // 清理需要登录的源
    final List<SourceRecord> filteredCopy;
    if (checkSettings.cleanLogin) {
      filteredCopy = SourceCleaner.cleanLogin(copy);
    } else {
      filteredCopy = copy;
    }
    final DedupeMode m = mode;
    final boolean clean = cleanNames;
    final boolean partialFlag = isPartial;
    // 去重计算移入后台线程：万级书源在主线程做 URL 规范化+分组+排序会 ANR/闪退。
    // 用单线程串行执行：切换模式/开关时可能连续触发多次 recompute，
    // 每次 new Thread 会同时跑 N 份万级去重，内存和 CPU 都会被打满。
    recomputeExecutor.execute(() -> {
      // 已有更新的请求在排队，这一轮结果必然被丢弃，直接跳过省掉一次全量计算
      if (gen != state.recomputeGeneration()) return;
      // markEmptyShell：把「搜索与发现链路都无效」的空壳源判为无效（Issue #1 二.1）。
      // 用 2257 + 1295 条真实书源验证过判定准确（0 误判），
      // 用户明确要求"发现链路与搜索链路同时缺失的列为错误源"。
      final DedupeResult base = DedupeEngine.run(filteredCopy, m, clean, true);
      runOnUiThread(() -> {
        if (state.isDestroyed() || gen != state.recomputeGeneration()) return; // Activity 已销毁，或已有更新的计算结果
        List<InvalidSource> all = new ArrayList<>(base.getInvalid());
        synchronized (extraInvalid) { all.addAll(extraInvalid); }
        result = new DedupeResult(filteredCopy.size(), base.getRetained(), base.getDuplicateGroups(), all);
        partial = partialFlag;
        setTaskRunning(false);
        cardRunning.setVisibility(View.GONE);
        cardResult.setVisibility(View.VISIBLE);
        boolean hasRetained = !result.getRetained().isEmpty();
        // 校验入口始终可点击；无书源时 showCheckDialog 会给出明确提示，避免灰色看起来像未实现
        btnCheck.setEnabled(true);
        btnImport.setEnabled(hasRetained);
        btnSave.setEnabled(hasRetained);
        tvStats.setText(ResultSummary.format(m, localCount, networkCount, result, partialFlag));        setStatValue(R.id.tileOriginal, result.getOriginalCount());
        setStatValue(R.id.tileDup, result.getDuplicateCount());
        setStatValue(R.id.tileKept, result.getRetained().size());
        setStatValue(R.id.tileBad, result.getInvalid().size());
        if (!checkResults.isEmpty()) renderCheckStats();
        else if (!all.isEmpty() && !hasRetained) {
          // 无有效结果时展示首条错误，便于定位抓取/解析失败
          tvCheckStats.setVisibility(View.VISIBLE);
          if (rowCheckTiles != null) rowCheckTiles.setVisibility(View.GONE);
          switchOnlyUsable.setVisibility(View.GONE);
          InvalidSource first = all.get(0);
          String detail = first.getDetail() == null ? first.getKind().name() : first.getDetail();
          if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
          tvCheckStats.setText("错误：" + detail);
          renderKindExportSwitches();
        } else {
          tvCheckStats.setVisibility(View.GONE);
          if (rowCheckTiles != null) rowCheckTiles.setVisibility(View.GONE);
          switchOnlyUsable.setVisibility(View.GONE);
          renderKindExportSwitches();
        }
        // 去重结果更新后落盘（后台线程），这样冷启动能恢复到这一步
        persistSessionAsync();
      });
    });
  }

  private void setTaskRunning(boolean running) {
    btnParse.setEnabled(!running);
    btnCheck.setEnabled(!running);
    btnImport.setEnabled(!running && result != null && !result.getRetained().isEmpty());
    btnSave.setEnabled(!running && result != null && !result.getRetained().isEmpty());
    btnStop.setEnabled(running && !state.isCheckCancelRequested());
    if (!running) btnStop.setText("停止");
    for (int i = 0; i < modeGroup.getChildCount(); i++) modeGroup.getChildAt(i).setEnabled(!running);
    switchCleanNames.setEnabled(!running);
    if (switchCleanLogin != null) switchCleanLogin.setEnabled(!running);
    if (switchDeleteCaptcha != null) switchDeleteCaptcha.setEnabled(!running);
    sliderConcurrency.setEnabled(!running);
    for (KindRow row : kindRows.values()) {
      if (row.sw != null) row.sw.setEnabled(!running);
    }
  }

  private void clearAll() {
    if (fetchManager != null) { state.setDiscard(true); fetchManager.cancel(false); }
    if (checkEngine != null) { checkEngine.cancel(); checkEngine = null; }
    state.invalidateAll();
    synchronized (buckets) { buckets.clearAll(); }
    orderAssigner.reset();
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    localFileCount = 0; result = null; checkResults.clear(); partial = false;
    refreshCheckSearchFilter();
    state.setFetchRunning(false); state.finishCheck();
    updateBackgroundTask("");
    // 统计卡片和导出预览也要归零，否则清空后"有效 1200 · 重复 340"还挂在屏幕上
    setStatValue(R.id.tileOriginal, 0);
    setStatValue(R.id.tileDup, 0);
    setStatValue(R.id.tileKept, 0);
    setStatValue(R.id.tileBad, 0);
    setStatValue(R.id.tileOk, 0);
    setStatValue(R.id.tileFail, 0);
    setStatValue(R.id.tileTimeout, 0);
    if (tvStats != null) tvStats.setText("");
    if (tvCheckStats != null) tvCheckStats.setVisibility(View.GONE);
    if (tvFailReasons != null) tvFailReasons.setVisibility(View.GONE);
    if (rowCheckTiles != null) rowCheckTiles.setVisibility(View.GONE);
    if (tilCheckSearch != null) tilCheckSearch.setVisibility(View.GONE);
    if (btnCheckDetail != null) btnCheckDetail.setVisibility(View.GONE);
    if (btnExport != null) btnExport.setVisibility(View.GONE);
    renderKindExportSwitches();
    refreshExportPreview();
    etUrls.setText(""); tvLocalStatus.setVisibility(View.GONE); cardResult.setVisibility(View.GONE); cardRunning.setVisibility(View.GONE);
    toast("已清空本地和网络书源");
  }

  private void readChosen(Uri u) {
    new Thread(() -> {
      final String name;
      final SourceParser.ParseResult p;
      try (InputStream in = getContentResolver().openInputStream(u); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192]; int n; int total = 0;
        while ((n = in.read(b)) >= 0) {
          total += n;
          if (total > MAX_LOCAL_IMPORT_BYTES) throw new IOException("文件过大（超过 64MB），请拆分后分批导入");
          o.write(b, 0, n);
        }
        name = nameOf(u);
        // 读文件与 JSON 解析都在后台线程执行，避免大文件阻塞主线程（ANR/OOM 卡顿）；
        // order 用占位值 0，提交时在 buckets 锁内统一分配全局顺序号。
        p = SourceParser.parseArray(o.toString(StandardCharsets.UTF_8.name()), 0);
      } catch (Exception e) {
        runOnUiThread(() -> { if (!state.isDestroyed()) toast("文件读取失败：" + e.getMessage()); });
        return;
      }
      runOnUiThread(() -> { if (state.isDestroyed()) return; commitLocal(name, p); });
    }, "local-import").start();
  }

  private String nameOf(Uri u) {
    String name = u.getLastPathSegment();
    try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (i >= 0) name = c.getString(i);
      }
    } catch (Exception ignored) {}
    return name == null ? "json" : name;
  }

  /** 在 UI 线程提交后台解析完成的本地书源（order 在 buckets 锁内统一分配）。 */
  private void commitLocal(String name, SourceParser.ParseResult p) {
    operationMode.start(); mode = operationMode.resultMode();
    synchronized (buckets) { buckets.addLocal(reorder(p.getRecords())); }
    synchronized (extraInvalid) { extraInvalid.addAll(p.getInvalid()); }
    if (!p.getRecords().isEmpty()) {
      localFileCount++;
      tvLocalStatus.setVisibility(View.VISIBLE);
      tvLocalStatus.setText("已添加本地 JSON：" + localFileCount + " 个文件 · " + buckets.localCount() + " 条书源");
    }
    recompute(false);
  }

  /** 直接提交一组 SourceRecord（无需构造 ParseResult）到去重 buckets，复用 commitLocal 流程。 */
  private void commitRecords(String label, List<SourceRecord> records) {
    if (records == null || records.isEmpty()) return;
    operationMode.start(); mode = operationMode.resultMode();
    synchronized (buckets) { buckets.addLocal(reorder(records)); }
    localFileCount++;
    tvLocalStatus.setVisibility(View.VISIBLE);
    tvLocalStatus.setText("已添加本地 JSON：" + localFileCount + " 个文件 · " + buckets.localCount() + " 条书源");
    recompute(false);
  }

  private SharedPreferences checkPrefs() {
    return getSharedPreferences("check", MODE_PRIVATE);
  }

  private SharedPreferences appPrefs() {
    return getSharedPreferences("main", MODE_PRIVATE);
  }

  /** 恢复上次的 URL 输入、去重模式、开关与并发数。 */
  private void restorePrefs() {
    SharedPreferences p = appPrefs();
    String urls = p.getString("urls", "");
    if (!urls.isEmpty() && etUrls != null) etUrls.setText(urls);
    String m = p.getString("mode", DedupeMode.STANDARD.name());
    int checkId = R.id.modeStandard;
    if (DedupeMode.STRICT.name().equals(m)) checkId = R.id.modeStrict;
    else if (DedupeMode.NAME.name().equals(m)) checkId = R.id.modeName;
    else if (DedupeMode.AGGRESSIVE.name().equals(m)) checkId = R.id.modeAggressive;
    if (modeGroup != null) modeGroup.check(checkId);
    if (switchCleanNames != null) switchCleanNames.setChecked(p.getBoolean("clean", false));
    if (switchOnlyUsable != null) switchOnlyUsable.setChecked(p.getBoolean("onlyUsable", false));
    if (switchCleanLogin != null) switchCleanLogin.setChecked(checkSettings.cleanLogin);
    if (switchDeleteCaptcha != null) switchDeleteCaptcha.setChecked(checkSettings.deleteCaptcha);
    if (sliderConcurrency != null) {
      sliderConcurrency.setValue(Math.max(1f, Math.min(5f, (float) p.getInt("concurrency", 4))));
    }
    if (btnUrlHistory != null) btnUrlHistory.setVisibility(getUrlHistory().isEmpty() ? View.GONE : View.VISIBLE);
  }

  /**
   * URL 输入框内容最多保存多少字符。
   *
   * SharedPreferences 是整个 XML 一次性读写，塞进几百 KB 会让每次
   * savePrefs() 都变慢（onDestroy 也会调，退出时卡一下）。
   * 8KB 够存几百个 URL，超出就只保留前 8KB——恢复时用户能看个大概，
   * 但不会把 prefs 撑大。
   */
  private static final int MAX_SAVED_URLS_CHARS = 8 * 1024;

  /** 保存当前 URL 输入、模式、开关与并发数，下次打开自动恢复。 */
  private void savePrefs() {
    CharSequence input = etUrls.getText();
    String urls = input == null ? "" : input.toString();
    if (urls.length() > MAX_SAVED_URLS_CHARS) {
      urls = urls.substring(0, MAX_SAVED_URLS_CHARS);
    }
    appPrefs().edit()
        .putString("urls", urls)
        .putString("mode", mode.name())
        .putBoolean("clean", cleanNames)
        .putBoolean("onlyUsable", onlyUsable)
        .putInt("concurrency", concurrency)
        .apply();
  }

  /** 最近使用的 URL 地址（最多 10 条，最新在前）。 */
  private List<String> getUrlHistory() {
    return UrlHistory.parse(appPrefs().getString("urlHistory", ""));
  }

  private void rememberUrls(List<String> urls) {
    if (urls == null || urls.isEmpty()) return;
    List<String> hist = UrlHistory.merge(getUrlHistory(), urls);
    appPrefs().edit().putString("urlHistory", UrlHistory.format(hist)).apply();
    if (btnUrlHistory != null) btnUrlHistory.setVisibility(View.VISIBLE);
  }

  /** 历史地址选择：点击追加到输入框，可清空历史。 */
  private void showUrlHistory() {
    final List<String> hist = getUrlHistory();
    if (hist.isEmpty()) { toast("暂无历史地址"); return; }
    new MaterialAlertDialogBuilder(this)
        .setTitle("最近使用的地址")
        .setItems(hist.toArray(new String[0]), (d, w) -> {
          String u = hist.get(w);
          CharSequence cur = etUrls.getText();
          String s = cur == null ? "" : cur.toString().trim();
          etUrls.setText(s.isEmpty() ? u : s + "\n" + u);
        })
        .setNegativeButton("清空历史", (d, w) -> {
          appPrefs().edit().remove("urlHistory").apply();
          if (btnUrlHistory != null) btnUrlHistory.setVisibility(View.GONE);
        })
        .setPositiveButton("关闭", null)
        .show();
  }

  private void loadCheckSettings() {
    SharedPreferences p = checkPrefs();
    checkSettings.timeoutSeconds = p.getInt("timeout", CheckSourceSettings.DEFAULT_TIMEOUT);
    checkSettings.concurrency = p.getInt("concurrency", CheckSourceSettings.DEFAULT_CONCURRENCY);
    checkSettings.keyword = p.getString("keyword", CheckSourceSettings.DEFAULT_KEYWORD);
    checkSettings.okStatusRanges = p.getString("okStatus", CheckSourceSettings.DEFAULT_OK_STATUS);
    checkSettings.quickMode = p.getBoolean("quickMode", false);
    // 过滤开关默认关闭。v3.7.4 之前默认是 true，老用户升级后 prefs 里已存着 true，
    // 光改默认值不够——必须做一次性迁移，否则他们会继续被剔掉书源。
    checkSettings.deleteCaptcha = p.getBoolean("deleteCaptcha", false);
    checkSettings.cleanLogin = p.getBoolean("cleanLogin", false);
    migrateFilterDefaultsOnce(p);
    checkSettings.checkSearch = p.getBoolean("search", true);
    checkSettings.checkDiscovery = p.getBoolean("discovery", true);
    checkSettings.checkInfo = p.getBoolean("info", true);
    checkSettings.checkCategory = p.getBoolean("category", true);
    checkSettings.checkContent = p.getBoolean("content", true);
    checkSettings.checkNovel = p.getBoolean("kindNovel", true);
    checkSettings.checkComic = p.getBoolean("kindComic", true);
    checkSettings.checkVideo = p.getBoolean("kindVideo", true);
    checkSettings.checkAudio = p.getBoolean("kindAudio", true);
    checkSettings.checkFile = p.getBoolean("kindFile", true);
    checkSettings.normalize();
  }

  /**
   * 一次性迁移：把旧版本默认开启的过滤开关重置为关闭。
   *
   * 背景（Issue #1）：v3.7.3 及之前，`deleteCaptcha` / `cleanLogin` 默认是 true，
   * 用户什么都没做就会在去重阶段丢掉所有带 loginUrl / captchaUrl 的书源。
   * 改成默认 false 后，老用户的 prefs 里仍存着 true，不迁移的话他们升级后
   * 行为不变，等于修复没生效。
   *
   * 用独立的迁移标记而不是比对值——用户如果真的是自己勾选的，我们无权改；
   * 但无法区分"自己勾的"和"旧默认值"，所以只在首次升级时重置一次，
   * 之后用户再勾选就会被尊重。
   */
  private void migrateFilterDefaultsOnce(SharedPreferences p) {
    final String key = "filterDefaultsMigrated_v374";
    if (p.getBoolean(key, false)) return;
    p.edit()
        .putBoolean("deleteCaptcha", false)
        .putBoolean("cleanLogin", false)
        .putBoolean(key, true)
        .apply();
    checkSettings.deleteCaptcha = false;
    checkSettings.cleanLogin = false;
  }

  private void saveCheckSettings() {
    checkSettings.normalize();
    checkPrefs().edit()
        .putInt("timeout", (int) checkSettings.timeoutSeconds)
        .putInt("concurrency", checkSettings.concurrency)
        .putString("keyword", checkSettings.keyword)
        .putString("okStatus", checkSettings.okStatusRanges)
        .putBoolean("quickMode", checkSettings.quickMode)
        .putBoolean("deleteCaptcha", checkSettings.deleteCaptcha)
        .putBoolean("cleanLogin", checkSettings.cleanLogin)
        .putBoolean("search", checkSettings.checkSearch)
        .putBoolean("discovery", checkSettings.checkDiscovery)
        .putBoolean("info", checkSettings.checkInfo)
        .putBoolean("category", checkSettings.checkCategory)
        .putBoolean("content", checkSettings.checkContent)
        .putBoolean("kindNovel", checkSettings.checkNovel)
        .putBoolean("kindComic", checkSettings.checkComic)
        .putBoolean("kindVideo", checkSettings.checkVideo)
        .putBoolean("kindAudio", checkSettings.checkAudio)
        .putBoolean("kindFile", checkSettings.checkFile)
        .apply();
  }

  private void bindCheckDialog(View v) {
    Slider slider = v.findViewById(R.id.sliderTimeout);
    TextView label = v.findViewById(R.id.tvTimeoutLabel);
    Slider checkConcurrencySlider = v.findViewById(R.id.sliderCheckConcurrency);
    TextView checkConcurrencyLabel = v.findViewById(R.id.tvCheckConcurrencyLabel);
    MaterialCheckBox cbSearch = v.findViewById(R.id.cbSearch);
    MaterialCheckBox cbDiscovery = v.findViewById(R.id.cbDiscovery);
    MaterialCheckBox cbInfo = v.findViewById(R.id.cbInfo);
    MaterialCheckBox cbCategory = v.findViewById(R.id.cbCategory);
    MaterialCheckBox cbContent = v.findViewById(R.id.cbContent);
    TextInputLayout tilKeyword = v.findViewById(R.id.tilCheckKeyword);
    TextInputEditText etKeyword = v.findViewById(R.id.etCheckKeyword);
    MaterialCheckBox cbKindNovel = v.findViewById(R.id.cbKindNovel);
    MaterialCheckBox cbKindComic = v.findViewById(R.id.cbKindComic);
    MaterialCheckBox cbKindVideo = v.findViewById(R.id.cbKindVideo);
    MaterialCheckBox cbKindAudio = v.findViewById(R.id.cbKindAudio);
    MaterialCheckBox cbKindFile = v.findViewById(R.id.cbKindFile);

    // 按滑条自身范围钳制：还原自手改备份的越界值会让 Slider.setValue 抛 IllegalStateException
    slider.setValue(Math.max(slider.getValueFrom(), Math.min(slider.getValueTo(), checkSettings.timeoutSeconds)));
    label.setText("校验超时(秒)：" + checkSettings.timeoutSeconds);
    checkConcurrencySlider.setValue(Math.max(checkConcurrencySlider.getValueFrom(),
        Math.min(checkConcurrencySlider.getValueTo(), checkSettings.concurrency)));
    checkConcurrencyLabel.setText("并发校验数量：" + checkSettings.concurrency);
    cbSearch.setChecked(checkSettings.checkSearch);
    cbDiscovery.setChecked(checkSettings.checkDiscovery);
    cbInfo.setChecked(checkSettings.checkInfo);
    cbCategory.setChecked(checkSettings.checkCategory);
    cbContent.setChecked(checkSettings.checkContent);
    etKeyword.setText(checkSettings.keyword);
    tilKeyword.setEnabled(checkSettings.checkSearch);
    etKeyword.setEnabled(checkSettings.checkSearch);
    cbKindNovel.setChecked(checkSettings.checkNovel);
    cbKindComic.setChecked(checkSettings.checkComic);
    cbKindVideo.setChecked(checkSettings.checkVideo);
    cbKindAudio.setChecked(checkSettings.checkAudio);
    cbKindFile.setChecked(checkSettings.checkFile);
    cbCategory.setEnabled(cbInfo.isChecked());
    cbContent.setEnabled(cbInfo.isChecked() && cbCategory.isChecked());
    MaterialCheckBox cbQuickMode = v.findViewById(R.id.cbQuickMode);
    if (cbQuickMode != null) cbQuickMode.setChecked(checkSettings.quickMode);
    TextInputEditText etOkStatus = v.findViewById(R.id.etOkStatus);
    if (etOkStatus != null) etOkStatus.setText(checkSettings.okStatusRanges);
  }

  private void collectCheckDialog(View v) {
    Slider slider = v.findViewById(R.id.sliderTimeout);
    Slider checkConcurrencySlider = v.findViewById(R.id.sliderCheckConcurrency);
    MaterialCheckBox cbSearch = v.findViewById(R.id.cbSearch);
    MaterialCheckBox cbDiscovery = v.findViewById(R.id.cbDiscovery);
    MaterialCheckBox cbInfo = v.findViewById(R.id.cbInfo);
    MaterialCheckBox cbCategory = v.findViewById(R.id.cbCategory);
    MaterialCheckBox cbContent = v.findViewById(R.id.cbContent);
    TextInputEditText etKeyword = v.findViewById(R.id.etCheckKeyword);
    checkSettings.timeoutSeconds = Math.round(slider.getValue());
    if (checkSettings.timeoutSeconds <= 0) checkSettings.timeoutSeconds = CheckSourceSettings.DEFAULT_TIMEOUT;
    checkSettings.concurrency = Math.round(checkConcurrencySlider.getValue());
    checkSettings.checkSearch = cbSearch.isChecked();
    checkSettings.checkDiscovery = cbDiscovery.isChecked();
    checkSettings.checkInfo = cbInfo.isChecked();
    checkSettings.checkCategory = cbCategory.isChecked();
    checkSettings.checkContent = cbContent.isChecked();
    checkSettings.keyword = etKeyword.getText() == null ? "" : etKeyword.getText().toString();
    checkSettings.checkNovel = ((MaterialCheckBox) v.findViewById(R.id.cbKindNovel)).isChecked();
    checkSettings.checkComic = ((MaterialCheckBox) v.findViewById(R.id.cbKindComic)).isChecked();
    checkSettings.checkVideo = ((MaterialCheckBox) v.findViewById(R.id.cbKindVideo)).isChecked();
    checkSettings.checkAudio = ((MaterialCheckBox) v.findViewById(R.id.cbKindAudio)).isChecked();
    checkSettings.checkFile = ((MaterialCheckBox) v.findViewById(R.id.cbKindFile)).isChecked();
    MaterialCheckBox cbQuickMode = v.findViewById(R.id.cbQuickMode);
    if (cbQuickMode != null) checkSettings.quickMode = cbQuickMode.isChecked();
    TextInputEditText etOkStatus = v.findViewById(R.id.etOkStatus);
    if (etOkStatus != null) {
      checkSettings.okStatusRanges = etOkStatus.getText() == null ? "" : etOkStatus.getText().toString().trim();
    }
    checkSettings.normalize();
  }

  private void showCheckDialog() {
    if (result == null || result.getRetained().isEmpty()) { toast("请先解析网络源或选择 JSON 文件，再进行校验"); return; }
    BottomSheetDialog dialog = new BottomSheetDialog(this);
    View v = getLayoutInflater().inflate(R.layout.dialog_check_source, null);
    dialog.setContentView(v);
    Slider slider = v.findViewById(R.id.sliderTimeout);
    TextView label = v.findViewById(R.id.tvTimeoutLabel);
    Slider checkConcurrencySlider = v.findViewById(R.id.sliderCheckConcurrency);
    TextView checkConcurrencyLabel = v.findViewById(R.id.tvCheckConcurrencyLabel);
    MaterialCheckBox cbSearch = v.findViewById(R.id.cbSearch);
    MaterialCheckBox cbInfo = v.findViewById(R.id.cbInfo);
    MaterialCheckBox cbCategory = v.findViewById(R.id.cbCategory);
    MaterialCheckBox cbContent = v.findViewById(R.id.cbContent);
    TextInputLayout tilKeyword = v.findViewById(R.id.tilCheckKeyword);
    TextInputEditText etKeyword = v.findViewById(R.id.etCheckKeyword);
    bindCheckDialog(v);
    slider.addOnChangeListener((s, value, fromUser) -> label.setText("校验超时(秒)：" + Math.round(value)));
    checkConcurrencySlider.addOnChangeListener((s, value, fromUser) ->
        checkConcurrencyLabel.setText("并发校验数量：" + Math.round(value)));
    cbSearch.setOnCheckedChangeListener((b, on) -> {
      tilKeyword.setEnabled(on);
      etKeyword.setEnabled(on);
    });
    cbInfo.setOnCheckedChangeListener((b, on) -> { cbCategory.setEnabled(on); if (!on) { cbCategory.setChecked(false); cbContent.setChecked(false);} });
    cbCategory.setOnCheckedChangeListener((b, on) -> { cbContent.setEnabled(on && cbInfo.isChecked()); if (!on) cbContent.setChecked(false); });
    v.findViewById(R.id.btnReset).setOnClickListener(x -> {
      checkSettings.resetToDefaults();
      saveCheckSettings();
      bindCheckDialog(v);
    });
    v.findViewById(R.id.btnCancel).setOnClickListener(x -> dialog.dismiss());
    v.findViewById(R.id.btnOk).setOnClickListener(x -> {
      collectCheckDialog(v);
      saveCheckSettings();
      dialog.dismiss();
      runCheck(checkSettings);
    });
    dialog.show();
  }

  private void runCheck(CheckSourceSettings settings) {
    runCheck(settings, result == null ? new ArrayList<>() : new ArrayList<>(result.getRetained()));
  }

  private void runCheck(CheckSourceSettings settings, java.util.List<SourceRecord> targetList) {
    // 运行守卫：解析/校验进行中时，右上角菜单的重验入口仍可点到这里，放行会并发启动第二个引擎
    if (state.isFetchRunning() || state.isCheckRunning()) { toast("已有任务在运行，请先停止再发起校验"); return; }
    List<SourceRecord> targets = new ArrayList<>(targetList);
    final int gen = state.startCheck(); // 本轮校验代际号，旧引擎的回调据此作废
    state.setFetchRunning(false);
    // startCheck() 已置 running=true 与 cancel=false
    checkResults.clear();
    refreshCheckSearchFilter();
    // 删除弹窗验证码的源 + 清理需要登录的源（顺序与统计规则见 CheckTargetPreparer）
    CheckTargetPreparer.Prepared prepared = CheckTargetPreparer.prepare(targets, settings);
    final java.util.List<SourceRecord> finalTargets = prepared.targets;
    int incompleteRules = prepared.incompleteRules;
    if (incompleteRules > 0) toast("校验前诊断：" + incompleteRules + " 条书源缺少部分规则，结果会标明失败步骤");
    cardRunning.setVisibility(View.VISIBLE);
    setTaskRunning(true);
    btnStop.setText("停止校验");
    btnStop.setEnabled(true);
    updateBackgroundTask("后台校验 0 / " + finalTargets.size());
    tvProgress.setText("正在校验书源 0 / " + finalTargets.size());
    tvStatus.setText("超时 " + settings.timeoutSeconds + "s · 并发 " + settings.concurrency
        + " · 关键词 " + settings.keyword);
    progressBar.setProgressCompat(0, false);
    final CheckSourceEngine engine = new CheckSourceEngine(settings.concurrency);
    checkEngine = engine;
    new Thread(() -> engine.checkAll(finalTargets, settings, new CheckSourceEngine.Listener() {
      @Override public void onProgress(int done, int total, String currentName) {
        runOnUiThread(() -> {
          if (state.isDestroyed() || gen != state.checkGeneration()) return;
          progressBar.setProgressCompat(done * 100 / Math.max(1, total), true);
          tvProgress.setText("正在校验书源 " + done + " / " + total);
          tvStatus.setText(currentName == null ? "" : currentName);
          updateBackgroundTask("后台校验 " + done + " / " + total);
        });
      }
      @Override public void onItem(CheckSourceResult r) {}
      @Override public void onFinished(List<CheckSourceResult> results) {
        runOnUiThread(() -> {
          // 代际守卫：清空、重新解析或发起新一轮校验之后，旧引擎的收尾不能再写结果，
          // 否则会把上一批的 checkResults / 历史 / 健康数据盖回来，并提前解禁按钮。
          if (state.isDestroyed() || gen != state.checkGeneration()) return;
          boolean wasCancelled = state.isCheckCancelRequested() || engine.isCancelled();
          checkResults = results;
          refreshCheckSearchFilter();
          if (wasCancelled) {
            Set<String> completed = new HashSet<>();
            for (CheckSourceResult item : results) if (item.source.getUrl() != null) completed.add(item.source.getUrl());
            pendingCheckUrls.clear();
            for (SourceRecord source : finalTargets) {
              if (source.getUrl() != null && !completed.contains(source.getUrl())) pendingCheckUrls.add(source.getUrl());
            }
          } else {
            pendingCheckUrls.clear();
          }
          persistPendingCheckUrls();
          rememberCheckHistory(results);
          state.finishCheck();
          if (checkEngine == engine) checkEngine = null;
          updateBackgroundTask("");
          setTaskRunning(false);
          cardRunning.setVisibility(View.GONE);
          switchOnlyUsable.setVisibility(checkResults.isEmpty() ? View.GONE : View.VISIBLE);
          renderCheckStats();
          // 记录书源健康数据
          healthTracker.recordBatch(finalTargets, results);
          saveHealthData();
          persistSessionAsync();
          toast(wasCancelled ? "校验已停止，保留已完成 " + results.size() + " 条" : "校验完成");
          // 识别「失败是不是网络环境（代理/VPN）造成的」——
          // 实测同一批书源开代理后可用数从 857 掉到 626，用户会误以为是书源变差。
          EnvironmentSuspicion.Verdict env = EnvironmentSuspicion.analyze(checkResults);
          if (env.suspectedEnvironment && !state.isDestroyed() && !isFinishing()) {
            // 注意：这里在 runOnUiThread 的 lambda 内，this 指向 lambda，
            // 必须写 MainActivity.this 才能拿到 Activity 作为 Context
            new MaterialAlertDialogBuilder(MainActivity.this)
                .setTitle("失败可能不是书源的问题")
                .setMessage(env.advice)
                .setNegativeButton("知道了", null)
                .setPositiveButton("查看失败明细", (dd, ww) -> showCheckDetail())
                .show();
          }
        });
      }
    }), "source-check").start();
  }

  private void renderCheckStats() {
    // 统计与原因归一化抽到 CheckStats（可单测）
    CheckStats stats = CheckStats.summarize(checkResults);
    int ok = stats.ok, fail = stats.fail, timeout = stats.timeout;
    Map<String, Integer> reasonCount = stats.reasonCount;

    tvCheckStats.setVisibility(View.VISIBLE);
    tvCheckStats.setText(getString(R.string.check_result_title));
    if (rowCheckTiles != null) rowCheckTiles.setVisibility(View.VISIBLE);
    setStatValue(R.id.tileOk, ok);
    setStatValue(R.id.tileFail, fail);
    setStatValue(R.id.tileTimeout, timeout);
    // 失败原因 Top 统计
    if (tvFailReasons != null) {
      if (fail > 0 && !reasonCount.isEmpty()) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(reasonCount.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("失败原因：");
        for (int c = 0; c < Math.min(3, sorted.size()); c++) {
          if (c > 0) sb.append(" · ");
          sb.append(sorted.get(c).getKey()).append(" ").append(sorted.get(c).getValue());
        }
        // 关键区分：告诉用户 1390 条失败里有多少是真的站点已死。
        // 没有这行时用户只看到"失败 1390"，会以为书源大面积失效；
        // 实际上大部分是 403 反爬/路径变化，站点还活着。
        UsabilityBuckets b = UsabilityBuckets.split(checkResults);
        if (b.siteDead.size() > 0) {
          sb.append("\n站点已死 ").append(b.siteDead.size())
              .append(" 条（DNS 失败/连接被拒，可清理）");
        }
        if (b.siteAlive.size() > 0) {
          sb.append(" · 站点在线但规则失效 ").append(b.siteAlive.size())
              .append(" 条（403反爬/路径变化，建议保留）");
        }
        tvFailReasons.setText(sb.toString());
        tvFailReasons.setVisibility(View.VISIBLE);
      } else {
        tvFailReasons.setVisibility(View.GONE);
      }
    }
    if (btnCheckDetail != null) {
      btnCheckDetail.setVisibility(checkResults.isEmpty() ? View.GONE : View.VISIBLE);
    }
    // 搜索框：有校验结果时显示
    if (tilCheckSearch != null) tilCheckSearch.setVisibility(checkResults.isEmpty() ? View.GONE : View.VISIBLE);
    // 导出按钮（含 分类导出 / CSV / 合并同域）：有校验结果时显示
    boolean hasCheck = !checkResults.isEmpty();
    if (btnExport != null) btnExport.setVisibility(hasCheck ? View.VISIBLE : View.GONE);
    renderKindExportSwitches();
  }

  private void bindKindRow(View root, int rowId, SourceKind kind, int colorRes) {
    View rowView = root.findViewById(rowId);
    KindRow row = new KindRow(rowView);
    int color = ContextCompat.getColor(this, colorRes);
    row.rail.setBackgroundColor(color);
    row.bar.setIndicatorColor(color);
    row.title.setText(kind.label);
    row.sw.setChecked(checkPrefs().getBoolean("import" + kind.name(), true));
    row.sw.setOnCheckedChangeListener((b, on) -> {
      checkPrefs().edit().putBoolean("import" + kind.name(), on).apply();
      refreshExportPreview();
    });
    kindRows.put(kind, row);
  }

  private void bindStatTile(View tile, String label, int colorRes) {
    if (tile == null) return;
    TextView value = tile.findViewById(R.id.tvStatValue);
    TextView name = tile.findViewById(R.id.tvStatLabel);
    if (name != null) name.setText(label);
    if (value != null) value.setTextColor(ContextCompat.getColor(this, colorRes));
  }

  /** 返回当前显示的校验结果（搜索过滤后的或全部）。 */
  private List<CheckSourceResult> getCheckResultsForDisplay() {
    return filteredCheckResults != null ? filteredCheckResults : checkResults;
  }

  /** 搜索关键词过滤校验结果。 */
  private void applyCheckSearch(String keyword) {
    filteredCheckResults = CheckResultFilter.filter(checkResults, keyword);
  }

  /** checkResults 被替换或清空后必须重新应用过滤，否则明细/导出仍操作旧一轮的列表。 */
  private void refreshCheckSearchFilter() {
    CharSequence kw = etCheckSearch == null || etCheckSearch.getText() == null ? "" : etCheckSearch.getText();
    applyCheckSearch(kw.toString().trim());
  }

  /** 校验明细入口：先选状态筛选，再按耗时降序查看。 */
  private void showCheckDetail() {
    List<CheckSourceResult> list = getCheckResultsForDisplay();
    if (list.isEmpty()) { toast("暂无校验结果"); return; }
    String[] filters = {"全部", "成功", "失败", "超时"};
    new MaterialAlertDialogBuilder(this)
        .setTitle("校验明细（共 " + list.size() + " 条）")
        .setSingleChoiceItems(filters, 0, (d, which) -> {
          showCheckDetailList(which);
          d.dismiss();
        })
        .setNegativeButton("取消", null)
        .show();
  }

  private void showCheckDetailList(int filter) {
    List<CheckSourceResult> list = new ArrayList<>();
    for (CheckSourceResult r : getCheckResultsForDisplay()) {
      if (r.status == CheckSourceResult.Status.SKIPPED) continue;
      if (filter == 1 && r.status != CheckSourceResult.Status.SUCCESS) continue;
      if (filter == 2 && r.status != CheckSourceResult.Status.FAILED) continue;
      if (filter == 3 && r.status != CheckSourceResult.Status.TIMEOUT) continue;
      list.add(r);
    }
    if (list.isEmpty()) { toast("该分类暂无结果"); return; }
    // 按耗时降序（最慢的排前面，便于发现慢源）
    list.sort((a, b) -> Long.compare(b.respondTimeMs, a.respondTimeMs));
    final java.util.Set<Integer> selected = new java.util.TreeSet<>();
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(8), dp(8), dp(8), dp(8));
    final java.util.List<MaterialCheckBox> boxes = new ArrayList<>();
    // 对话框在循环之后才创建，行内「重验」按钮需要在点击时才能拿到它
    final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    // 操作栏固定在弹窗顶部：勾选后导出/复制/移除选中项（无需滑到底部）
    TextView hint = new TextView(this);
    hint.setText("勾选书源后，用上方操作栏 导出 / 复制 / 移除选中项");
    hint.setTextSize(12);
    hint.setTextColor(Color.GRAY);
    hint.setPadding(dp(4), dp(4), dp(4), dp(4));
    content.addView(hint);
    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setPadding(dp(4), dp(4), dp(4), dp(8));
    MaterialButton btnExportSel = new MaterialButton(this);
    btnExportSel.setText("导出选中");
    MaterialButton btnCopySel = new MaterialButton(this);
    btnCopySel.setText("复制选中");
    MaterialButton btnRemoveSel = new MaterialButton(this);
    btnRemoveSel.setText("移除选中");
    actions.addView(btnExportSel);
    actions.addView(btnCopySel);
    actions.addView(btnRemoveSel);
    content.addView(actions);
    // 全选 / 反选按钮行
    LinearLayout selectActions = new LinearLayout(this);
    selectActions.setOrientation(LinearLayout.HORIZONTAL);
    selectActions.setPadding(dp(4), dp(4), dp(4), dp(8));
    MaterialButton btnSelectAll = new MaterialButton(this);
    btnSelectAll.setText("全选");
    MaterialButton btnInvert = new MaterialButton(this);
    btnInvert.setText("反选");
    selectActions.addView(btnSelectAll);
    selectActions.addView(btnInvert);
    content.addView(selectActions);
    int shown = 0;
    for (int idx = 0; idx < list.size(); idx++) {
      if (shown >= 200) break;
      CheckSourceResult r = list.get(idx);
      shown++;
      String statusText, colorStr;
      switch (r.status) {
        case SUCCESS: statusText = "✓"; colorStr = "#00C853"; break;
        case TIMEOUT: statusText = "⏱"; colorStr = "#606060"; break;
        default: statusText = "✗"; colorStr = "#D32F2F";
      }
      String name = r.source.getName();
      if (name == null || name.trim().isEmpty()) name = r.source.getUrl();
      String msg = r.message == null || r.message.trim().isEmpty() ? r.status.name() : r.message.trim();
      MaterialCheckBox cb = new MaterialCheckBox(this);
      cb.setText(statusText + " " + (name == null ? "(无名)" : name) + "   " + r.respondTimeMs + "ms\n    " + msg);
      cb.setTextSize(13);
      cb.setTextColor(Color.parseColor(colorStr));
      cb.setPadding(dp(4), dp(3), dp(4), dp(3));
      final int position = idx;
      cb.setOnCheckedChangeListener((b, on) -> { if (on) selected.add(position); else selected.remove(position); });

      // 每行右侧带一个「重验」按钮：之前只能全量重跑或重验全部失败源，
      // 单独确认一条源是否恢复需要跑一遍全部，浪费时间和校验配额。
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
          0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      row.addView(cb, cbLp);

      MaterialButton btnRecheck = new MaterialButton(this, null,
          com.google.android.material.R.attr.materialButtonOutlinedStyle);
      btnRecheck.setText("重验");
      btnRecheck.setTextSize(12);
      btnRecheck.setMinWidth(0);
      btnRecheck.setMinimumWidth(0);
      btnRecheck.setPadding(dp(10), dp(2), dp(10), dp(2));
      final CheckSourceResult rowResult = r;
      btnRecheck.setOnClickListener(view -> {
        if (state.isCheckRunning()) { toast("校验进行中，请先停止"); return; }
        // 对话框对象在循环之后才创建，这里通过 holding 数组延迟取用
        if (holder[0] != null) holder[0].dismiss();
        recheckSingle(rowResult.source);
      });
      row.addView(btnRecheck, new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

      content.addView(row);
      boxes.add(cb);
    }

    LinearLayout wrapper = new LinearLayout(this);
    wrapper.setOrientation(LinearLayout.VERTICAL);
    wrapper.addView(content);
    final java.util.List<CheckSourceResult> finalList = list;
    btnExportSel.setOnClickListener(v -> {
      List<SourceRecord> recs = new ArrayList<>();
      for (Integer i : selected) recs.add(finalList.get(i).source);
      if (recs.isEmpty()) { toast("请先勾选要导出的书源"); return; }
      saveShareFile(recs);
    });
    btnCopySel.setOnClickListener(v -> {
      List<SourceRecord> recs = new ArrayList<>();
      for (Integer i : selected) recs.add(finalList.get(i).source);
      if (recs.isEmpty()) { toast("请先勾选要复制的书源"); return; }
      shareToClipboard(recs);
    });
    btnRemoveSel.setOnClickListener(v -> {
      if (selected.isEmpty()) { toast("请先勾选要移除的书源"); return; }
      java.util.Set<String> removeUrls = new HashSet<>();
      for (Integer i : selected) {
        String u = finalList.get(i).source.getUrl();
        if (u != null) removeUrls.add(u);
      }
      int actuallyRemoved = removeSourcesFromResult(removeUrls);
      // 提示用真实移除数：勾选里可能包含"已被去重掉的重复源"，
      // 它们本来就不在 retained 中，按勾选数提示会虚报。
      String msg = actuallyRemoved == removeUrls.size()
          ? "已移除 " + actuallyRemoved + " 条选中书源"
          : "已移除 " + actuallyRemoved + " 条（" + (removeUrls.size() - actuallyRemoved) + " 条为已合并的重复源，无需移除）";
      new MaterialAlertDialogBuilder(this).setTitle("已移除").setMessage(msg).setPositiveButton("确定", null).show();
    });
    btnSelectAll.setOnClickListener(v -> {
      selected.clear();
      for (int i = 0; i < boxes.size(); i++) { boxes.get(i).setChecked(true); selected.add(i); }
    });
    btnInvert.setOnClickListener(v -> {
      java.util.Set<Integer> newSelected = new java.util.TreeSet<>();
      for (int i = 0; i < boxes.size(); i++) {
        boolean was = selected.contains(i);
        boxes.get(i).setChecked(!was);
        if (!was) newSelected.add(i);
      }
      selected.clear();
      selected.addAll(newSelected);
    });

    ScrollView sv = new ScrollView(this);
    sv.addView(wrapper);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.65f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    String title = (filter == 1 ? "成功" : filter == 2 ? "失败" : filter == 3 ? "超时" : "全部")
        + " " + list.size() + " 条" + (list.size() > shown ? "（显示前 " + shown + " 条）" : "");
    holder[0] = new MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(sv)
        .setPositiveButton("关闭", null)
        .show();
  }

  /** 从去重结果与校验结果中移除指定 URL 的书源。@return 实际从结果里移除的条数 */
  private int removeSourcesFromResult(java.util.Set<String> urls) {
    if (result == null) { toast("当前无结果"); return 0; }
    ResultPruner.Pruned pruned = ResultPruner.prune(result, checkResults, urls);
    result = pruned.result;
    checkResults = new ArrayList<>(pruned.checkResults);
    // 同步从数据源头移除：否则「重新去重」会把刚删的源全部算回来
    synchronized (buckets) { buckets.removeByUrls(urls); }
    // 同步刷新统计卡片：否则删完书源后"有效/重复"数字还停留在删除前
    setStatValue(R.id.tileKept, result.getRetained().size());
    setStatValue(R.id.tileDup, result.getDuplicateCount());
    refreshCheckSearchFilter();
    renderCheckStats();
    renderKindExportSwitches();
    refreshExportPreview();
    toast("已移除 " + pruned.removedCount + " 条书源");
    return pruned.removedCount;
  }

  private void setStatValue(int tileId, int number) {
    View tile = findViewById(tileId);
    if (tile == null) return;
    TextView value = tile.findViewById(R.id.tvStatValue);
    if (value != null) value.setText(String.valueOf(number));
  }

  /** 点击"重复"统计卡片：查看每个重复组保留了哪个、合并了哪些书源。 */
  private void showDuplicateDetail() {
    if (result == null || result.getDuplicateGroups().isEmpty()) { toast("当前没有重复项"); return; }
    List<DuplicateGroup> groups = result.getDuplicateGroups();
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(20), dp(8), dp(20), dp(8));
    int shown = 0;
    for (DuplicateGroup g : groups) {
      if (shown >= 100) break;
      shown++;
      String keptName = g.getKept().getName();
      if (keptName == null || keptName.trim().isEmpty()) keptName = g.getKept().getUrl();
      StringBuilder sb = new StringBuilder();
      sb.append(shown).append(". ").append(keptName == null ? "(无名)" : keptName).append("\n");
      sb.append("    保留理由：").append(g.getReason()).append("\n");
      // NAME 模式下显示归一化名称
      if (mode == DedupeMode.NAME) {
        String norm = NameSimilarity.normalize(g.getKept().getName());
        if (!norm.isEmpty() && !norm.equals(g.getKept().getName())) {
          sb.append("    归一化名称：").append(norm).append("\n");
        }
        sb.append("    说明：名称模式只影响当前结果，切换模式即可恢复全部原始书源。\n");
      }
      sb.append("    合并 ").append(g.getRemoved().size()).append(" 个：");
      int c = 0;
      for (SourceRecord r : g.getRemoved()) {
        if (c >= 3) { sb.append("…"); break; }
        String n = r.getName();
        if (n == null || n.trim().isEmpty()) n = r.getUrl();
        if (c > 0) sb.append("、");
        sb.append(n == null ? "(无名)" : n);
        // NAME 模式下显示归一化名称
        if (mode == DedupeMode.NAME && n != null) {
          String rNorm = NameSimilarity.normalize(n);
          if (!rNorm.isEmpty() && !rNorm.equals(n)) {
            sb.append("(").append(rNorm).append(")");
          }
          double similarity = NameSimilarity.similarity(NameSimilarity.normalize(g.getKept().getName()), rNorm);
          sb.append("·相似 ").append(Math.round(similarity * 100)).append("%");
          sb.append("·").append(domainLabel(r.getUrl()));
        }
        c++;
      }
      TextView tv = new TextView(this);
      tv.setTextSize(13);
      tv.setTextColor(ContextCompat.getColor(this, R.color.md_theme_on_surface));
      tv.setText(sb.toString());
      tv.setPadding(0, dp(6), 0, dp(6));
      list.addView(tv);
    }
    ScrollView sv = new ScrollView(this);
    sv.addView(list);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this)
        .setTitle((mode == DedupeMode.NAME ? "名称去重安全预览 · " : "重复组 ") + groups.size() + " 个" + (groups.size() > shown ? "（显示前 " + shown + " 个）" : ""))
        .setView(sv)
        .setPositiveButton("关闭", null)
        .show();
  }

  private static String domainLabel(String url) {
    if (url == null || url.trim().isEmpty()) return "无地址";
    try {
      String host = new java.net.URL(url).getHost();
      return host == null || host.isEmpty() ? url : host;
    } catch (Exception ignored) {
      return url;
    }
  }

  private void renderKindExportSwitches() {
    if (boxKindExport == null) return;
    if (result == null || result.getRetained().isEmpty()) {
      boxKindExport.setVisibility(View.GONE);
      return;
    }
    Map<SourceKind, Integer> totals = SourceKind.counts(result.getRetained());
    Map<SourceKind, int[]> checked = new LinkedHashMap<>();
    for (SourceKind k : SourceKind.values()) checked.put(k, new int[]{0, 0});
    for (CheckSourceResult r : checkResults) {
      if (r.status == CheckSourceResult.Status.SKIPPED) continue;
      SourceKind kind = r.kind == null ? SourceKind.NOVEL : r.kind;
      int[] n = checked.get(kind);
      n[1]++;
      if (r.status == CheckSourceResult.Status.SUCCESS) n[0]++;
    }
    boolean any = false;
    for (SourceKind k : SourceKind.values()) {
      KindRow row = kindRows.get(k);
      if (row == null) continue;
      int total = totals.get(k) == null ? 0 : totals.get(k);
      int[] n = checked.get(k);
      if (total <= 0) {
        row.root.setVisibility(View.GONE);
        continue;
      }
      any = true;
      row.root.setVisibility(View.VISIBLE);
      if (!checkResults.isEmpty() && n[1] > 0) {
        int pct = n[1] == 0 ? 0 : Math.round(n[0] * 100f / n[1]);
        row.detail.setText("可用 " + n[0] + " · 共 " + n[1] + " · 通过 " + pct + "%");
        row.bar.setProgressCompat(pct, true);
      } else {
        row.detail.setText("去重后 " + total + " 条，尚未校验");
        row.bar.setProgressCompat(0, false);
      }
    }
    boxKindExport.setVisibility(any ? View.VISIBLE : View.GONE);
    refreshExportPreview();
  }

  private void refreshExportPreview() {
    if (tvExportPreview == null) return;
    int n = exportRecords().size();
    tvExportPreview.setText("当前将导入 / 保存 " + n + " 条");
  }

  private Set<SourceKind> selectedExportKinds() {
    Set<SourceKind> allow = new LinkedHashSet<>();
    for (Map.Entry<SourceKind, KindRow> e : kindRows.entrySet()) {
      KindRow row = e.getValue();
      if (row != null && row.root.getVisibility() == View.VISIBLE && row.sw.isChecked()) allow.add(e.getKey());
    }
    return allow;
  }

  private static final class KindRow {
    final View root;
    final MaterialSwitch sw;
    final TextView title;
    final TextView detail;
    final LinearProgressIndicator bar;
    final View rail;
    KindRow(View root) {
      this.root = root;
      this.sw = root.findViewById(R.id.switchKind);
      this.title = root.findViewById(R.id.tvKindTitle);
      this.detail = root.findViewById(R.id.tvKindDetail);
      this.bar = root.findViewById(R.id.barKind);
      this.rail = root.findViewById(R.id.kindRail);
    }
  }

  private List<SourceRecord> exportRecords() {
    if (result == null) return Collections.emptyList();
    return ExportRecordSelector.select(
        result.getRetained(), checkResults, onlyUsable, selectedExportKinds());
  }

  private List<Object> exportPayload() {
    return exportPayload(exportRecords());
  }

  private List<Object> exportPayload(List<SourceRecord> list) {
    return ExportPayloadBuilder.build(list, checkResults, todayStamp());
  }

  private String exportJson(List<SourceRecord> list) {
    return ExportPayloadBuilder.toJson(list, checkResults, todayStamp());
  }

  /** 导出用的日期标记，如 "2026-11-24"。 */
  private static String todayStamp() {
    return new SimpleDateFormat("yyyy-M-d", Locale.CHINA).format(new Date());
  }

  private void importReader() {
    List<SourceRecord> candidates = exportRecords();
    if (candidates.isEmpty()) { toast("没有可导入的数据，请打开要导入的类型开关"); return; }
    startReaderImport(candidates);
  }

  private void startReaderImport(List<SourceRecord> list) {
    toast("正在生成书源数据，请稍候…");
    // 万级书源序列化在后台线程执行，避免主线程卡死/闪退
    new Thread(() -> {
      final String json = exportJson(list);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        if (json == null) { toast("没有可导入的数据"); return; }
        try {
          // Reading fetches this URL immediately before showing its native BatchImportDialog.
          // Loopback avoids Binder-size limits and never uploads the source JSON to a third party.
          String importUrl = ReaderImportService.prepare(this, json, 2L * 60L * 1000L);
          Uri deepLink = Uri.parse("legado://import/bookSource").buildUpon()
              .appendQueryParameter("src", importUrl)
              .build();
          Intent view = new Intent(Intent.ACTION_VIEW, deepLink);
          view.addCategory(Intent.CATEGORY_BROWSABLE);
          List<android.content.pm.ResolveInfo> readers = getPackageManager().queryIntentActivities(
              view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
          if (readers.isEmpty()) throw new ActivityNotFoundException("未找到支持 legado:// 导入的阅读 App");
          // 多阅读分支支持
          if (readers.size() > 1) {
            // 每次导入都弹选择框，显示名称+包名+图标方便区分
            final String[] displayNames = new String[readers.size()];
            final android.graphics.drawable.Drawable[] iconDrawables = new android.graphics.drawable.Drawable[readers.size()];
            for (int i = 0; i < readers.size(); i++) {
              android.content.pm.ResolveInfo ri = readers.get(i);
              displayNames[i] = ri.loadLabel(getPackageManager()).toString() + "\n    " + ri.activityInfo.packageName;
              iconDrawables[i] = ri.loadIcon(getPackageManager());
            }
            // 自定义列表适配器显示图标
            android.widget.ListAdapter adapter = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayNames) {
              @Override
              public android.view.View getView(int pos, android.view.View v, android.view.ViewGroup p) {
                android.view.View row = super.getView(pos, v, p);
                android.widget.TextView tv = (android.widget.TextView) row;
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(iconDrawables[pos], null, null, null);
                tv.setCompoundDrawablePadding(dp(12));
                tv.setPadding(dp(4), dp(6), dp(4), dp(6));
                return row;
              }
            };
            new MaterialAlertDialogBuilder(this)
                .setTitle("选择阅读分支")
                .setAdapter(adapter, (d, w) -> {
                  android.content.pm.ResolveInfo ri = readers.get(w);
                  Intent specific = new Intent(Intent.ACTION_VIEW, deepLink);
                  specific.setPackage(ri.activityInfo.packageName);
                  try { startActivity(specific); toast("将导入 " + list.size() + " 条到阅读，请再勾选确认"); }
                  catch (Exception ex) { toast("打开失败：" + ex.getMessage()); }
                })
                .setPositiveButton("取消", null)
                .show();
            return;
          }
          startActivity(view);
          toast("将导入 " + list.size() + " 条到阅读，请再勾选确认");
        } catch (Exception e) {
          ReaderImportService.cancel(this);
          toast("打开阅读选择页失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
      });
    }, "export-import").start();
  }

  private void saveJson() {
    final List<SourceRecord> list = exportRecords();
    if (list.isEmpty()) { toast("没有可保存的数据"); return; }
    toast("正在生成 JSON，请稍候…");
    // 万级书源序列化在后台线程执行，避免主线程卡死/闪退
    new Thread(() -> {
      final String j = exportJson(list);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        if (j == null) { toast("没有可保存的数据"); return; }
        pendingSave = j;
        String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        createDoc.launch(ExportNaming.dedupeJson(list.size(), d));
      });
    }, "export-json").start();
  }

  private void addBackgroundTaskChip() {
    backgroundTaskChip = new TextView(this);
    backgroundTaskChip.setTextColor(Color.WHITE);
    backgroundTaskChip.setTextSize(13);
    backgroundTaskChip.setGravity(Gravity.CENTER);
    backgroundTaskChip.setMaxLines(1);
    backgroundTaskChip.setPadding(dp(14), 0, dp(14), 0);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff6750A4);
    background.setCornerRadius(dp(19));
    backgroundTaskChip.setBackground(background);
    backgroundTaskChip.setElevation(dp(6));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, dp(38), Gravity.START | Gravity.BOTTOM);
    params.setMargins(dp(16), 0, 0, dp(68));
    pageContainer.addView(backgroundTaskChip, params);
    backgroundTaskChip.setVisibility(View.GONE);
    backgroundTaskChip.setOnClickListener(view -> {
      selectDedupeTab();
      showDedupe();
    });
  }

  private void updateBackgroundTask(String text) {
    backgroundTaskText = text == null ? "" : text;
    if (backgroundTaskChip == null) return;
    backgroundTaskChip.setText(backgroundTaskText);
    backgroundTaskChip.setVisibility(currentTab == 1 && (state.isFetchRunning() || state.isCheckRunning()) ? View.VISIBLE : View.GONE);
  }

  private void addYckRefreshButton() {
    yckRefreshButton = new TextView(this);
    yckRefreshButton.setText("刷新 ↻");
    yckRefreshButton.setContentDescription("刷新 YCK 页面");
    yckRefreshButton.setTextColor(Color.WHITE);
    yckRefreshButton.setTextSize(14);
    yckRefreshButton.setGravity(Gravity.CENTER);
    yckRefreshButton.setPadding(dp(14), 0, dp(14), 0);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff6750A4);
    background.setCornerRadius(dp(21));
    yckRefreshButton.setBackground(background);
    yckRefreshButton.setElevation(dp(6));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.START | Gravity.BOTTOM);
    params.setMargins(dp(16), 0, 0, dp(16));
    pageContainer.addView(yckRefreshButton, params);
    yckRefreshButton.setVisibility(View.GONE);
    yckRefreshButton.setOnClickListener(view -> refreshYck());
  }

  private void refreshYck() {
    if (yck == null) return;
    yckAutoFellBack = false;
    // 白屏/错误页时绕过旧缓存，并重新加载当前 YCK 页面；没有可用地址时回到站点入口。
    String current = yck.getUrl();
    String target = current != null && YckUrlPolicy.allowed(current) ? current : yckSite.entryUrl();
    yck.stopLoading();
    yck.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
    yck.clearCache(false);
    yckLoaded = true;
    toast("正在刷新 " + yckSite.label());
    yck.loadUrl(target);
  }

  private void addYckSiteButton() {
    yckSiteButton = new TextView(this);
    yckSiteButton.setText(yckSite.label() + " ▾");
    yckSiteButton.setTextColor(Color.WHITE);
    yckSiteButton.setTextSize(14);
    yckSiteButton.setGravity(Gravity.CENTER);
    yckSiteButton.setPadding(dp(14), 0, dp(14), 0);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff007AFF);
    background.setCornerRadius(dp(21));
    yckSiteButton.setBackground(background);
    yckSiteButton.setElevation(dp(6));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.END | Gravity.BOTTOM);
    params.setMargins(0, 0, dp(16), dp(16));
    pageContainer.addView(yckSiteButton, params);
    yckSiteButton.setVisibility(View.GONE);
    yckSiteButton.setOnClickListener(view -> {
      PopupMenu menu = new PopupMenu(this, yckSiteButton);
      menu.getMenu().add(0, 0, 0, (yckSite == YckSite.MAIN ? "✓ " : "") + "主站 · www.yckceo.com");
      menu.getMenu().add(0, 1, 1, (yckSite == YckSite.BACKUP ? "✓ " : "") + "备用 · www.yck2026.fun");
      menu.getMenu().add(0, 2, 2, (yckSite == YckSite.RELEASE ? "✓ " : "") + "发布页 · yckceo.vip");
      menu.setOnMenuItemClickListener(item -> {
        selectYckSite(item.getItemId() == 0 ? YckSite.MAIN :
            (item.getItemId() == 1 ? YckSite.BACKUP : YckSite.RELEASE));
        return true;
      });
      menu.show();
    });
  }

  private void selectYckSite(YckSite site) {
    if (site == yckSite) return;
    yckSite = site;
    yckAutoFellBack = false;
    getSharedPreferences("yck", MODE_PRIVATE).edit().putString("site", site.preference()).apply();
    if (yckSiteButton != null) yckSiteButton.setText(site.label() + " ▾");
    yckLoaded = true;
    yck.loadUrl(site.entryUrl());
  }

  private void setupYck() {
    WebSettings s = yck.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    yck.addJavascriptInterface(new YckBridge(this::collectYckUrl, this::batchCollectYckUrls), "YckDedupe");
    yckClient = new YckWebClient(new YckWebClient.Listener() {
      public void onJsonLink(String u) { if (state.isDestroyed()) return; showJsonMenu(u); }
      public void onExternal(String u) { if (!state.isDestroyed()) toast("已拦截非 YCK 页面"); }
      public void onLoadError(String u) {
        if (state.isDestroyed()) return;
        if (!yckAutoFellBack) {
          yckAutoFellBack = true;
          YckSite other = yckSite == YckSite.BACKUP ? YckSite.MAIN : YckSite.BACKUP;
          if (yckSite == YckSite.RELEASE) other = YckSite.MAIN;
          String oldLabel = yckSite == YckSite.MAIN ? "主站" : (yckSite == YckSite.BACKUP ? "备用站" : "发布页");
          yckSite = other;
          getSharedPreferences("yck", MODE_PRIVATE).edit().putString("site", other.preference()).apply();
          if (yckSiteButton != null) yckSiteButton.setText(other.label() + " ▾");
          toast(oldLabel + "加载失败，已自动切换到" + other.label());
          yck.loadUrl(other.entryUrl());
        } else toast("站点加载失败，请检查网络后重试");
      }
      public void onPageFinished(String u) {
        if (state.isDestroyed()) return;
        yckAutoFellBack = false;
        yck.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        injectYckCollector();
      }
    });
    yck.setWebViewClient(yckClient);
    yck.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
      if (YckUrlPolicy.json(url)) showJsonMenu(url);
      else {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) {}
      }
    });
  }
  private String collectYckUrl(final String url) {
    if (!YckUrlPolicy.collectable(url)) return "invalid";
    // 已经在 UI 线程时直接执行：@JavascriptInterface 在部分实现里可能回调到 UI 线程，
    // 那时 runOnUiThread 是同步执行，再用 latch 等待会永久卡住
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      return appendUrlIfAbsent(url) ? "added" : "duplicate";
    }
    final String[] out = {"invalid"};
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    runOnUiThread(() -> {
      try {
        if (state.isDestroyed()) return;
        out[0] = appendUrlIfAbsent(url) ? "added" : "duplicate";
      } finally {
        // 必须在 finally 里 countDown：中途抛异常会让 JS 线程白等满超时
        latch.countDown();
      }
    });
    try {
      if (!latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) return "invalid";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "invalid";
    }
    return out[0];
  }

  /** 批量收集 YCK 页面上的书源 URL（JSON 数组字符串），返回 "added:N" 表示成功添加 N 条。 */
  @SuppressWarnings("unchecked")
  private String batchCollectYckUrls(String jsonUrls) {
    try {
      Object root = com.mina.yuedu.core.MiniJson.parse(jsonUrls);
      if (!(root instanceof java.util.List)) return "invalid";
      final java.util.List<String> urls = new java.util.ArrayList<>();
      for (Object o : (java.util.List<?>) root) {
        if (o instanceof String && YckUrlPolicy.collectable((String) o)) urls.add((String) o);
      }
      if (urls.isEmpty()) return "invalid";
      // 一次 setText 写完所有 URL：逐条 appendUrlIfAbsent 是 O(n²)（每次都重读重写整个输入框），
      // 一次收集几百条时会把 JS 线程和主线程一起拖住。
      final int[] added = {0};
      Runnable job = () -> {
        if (state.isDestroyed()) return;
        java.util.LinkedHashSet<String> existing = new java.util.LinkedHashSet<>();
        CharSequence cur = etUrls.getText();
        if (cur != null) for (String x : cur.toString().split("\\r?\\n")) if (!x.trim().isEmpty()) existing.add(x.trim());
        java.util.List<String> merged = new java.util.ArrayList<>(existing);
        for (String u : urls) if (existing.add(u)) { merged.add(u); added[0]++; }
        if (added[0] > 0) {
          etUrls.setText(TextKit.join("\n", merged));
          etUrls.setSelection(etUrls.getText() == null ? 0 : etUrls.getText().length());
        }
      };
      if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        job.run();
        return "added:" + added[0];
      }
      final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
      runOnUiThread(() -> { try { job.run(); } finally { latch.countDown(); } });
      try { latch.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception e) { Thread.currentThread().interrupt(); }
      return "added:" + added[0];
    } catch (Exception e) { return "invalid"; }
  }

  private void injectYckCollector() {
    if (state.isDestroyed() || yck == null) return;
    yck.post(() -> { if (!state.isDestroyed() && yck != null) yck.evaluateJavascript(YckCollectorScript.source(), null); });
    yck.postDelayed(() -> { if (!state.isDestroyed() && yck != null) yck.evaluateJavascript(YckCollectorScript.source(), null); }, 800);
  }

  private boolean appendUrlIfAbsent(String u) {
    String clean = u == null ? "" : u.trim();
    if (clean.isEmpty()) return false;
    CharSequence cur = etUrls.getText();
    List<String> existing = UrlInputParser.parse(cur == null ? null : cur.toString());
    if (existing.contains(clean)) return false;
    existing.add(clean);
    etUrls.setText(UrlInputParser.format(existing));
    // 光标移到末尾：批量收集几十条后默认停在开头，再手动输入会插到最前面
    etUrls.setSelection(etUrls.getText() == null ? 0 : etUrls.getText().length());
    return true;
  }

  private void showJsonMenu(String url) {
    new MaterialAlertDialogBuilder(this).setTitle("发现书源链接")
      .setItems(new String[]{"添加到去重工具", "在当前页面打开", "复制链接", "取消"}, (d, w) -> {
        if (w == 0) {
          boolean added = appendUrlIfAbsent(url);
          toast(added ? "已添加到去重工具" : "已在列表中");
          showDedupe();
          selectDedupeTab();
        } else if (w == 1) {
          if (yckClient != null) yckClient.allowNextJson();
          if (yck != null) yck.loadUrl(url);
        } else if (w == 2) {
          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("书源链接", url));
          toast("链接已复制");
        }
      }).show();
  }

  private void showDedupe() {
    currentTab = 0;
    dedupePage.setVisibility(View.VISIBLE);
    yck.setVisibility(View.INVISIBLE);
    if (yckSiteButton != null) yckSiteButton.setVisibility(View.GONE);
    if (yckRefreshButton != null) yckRefreshButton.setVisibility(View.GONE);
    if (backgroundTaskChip != null) backgroundTaskChip.setVisibility(View.GONE);
  }
  private void showYck() {
    currentTab = 1;
    // 仅切换可见页面；不修改 fetch/check 状态，也不调用 cancel。
    dedupePage.setVisibility(View.INVISIBLE);
    yck.setVisibility(View.VISIBLE);
    if (yckSiteButton != null) yckSiteButton.setVisibility(View.VISIBLE);
    if (yckRefreshButton != null) yckRefreshButton.setVisibility(View.VISIBLE);
    updateBackgroundTask(backgroundTaskText);
    yck.requestFocus();
    yck.requestLayout();
    yck.invalidate();
    if (!yckLoaded) { yckLoaded = true; yck.loadUrl(yckSite.entryUrl()); }
  }

  private void showAbout() {
    TextView tv = new TextView(this);
    tv.setPadding(dp(24), dp(12), dp(24), dp(12));
    tv.setText("轻量原生 Android 阅读书源整理工具：合并、去重、校验、导入。\n\n"
        + "GitHub：https://github.com/MIXUULS/yuedu-source-dedupe\n\n"
        + (BuildConfig.DEBUG ? "本机构建版（debug 签名）。" : "正式发布版。"));
    tv.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
    tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    tv.setTextSize(14);
    tv.setLinkTextColor(ContextCompat.getColor(this, R.color.md_theme_primary));
    new MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.app_name) + "  v" + BuildConfig.VERSION_NAME)
        .setView(tv)
        .setPositiveButton("确定", null)
        .show();
  }

  /** 导出下拉菜单：分类导出（可用/不可用/非HTTP）+ CSV + 合并同域校验结果。 */
  private void showExportMenu() {
    PopupMenu pm = new PopupMenu(this, btnExport);
    pm.getMenu().add(0, 1, 0, "导出可用书源");
    pm.getMenu().add(0, 2, 1, "导出不可用书源");
    // 新增：区分「站点已死」与「站点在线但规则失效」。
    // 用户要的是「去除无论如何都不能用的网站」——403/404/超时都不算，
    // 只有 DNS 失败与连接被拒才是换什么手段都救不回来的。
    UsabilityBuckets buckets = UsabilityBuckets.split(checkResults);
    pm.getMenu().add(0, 9, 2, "导出站点已死的源（" + buckets.siteDead.size() + " 条）");
    pm.getMenu().add(0, 10, 3, "移除站点已死的源（" + buckets.siteDead.size() + " 条）");
    pm.getMenu().add(0, 3, 4, "导出非HTTP书源");
    pm.getMenu().add(0, 4, 5, "导出 CSV（可在电脑打开）");
    pm.getMenu().add(0, 5, 6, "合并同域校验结果");
    pm.getMenu().add(0, 6, 7, "导出优质源（评分≥70）");
    pm.getMenu().add(0, 7, 8, "同域优化建议");
    pm.getMenu().add(0, 8, 9, "智能优选导出（评分≥70，每域最多2个）");
    pm.getMenu().add(0, 11, 10, "检测重定向同站（需联网）");
    pm.setOnMenuItemClickListener(item -> {
      int id = item.getItemId();
      if (id == 1) exportByCategory("可用");
      else if (id == 2) exportByCategory("不可用");
      else if (id == 3) exportByCategory("非HTTP");
      else if (id == 4) exportCsv();
      else if (id == 5) showMergeDomain();
      else if (id == 6) exportQualitySources();
      else if (id == 7) showDomainOptimization();
      else if (id == 8) exportSmartQualitySources();
      else if (id == 9) exportDeadSiteSources();
      else if (id == 10) removeDeadSiteSources();
      else if (id == 11) checkRedirectDuplicates();
      return true;
    });
    pm.show();
  }

  /** 导出「站点确认不可用」的书源（DNS 失败 / 连接被拒）。 */
  private void exportDeadSiteSources() {
    final UsabilityBuckets b = UsabilityBuckets.split(checkResults);
    if (b.siteDead.isEmpty()) {
      toast("没有站点已死的书源（4xx/5xx 与超时不算死站）");
      return;
    }
    final List<SourceRecord> list = new ArrayList<>();
    for (CheckSourceResult r : b.siteDead) {
      if (r.source != null) list.add(r.source);
    }
    final String stamp = todayStamp();
    // 与 exportByCategory 一致：生成放后台线程，避免万级序列化卡 UI
    new Thread(() -> {
      String json = ExportPayloadBuilder.toJson(list, checkResults, stamp);
      runOnUiThread(() -> {
        if (state.isDestroyed() || isFinishing()) return;
        if (json == null) { toast("生成失败"); return; }
        pendingSave = json;
        createDoc.launch(ExportNaming.categoryJson("站点已死", list.size(), stamp));
      });
    }, "export-deadsite").start();
  }

  /**
   * 检测「重定向到同一站点」的书源（Issue #1 三.2）。
   *
   * 这是个可选的重活：要发真实网络请求，万级书源即使 32 并发也要几分钟。
   * 所以放在菜单里由用户主动触发，结果显示为「建议」而不自动改结果。
   */
  private void checkRedirectDuplicates() {
    final List<SourceRecord> list;
    synchronized (buckets) {
      list = result != null ? new ArrayList<>(result.getRetained()) : buckets.all();
    }
    if (list.isEmpty()) { toast("没有可检测的书源"); return; }
    if (state.isFetchRunning() || state.isCheckRunning()) {
      toast("有任务在运行，请先停止");
      return;
    }

    new MaterialAlertDialogBuilder(this)
        .setTitle("检测重定向同站")
        .setMessage("将要向 " + list.size() + " 条书源的站点各发一次请求，"
            + "看哪些不同域名最终重定向到同一个站点。\n\n"
            + "· 需要联网，万级书源大约几分钟\n"
            + "· 只读请求头不看正文，不会下载内容\n"
            + "· 结果只作建议，不会自动改你的书源")
        .setNegativeButton("取消", null)
        .setPositiveButton("开始检测", (d, w) -> runRedirectProbe(list))
        .show();
  }

  /** 执行重定向探测并在完成后展示分组建议。 */
  private void runRedirectProbe(List<SourceRecord> list) {
    final RedirectProbe probe = new RedirectProbe();
    redirectProbe = probe;
    setTaskRunning(true);
    updateBackgroundTask("检测重定向：准备中…");

    new Thread(() -> {
      Map<String, String> resolved = probe.probeAll(list, new RedirectProbe.Listener() {
        private long lastUi = 0;
        @Override public void onProgress(int done, int total) {
          long now = System.currentTimeMillis();
          if (now - lastUi < 400) return;   // 限流，避免刷屏
          lastUi = now;
          runOnUiThread(() -> {
            if (!state.isDestroyed()) {
              updateBackgroundTask("检测重定向：" + done + "/" + total);
            }
          });
        }
        @Override public void onFinished(Map<String, String> r, boolean cancelled) {
          // 结果在下面统一处理
        }
      });
      if (state.isDestroyed()) return;
      List<RedirectCluster.Group> groups = RedirectProbe.probeAndCluster(list, resolved);
      runOnUiThread(() -> {
        if (state.isDestroyed() || isFinishing()) return;
        redirectProbe = null;
        setTaskRunning(false);
        updateBackgroundTask("");
        showRedirectGroups(groups, list.size(), probe.isCancelled());
      });
    }, "redirect-check").start();
  }

  /** 展示重定向分组结果。 */
  private void showRedirectGroups(List<RedirectCluster.Group> groups, int scanned, boolean cancelled) {
    if (groups.isEmpty()) {
      toast(cancelled ? "已取消，未发现重定向同站" : "未发现重定向到同一站点的书源");
      return;
    }
    int totalDup = 0;
    for (RedirectCluster.Group g : groups) totalDup += g.size() - 1;

    LinearLayout ll = new LinearLayout(this);
    ll.setOrientation(LinearLayout.VERTICAL);
    ll.setPadding(dp(20), dp(12), dp(20), dp(4));
    TextView head = new TextView(this);
    head.setText("扫描 " + scanned + " 条，发现 " + groups.size() + " 组重定向同站，"
        + "可合并 " + totalDup + " 条冗余。");
    head.setTextSize(14);
    head.setPadding(0, 0, 0, dp(10));
    ll.addView(head);

    ScrollView sc = new ScrollView(this);
    LinearLayout inner = new LinearLayout(this);
    inner.setOrientation(LinearLayout.VERTICAL);
    int shown = 0;
    for (RedirectCluster.Group g : groups) {
      if (shown++ >= 20) break;
      TextView tv = new TextView(this);
      tv.setText(RedirectCluster.describe(g));
      tv.setTextSize(13);
      tv.setPadding(0, dp(6), 0, dp(6));
      inner.addView(tv);
    }
    if (groups.size() > 20) {
      TextView more = new TextView(this);
      more.setText("…另有 " + (groups.size() - 20) + " 组未显示");
      more.setTextSize(12);
      inner.addView(more);
    }
    sc.addView(inner);
    ll.addView(sc);

    new MaterialAlertDialogBuilder(this)
        .setTitle("重定向同站检测")
        // 注意：这里必须传 ll 而不是 sc——sc 已经 addView 给 ll 了，
        // 再交给弹窗会抛 "The specified child already has a parent"。
        .setView(ll)
        .setNegativeButton("关闭", null)
        .setNeutralButton("复制结果", (d, w) -> {
          StringBuilder sb = new StringBuilder();
          sb.append("重定向同站检测结果\n\n");
          for (RedirectCluster.Group g : groups) sb.append(RedirectCluster.describe(g)).append("\n");
          ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          cm.setPrimaryClip(ClipData.newPlainText("重定向同站检测", sb.toString()));
          toast("已复制");
        })
        .setPositiveButton("合并冗余 " + totalDup + " 条", (d, w) -> mergeRedirectGroups(groups))
        .show();
  }

  /**
   * 按重定向分组合并冗余：每组只保留一条，其余移除。
   *
   * 保留策略：优先留规则更完整的（ruleToc/ruleContent 齐全的），
   * 其次留 URL 更短的（通常是主域名而非轮换域名）。
   */
  private void mergeRedirectGroups(List<RedirectCluster.Group> groups) {
    if (groups.isEmpty()) { toast("没有可合并的组"); return; }

    final Set<String> removeUrls = new java.util.LinkedHashSet<>();
    for (RedirectCluster.Group g : groups) {
      SourceRecord keep = RedirectCluster.pickBestInGroup(g.members);
      for (SourceRecord s : g.members) {
        if (s == keep && s.getUrl() != null) continue;
        if (s.getUrl() != null && !s.getUrl().equals(keep == null ? null : keep.getUrl())) {
          removeUrls.add(s.getUrl());
        }
      }
    }
    if (removeUrls.isEmpty()) { toast("没有可移除的冗余"); return; }

    new MaterialAlertDialogBuilder(this)
        .setTitle("合并重定向同站")
        .setMessage("将从 " + groups.size() + " 组中移除 " + removeUrls.size()
            + " 条冗余书源，每组保留 1 条（规则更完整、域名更短的那条）。\n\n"
            + "此操作只影响当前列表，不会改动你磁盘上的原文件。")
        .setNegativeButton("取消", null)
        .setPositiveButton("合并", (dd, ww) -> {
          ResultPruner.Pruned pruned = ResultPruner.prune(result, checkResults, removeUrls);
          synchronized (buckets) { buckets.removeByUrls(removeUrls); }
          checkResults = pruned.checkResults;
          if (pruned.result != null) result = pruned.result;
          refreshCheckSearchFilter();
          renderCheckStats();
          refreshExportPreview();
          if (result != null) recompute(partial);
          toast("已移除 " + removeUrls.size() + " 条重定向冗余");
        })
        .show();
  }


  /** 移除「站点确认不可用」的书源，并说明为什么这些能删、那些不能。 */
  private void removeDeadSiteSources() {
    final UsabilityBuckets b = UsabilityBuckets.split(checkResults);
    if (b.siteDead.isEmpty()) {
      toast("没有站点已死的书源");
      return;
    }
    final int dead = b.siteDead.size();
    final int alive = b.siteAlive.size();
    StringBuilder msg = new StringBuilder();
    msg.append("将移除 ").append(dead).append(" 条「站点已死」的书源。\n\n");
    msg.append("判定依据：DNS 解析失败或连接被拒——换代理、换 UA 都救不回来。\n");
    if (alive > 0) {
      msg.append("\n另有 ").append(alive)
          .append(" 条「站点在线但规则失效」（403 反爬 / 404 路径变化 / 超时），");
      msg.append("这些不会被移除——站点还在维护，改规则或换个时间就能用。");
    }
    new MaterialAlertDialogBuilder(this)
        .setTitle("移除站点已死的源")
        .setMessage(msg.toString())
        .setNegativeButton("取消", null)
        .setPositiveButton("移除 " + dead + " 条", (d, w) -> {
          Set<String> deadUrls = b.deadUrls();
          // ResultPruner 会同时清理 retained / duplicateGroups / checkResults 三份数据，
          // 所以不需要再单独过滤 checkResults。
          ResultPruner.Pruned pruned = ResultPruner.prune(result, checkResults, deadUrls);
          synchronized (buckets) { buckets.removeByUrls(deadUrls); }
          checkResults = pruned.checkResults;
          if (pruned.result != null) result = pruned.result;
          refreshCheckSearchFilter();
          renderCheckStats();
          refreshExportPreview();
          // 剩余数取校验结果实际条数：没跑过去重时 result 为 null，不能拿它算
          int left = checkResults.size();
          toast("已移除 " + dead + " 条站点已死的源，剩余 " + left + " 条校验记录");
        })
        .show();
  }


  /** 书源健康看板：按评分排序，显示建议/统计/可导出优质源。 */
  private void showHealthDashboard() {
    java.util.List<SourceHealthTracker.HealthRecord> all = healthTracker.getAll();
    if (all.isEmpty()) { toast("暂无书源健康数据，请先运行校验"); return; }
    int total = all.size();
    int retain = 0, watch = 0, remove = 0;
    long sumMs = 0;
    for (SourceHealthTracker.HealthRecord r : all) {
      String sug = r.suggestion();
      if ("建议保留".equals(sug)) retain++;
      else if ("建议删除".equals(sug)) remove++;
      else watch++;
      sumMs += r.avgResponseMs();
    }
    StringBuilder sb = new StringBuilder();
    sb.append("书源总数：").append(total).append("\n");
    sb.append("✅ 建议保留：").append(retain).append("\n");
    sb.append("👀 建议观察：").append(watch).append("\n");
    sb.append("❌ 建议删除：").append(remove).append("\n");
    sb.append("平均响应：").append(total > 0 ? sumMs / total : 0).append("ms\n");
    sb.append("\n按评分降序（前 30 条）：\n");
    int shown = Math.min(30, all.size());
    for (int i = 0; i < shown; i++) {
      SourceHealthTracker.HealthRecord r = all.get(i);
      sb.append(String.format(Locale.CHINA, "%.0f分 ", r.score))
          .append(r.name.isEmpty() ? r.url : r.name).append(" ")
          .append(r.passRate() > 0 ? String.format(Locale.CHINA, "%.0f%% ", r.passRate() * 100) : "")
          .append(r.avgResponseMs() + "ms ")
          .append(r.suggestion()).append("\n");
    }
    if (all.size() > shown) sb.append("… 其余 ").append(all.size() - shown).append(" 条略\n");

    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setText(sb.toString());
    tv.setTextSize(13);
    tv.setTextIsSelectable(true);
    int p = dp(16);
    tv.setPadding(p, p, p, p);
    sv.addView(tv);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this)
        .setTitle("书源健康看板")
        .setView(sv)
        .setNegativeButton("导出优质源", (d, w) -> exportQualitySources())
        .setNeutralButton("健康报告", (d, w) -> showHealthReport())
        .setPositiveButton("关闭", null)
        .show();
  }

  /** 导出评分 ≥ 70 的优质书源。 */
  private void exportQualitySources() {
    if (result == null) { toast("当前无去重结果，无法导出"); return; }
    List<SourceRecord> records = new ArrayList<>();
    java.util.Set<String> healthUrls = new java.util.HashSet<>();
    for (SourceHealthTracker.HealthRecord hr : healthTracker.getAll()) {
      if (hr.score >= 70) healthUrls.add(hr.url);
    }
    for (SourceRecord s : result.getRetained()) {
      if (s.getUrl() != null && healthUrls.contains(s.getUrl())) records.add(s);
    }
    if (records.isEmpty()) { toast("当前结果中无优质书源（评分≥70）"); return; }
    saveShareFile(records);
  }

  /** 按评分、速度优先级筛选，并限制每个域名最多保留两个优质源。 */
  private void exportSmartQualitySources() {
    List<SourceRecord> records = QualityExportPolicy.select(exportRecords(), healthTracker, 70, 2);
    if (records.isEmpty()) { toast("暂无评分≥70 的健康书源，请先完成校验"); return; }
    toast("已智能优选 " + records.size() + " 条书源");
    saveShareFile(records);
  }

  /** 生成书源健康报告。 */
  private void showHealthReport() {
    java.util.List<SourceHealthTracker.HealthRecord> all = healthTracker.getAll();
    if (all.isEmpty()) { toast("暂无健康数据"); return; }
    int total = all.size();
    int retain = 0, watch = 0, remove = 0;
    for (SourceHealthTracker.HealthRecord r : all) {
      String sug = r.suggestion();
      if ("建议保留".equals(sug)) retain++;
      else if ("建议删除".equals(sug)) remove++;
      else watch++;
    }
    // 按类型统计
    Map<String, Integer> domainCount = new LinkedHashMap<>();
    for (SourceHealthTracker.HealthRecord r : all) {
      String domain;
      try { domain = new java.net.URL(r.url).getHost(); } catch (Exception e) { domain = r.url; }
      domainCount.put(domain, domainCount.getOrDefault(domain, 0) + 1);
    }
    StringBuilder sb = new StringBuilder();
    sb.append("=== 书源健康报告 ===\n");
    sb.append("生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date())).append("\n\n");
    sb.append("📊 总体统计\n");
    sb.append("书源总数：").append(total).append("\n");
    sb.append("✅ 建议保留：").append(retain).append("（").append(String.format(Locale.CHINA, "%.1f%%", retain * 100.0 / total)).append("）\n");
    sb.append("👀 建议观察：").append(watch).append("（").append(String.format(Locale.CHINA, "%.1f%%", watch * 100.0 / total)).append("）\n");
    sb.append("❌ 建议删除：").append(remove).append("（").append(String.format(Locale.CHINA, "%.1f%%", remove * 100.0 / total)).append("）\n\n");
    sb.append("🏢 域名分布（Top 10）\n");
    List<Map.Entry<String, Integer>> sorted = new ArrayList<>(domainCount.entrySet());
    sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    for (int i = 0; i < Math.min(10, sorted.size()); i++) {
      sb.append("  ").append(sorted.get(i).getKey()).append(": ").append(sorted.get(i).getValue()).append(" 个\n");
    }
    if (sorted.size() > 10) sb.append("  … 其余 ").append(sorted.size() - 10).append(" 个域名\n");
    sb.append("\n❌ 建议删除的源\n");
    int removed = 0;
    for (SourceHealthTracker.HealthRecord r : healthTracker.getSuggestedRemovals()) {
      if (removed >= 20) { sb.append("  … 其余 ").append(healthTracker.getSuggestedRemovals().size() - 20).append(" 条\n"); break; }
      removed++;
      sb.append("  ").append(r.name.isEmpty() ? r.url : r.name).append(" ").append(String.format(Locale.CHINA, "%.0f分", r.score)).append("\n");
    }
    if (removed == 0) sb.append("  （无）\n");
    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setText(sb.toString());
    tv.setTextSize(13);
    tv.setTextIsSelectable(true);
    int p = dp(16);
    tv.setPadding(p, p, p, p);
    sv.addView(tv);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this)
        .setTitle("书源健康报告")
        .setView(sv)
        .setNegativeButton("复制报告", (d, w) -> {
          ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          cm.setPrimaryClip(ClipData.newPlainText("书源健康报告", sb.toString()));
          toast("报告已复制");
        })
        .setPositiveButton("关闭", null)
        .show();
  }

  /** 同域优化建议：按域名分组，同域内按评分排序，建议保留最快 2 个。 */
  private void showDomainOptimization() {
    java.util.List<SourceHealthTracker.HealthRecord> all = healthTracker.getAll();
    if (all.isEmpty()) { toast("暂无健康数据，请先运行校验"); return; }
    Map<String, java.util.List<SourceHealthTracker.HealthRecord>> byDomain = new LinkedHashMap<>();
    for (SourceHealthTracker.HealthRecord r : all) {
      String domain;
      try { domain = new java.net.URL(r.url).getHost(); } catch (Exception e) { domain = r.url; }
      if (domain.startsWith("www.")) domain = domain.substring(4);
      byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(r);
    }
    StringBuilder sb = new StringBuilder();
    sb.append("同域优化建议（每个域名建议保留最快 2 个）：\n\n");
    int totalSuggest = 0;
    for (Map.Entry<String, java.util.List<SourceHealthTracker.HealthRecord>> e : byDomain.entrySet()) {
      if (e.getValue().size() <= 2) continue;
      java.util.List<SourceHealthTracker.HealthRecord> sorted = new ArrayList<>(e.getValue());
      Collections.sort(sorted, (a, b) -> Long.compare(a.avgResponseMs(), b.avgResponseMs()));
      sb.append("🏢 ").append(e.getKey()).append("（共 ").append(e.getValue().size()).append(" 个）\n");
      for (int i = 0; i < sorted.size(); i++) {
        SourceHealthTracker.HealthRecord r = sorted.get(i);
        sb.append(i < 2 ? "  ✅ " : "  ❌ ");
        sb.append(r.name.isEmpty() ? r.url : r.name);
        sb.append(" ").append(String.format(Locale.CHINA, "%.0f分", r.score));
        sb.append(" ").append(r.avgResponseMs()).append("ms");
        if (i >= 2) sb.append(" ← 建议精简");
        sb.append("\n");
      }
      totalSuggest += e.getValue().size() - 2;
      sb.append("\n");
    }
    if (totalSuggest == 0) { sb.append("所有域名均不超过 2 个源，无需优化。\n"); }
    sb.append("共建议精简 ").append(totalSuggest).append(" 个书源。").append("\n");
    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setText(sb.toString());
    tv.setTextSize(13);
    tv.setTextIsSelectable(true);
    int p = dp(16);
    tv.setPadding(p, p, p, p);
    sv.addView(tv);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this)
        .setTitle("同域优化建议")
        .setView(sv)
        .setPositiveButton("关闭", null)
        .show();
  }

  /** 按分类导出校验结果（可用/不可用/非HTTP）。 */
  private void exportByCategory(String cat) {
    // 先按 URL 建索引再遍历：原来是双层循环（每条书源都扫一遍校验结果），
    // 万级书源 + 万级结果会变成上亿次比较，主线程直接 ANR。
    Map<String, CheckSourceResult> byUrl = new HashMap<>();
    for (CheckSourceResult r : getCheckResultsForDisplay()) {
      if (r.source.getUrl() != null) byUrl.put(r.source.getUrl(), r);
    }
    List<SourceRecord> list = new ArrayList<>();
    for (SourceRecord s : exportRecords()) {
      if (s.getUrl() == null) {
        // 非 HTTP 分类的口径是"地址不是 http(s)"，无地址的书源同样属于该口径
        if ("非HTTP".equals(cat)) list.add(s);
        continue;
      }
      CheckSourceResult matched = byUrl.get(s.getUrl());
      if (matched == null) continue;
      boolean isHttp = s.getUrl().startsWith("http://") || s.getUrl().startsWith("https://");
      if ("可用".equals(cat) && matched.isUsable()) list.add(s);
      else if ("不可用".equals(cat) && !matched.isUsable() && isHttp) list.add(s);
      else if ("非HTTP".equals(cat) && !isHttp) list.add(s);
    }
    if (list.isEmpty()) { toast("没有符合条件的书源"); return; }
    toast("正在生成 JSON…");
    new Thread(() -> {
      final String json = exportJson(list);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        if (json == null) { toast("生成失败"); return; }
        pendingSave = json;
        String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        createDoc.launch(ExportNaming.categoryJson(cat, list.size(), d));
      });
    }, "export-cat").start();
  }

  /** 导出校验结果为 CSV 文件（可在电脑上打开筛选排序）。 */
  private void exportCsv() {
    // 拷贝后再排序：getCheckResultsForDisplay() 返回的是共享列表，原地排序会永久改变明细/导出的展示顺序
    List<CheckSourceResult> list = new ArrayList<>(getCheckResultsForDisplay());
    if (list.isEmpty()) { toast("没有可导出的数据"); return; }
    list.sort((a, b) -> Long.compare(b.respondTimeMs, a.respondTimeMs));
    String csv = CsvBuilder.build(list);
    if (csv == null) { toast("没有可导出的数据"); return; }
    pendingSave = csv;
    String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    csvDoc.launch(ExportNaming.checkCsv(list.size(), d));
  }

  /** 展示同域合并校验结果（A1搜索失败+A2发现失败→合并后搜索+发现都成功）。 */
  private void showMergeDomain() {
    List<CheckSourceResult> list = getCheckResultsForDisplay();
    if (list.isEmpty()) { toast("暂无校验结果"); return; }
    Map<String, CheckSourceEngine.MergeResult> merged = CheckSourceEngine.mergeByDomain(list);
    LinearLayout ll = new LinearLayout(this);
    ll.setOrientation(LinearLayout.VERTICAL);
    ll.setPadding(dp(20), dp(8), dp(20), dp(8));
    int shown = 0;
    for (CheckSourceEngine.MergeResult m : merged.values()) {
      if (shown >= 30) break;
      shown++;
      String steps = m.mergedSucceededSteps.isEmpty() ? "无成功步骤" : TextKit.join("、", m.mergedSucceededSteps);
      TextView tv = new TextView(this);
      tv.setTextSize(13);
      tv.setTextColor(ContextCompat.getColor(this, R.color.md_theme_on_surface));
      tv.setText(m.domain + "（" + m.sources.size() + " 个源）\n    合并后通过步骤：" + steps);
      tv.setPadding(0, dp(6), 0, dp(6));
      ll.addView(tv);
    }
    ScrollView sv = new ScrollView(this);
    sv.addView(ll);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this)
        .setTitle("同域合并结果（共 " + merged.size() + " 个域名" + (merged.size() > shown ? "，显示前 " + shown + " 个" : "") + "）")
        .setView(sv)
        .setPositiveButton("关闭", null)
        .show();
  }

  private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

  private static String fmtBytes(long n) {
    if (n >= 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", n / 1048576.0);
    if (n >= 1024) return String.format(Locale.CHINA, "%.0f KB", n / 1024.0);
    return n + " B";
  }

  @Override public void onBackPressed() {
    if (currentTab == 1 && yck != null && yck.canGoBack()) { yck.goBack(); return; }
    if (currentTab == 1) {
      selectDedupeTab();
      showDedupe();
      return;
    }
    new MaterialAlertDialogBuilder(this).setTitle("退出确认").setMessage("确定要退出阅读书源去重吗？")
      .setPositiveButton("退出", (d, w) -> {
        // 交给父类走标准的 finish 流程（含 Fragment 回退栈、返回结果分发），
        // 直接 finish() 会跳过这些逻辑，Lint 也会报 MissingSuperCall。
        super.onBackPressed();
      })
      .setNegativeButton("取消", null).show();
  }

  /** 切到「去重」Tab。getTabAt 在极端时序下可能为 null（Tab 尚未布局完成），需判空。 */
  private void selectDedupeTab() {
    if (tabs == null) return;
    TabLayout.Tab tab = tabs.getTabAt(0);
    if (tab != null) tabs.selectTab(tab);
  }

  // ================= ==== 新增：书源分享 / 剪贴板导入 ================= ====

  /** 分享书源：一键底部菜单（复制文本 / 保存文件 / 二维码 / 自用分享链接）。 */
  private void showShareMenu() {
    final List<SourceRecord> records = exportRecords();
    BottomSheetDialog d = new BottomSheetDialog(this);
    d.setContentView(buildShareSheet(records, d));
    d.show();
  }

  private View buildShareSheet(final List<SourceRecord> records, final BottomSheetDialog dialog) {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(16);
    root.setPadding(pad, dp(20), pad, dp(12));
    TextView title = new TextView(this);
    title.setText(records.isEmpty() ? "分享与导入" : "分享书源（共 " + records.size() + " 条）");
    title.setTextSize(16);
    title.setPadding(0, 0, 0, dp(8));
    root.addView(title);
    if (records.isEmpty()) {
      TextView hint = new TextView(this);
      hint.setText("暂无待分享的书源。请先选择 JSON 文件或输入地址解析，也可直接从剪贴板导入已有书源文本。");
      hint.setTextSize(13);
      hint.setPadding(0, 0, 0, dp(8));
      root.addView(hint);
    }
    root.addView(sheetButton("📄 复制分享文本（包含校验备注）", v -> {
      if (records.isEmpty()) { toast("暂无书源可分享，请先解析或导入"); return; }
      shareToClipboard(records); dialog.dismiss();
    }));
    root.addView(sheetButton("💾 保存为 JSON 文件", v -> {
      if (records.isEmpty()) { toast("暂无书源可分享，请先解析或导入"); return; }
      saveShareFile(records); dialog.dismiss();
    }));
    root.addView(sheetButton("🔗 生成自用分享链接", v -> {
      if (records.isEmpty()) { toast("暂无书源可分享，请先解析或导入"); return; }
      shareSelfLink(records); dialog.dismiss();
    }));
    root.addView(sheetButton("▦ 生成二维码", v -> {
      if (records.isEmpty()) { toast("暂无书源可分享，请先解析或导入"); return; }
      showQrFor(records); dialog.dismiss();
    }));
    root.addView(sheetButton("📥 从剪贴板导入书源", v -> { pasteImport(); dialog.dismiss(); }));
    // 重定向检测放在这里而不是导出菜单：它只需要"去重结果"，不需要"校验结果"，
    // 放在导出菜单里会导致用户没跑校验就完全找不到这个功能。
    root.addView(sheetButton("🔀 检测重定向同站（需联网）", v -> {
      dialog.dismiss();
      checkRedirectDuplicates();
    }));
    return root;
  }

  private TextView sheetButton(String text, View.OnClickListener l) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setPadding(0, dp(12), 0, dp(12));
    tv.setTextSize(15);
    tv.setOnClickListener(l);
    return tv;
  }

  /** 复制分享文本到剪贴板。文本为完整书源 JSON 数组，含校验备注。 */
  private void shareToClipboard(final List<SourceRecord> records) {
    new Thread(() -> {
      final String json = exportJson(records);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        if (json == null) { toast("生成分享文本失败"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("阅读书源", json));
        toast("分享文本已复制到剪贴板");
      });
    }, "share-copy").start();
  }

  /** 保存分享 JSON 文件。 */
  private void saveShareFile(final List<SourceRecord> records) {
    toast("正在生成 JSON…");
    new Thread(() -> {
      final String json = exportJson(records);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        if (json == null) { toast("生成失败"); return; }
        pendingSave = json;
        String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        createDoc.launch(ExportNaming.shareJson(records.size(), d));
      });
    }, "share-save").start();
  }

  /** 生成自用分享链接（本机临时 HTTP 服务，对方可用浏览器/阅读拉取）。 */
  private void shareSelfLink(final List<SourceRecord> records) {
    new Thread(() -> {
      final String json = exportJson(records);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        if (json == null) { toast("生成失败"); return; }
        try {
          String url = ReaderImportService.prepare(this, json, 30L * 60L * 1000L);
          ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          cm.setPrimaryClip(ClipData.newPlainText("自用分享链接", url));
          showLinkDialog(url);
        } catch (Exception e) {
          ReaderImportService.cancel(this);
          toast("生成分享链接失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
      });
    }, "share-link").start();
  }

  /** 展示自用分享链接：复制 / 生成二维码。 */
  private void showLinkDialog(String url) {
    final String link = url;
    new MaterialAlertDialogBuilder(this)
        .setTitle("自用分享链接（本机短时有效）")
        .setMessage(link + "\n\n对方可在浏览器或阅读的「从URL导入」中打开。链接仅在本机生成后的 30 分钟内有效。")
        .setItems(new String[]{"复制链接", "生成二维码", "关闭"}, (d, w) -> {
          if (w == 0) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("自用分享链接", link));
            toast("链接已复制");
          } else if (w == 1) {
            showQrImage(link, "书源分享链接");
          }
        })
        .show();
  }

  /** 生成二维码弹窗：内容为分享内容（若过长则提示改用文件/文本）。 */
  private void showQrFor(final List<SourceRecord> records) {
    // 书源数组往往超出二维码容量（约 3KB），二维码放自用分享链接（短 URL）最实用。
    new Thread(() -> {
      final String json = exportJson(records);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        if (json == null) { toast("生成失败"); return; }
        if (json.length() > 2000) {
          toast("书源内容较大，二维码无法完整承载，请改用「复制文本」或「保存文件」");
          return;
        }
        showQrImage(json, "书源分享二维码");
      });
    }, "share-qr").start();
  }

  /** 用 ZXing 生成二维码并弹窗展示。 */
  private void showQrImage(String content, String title) {
    try {
      java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new HashMap<>();
      hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
      hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
      com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
          .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 720, 720, hints);
      int w = matrix.getWidth(), h = matrix.getHeight();
      int[] pixels = new int[w * h];
      for (int y = 0; y < h; y++) {
        int off = y * w;
        for (int x = 0; x < w; x++) pixels[off + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
      }
      android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
      bmp.setPixels(pixels, 0, w, 0, 0, w, h);
      android.widget.ImageView iv = new android.widget.ImageView(this);
      iv.setImageBitmap(bmp);
      int pad = dp(16);
      iv.setPadding(pad * 6, pad * 3, pad * 6, 0);
      new MaterialAlertDialogBuilder(this)
          .setTitle(title)
          .setView(iv)
          .setNegativeButton("复制内容", (d, ww) -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(title, content));
            toast("内容已复制");
          })
          .setPositiveButton("关闭", null)
          .show();
    } catch (Exception e) {
      toast("生成二维码失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }

  /** 从剪贴板导入书源文本：读取 → 解析 → 合并到去重结果。 */
  private void pasteImport() {
    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
      toast("剪贴板为空"); return;
    }
    CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
    if (clip == null || clip.toString().trim().isEmpty()) { toast("剪贴板中没有文本内容"); return; }
    final String text = clip.toString().trim();
    // 剪贴板里可能有两种东西：书源 JSON，或一串待解析的 JSON 地址。
    // 原来只认 JSON，粘贴地址列表会被判成"不是书源 JSON"直接拒绝。
    final boolean looksJson = text.startsWith("[") || text.startsWith("{");
    final List<String> urls = looksJson ? new ArrayList<String>() : UrlInputParser.extractUrls(text);
    final String hint = text.length() > 120 ? text.substring(0, 120) + "…" : text;

    if (!looksJson && urls.isEmpty()) { toast("剪贴板里没有可识别的书源 JSON 或地址"); return; }

    String message = looksJson
        ? "将剪贴板内容解析为书源并入去重流程：\n\n" + hint + "\n\n确认吗？"
        : "将把剪贴板中的 " + urls.size() + " 个地址填入地址栏：\n\n" + hint + "\n\n确认吗？";

    new MaterialAlertDialogBuilder(this)
        .setTitle("从剪贴板导入书源")
        .setMessage(message)
        .setPositiveButton("导入", (d, w) -> {
          if (!looksJson) {
            // 地址列表：合并进输入框（保序去重），再走正常的网络解析流程
            List<String> merged = UrlInputParser.merge(getUrls(), urls);
            etUrls.setText(UrlInputParser.format(merged));
            etUrls.setSelection(etUrls.getText() == null ? 0 : etUrls.getText().length());
            toast("已添加 " + urls.size() + " 个地址，点「解析网络源」开始");
            return;
          }
          new Thread(() -> {
            final SourceParser.ParseResult p = SourceParser.parseArray(text, 0);
            runOnUiThread(() -> {
              if (state.isDestroyed()) return;
              if (p.getRecords().isEmpty()) { toast("未解析到有效书源：" + (p.getInvalid().isEmpty() ? "" : p.getInvalid().get(0).getDetail())); return; }
              commitLocal("剪贴板", p);
              toast("已从剪贴板导入 " + p.getRecords().size() + " 条书源");
            });
          }, "paste-import").start();
        })
        .setNegativeButton("取消", null)
        .show();
  }

  // ============= 新增：配置 一键备份 / 还原 =============

  private void showConfigMenu() {
    new MaterialAlertDialogBuilder(this)
        .setTitle("设置 备份 / 还原")
        .setMessage("可导出去重模式、并发数、校验五步、自定义状态码、快速模式、导入分类等核心行为设置，不含 URL 历史等个人数据。")
        .setItems(new String[]{"备份设置（保存为 JSON 文件）", "从备份文件还原", "取消"}, (d, w) -> {
          if (w == 0) backupSettings();
          else if (w == 1) configOpen.launch(new String[]{"application/json", "text/*", "*/*"});
        })
        .show();
  }

  /** 备份核心行为设置到 JSON 文件（复用 createDoc 保存）。 */
  private void backupSettings() {
    SettingsBackup.Snapshot s = new SettingsBackup.Snapshot();
    SharedPreferences app = appPrefs();
    s.mode = app.getString("mode", DedupeMode.STANDARD.name());
    s.clean = app.getBoolean("clean", false);
    s.onlyUsable = app.getBoolean("onlyUsable", false);
    s.concurrency = app.getInt("concurrency", 4);
    SharedPreferences check = checkPrefs();
    s.timeout = check.getInt("timeout", CheckSourceSettings.DEFAULT_TIMEOUT);
    s.checkConcurrency = check.getInt("concurrency", CheckSourceSettings.DEFAULT_CONCURRENCY);
    s.keyword = check.getString("keyword", CheckSourceSettings.DEFAULT_KEYWORD);
    s.okStatus = check.getString("okStatus", CheckSourceSettings.DEFAULT_OK_STATUS);
    s.quickMode = check.getBoolean("quickMode", false);
    s.search = check.getBoolean("search", true);
    s.discovery = check.getBoolean("discovery", true);
    s.info = check.getBoolean("info", true);
    s.category = check.getBoolean("category", true);
    s.content = check.getBoolean("content", true);
    s.kindNovel = check.getBoolean("kindNovel", true);
    s.kindComic = check.getBoolean("kindComic", true);
    s.kindVideo = check.getBoolean("kindVideo", true);
    s.kindAudio = check.getBoolean("kindAudio", true);
    s.kindFile = check.getBoolean("kindFile", true);

    pendingSave = SettingsBackup.toJson(s);
    String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    createDoc.launch(ExportNaming.settingsBackup(d));
  }

  /** 从备份 JSON 还原核心设置。 */
  private void restoreSettings(String text) {
    // 先完整解析成快照再一次性写回：解析失败时不会留下"只改了一半"的设置
    final SettingsBackup.Snapshot s;
    try {
      s = SettingsBackup.parse(text);
    } catch (IllegalArgumentException e) {
      toast(e.getMessage());
      return;
    }
    try {
      appPrefs().edit()
          .putString("mode", s.mode)
          .putBoolean("clean", s.clean)
          .putBoolean("onlyUsable", s.onlyUsable)
          .putInt("concurrency", s.concurrency)
          .apply();

      checkPrefs().edit()
          .putInt("timeout", s.timeout)
          .putInt("concurrency", s.checkConcurrency)
          .putString("keyword", s.keyword)
          .putString("okStatus", s.okStatus)
          .putBoolean("quickMode", s.quickMode)
          .putBoolean("search", s.search)
          .putBoolean("discovery", s.discovery)
          .putBoolean("info", s.info)
          .putBoolean("category", s.category)
          .putBoolean("content", s.content)
          .putBoolean("kindNovel", s.kindNovel)
          .putBoolean("kindComic", s.kindComic)
          .putBoolean("kindVideo", s.kindVideo)
          .putBoolean("kindAudio", s.kindAudio)
          .putBoolean("kindFile", s.kindFile)
          .apply();

      loadCheckSettings();
      restorePrefs();
      toast("设置已还原");
    } catch (Exception e) {
      toast("还原设置失败：" + e.getMessage());
    }
  }

  // ============= 新增：版本对比 / 增量合并 =============

  private Uri compareUriA;   // 版本对比第一份文件的 Uri（选择第二份后再对比）

  private void showCompareMenu() {
    new MaterialAlertDialogBuilder(this)
        .setTitle("版本对比（两份书源文件）")
        .setMessage("选择两份书源 JSON 文件，逐条对比「新增 / 修改 / 移除」，并可将新增并入去重流程。\n\n可一次选两份，或分两次各选一份。")
        .setItems(new String[]{"开始选择第一份", "取消"}, (d, w) -> {
          if (w == 0) compareOpen.launch(new String[]{"application/json", "text/*", "*/*"});
        })
        .show();
  }

  /** 选择第一份：若本次选了 ≥2 份则直接对比；只选 1 份则打开第二份选择。 */
  private void readCompareFileA(java.util.List<Uri> uris) {
    // 防御：调用方已判空，但方法自身也不该因为空列表崩（新增调用点容易漏）
    if (uris == null || uris.isEmpty()) { toast("未选择文件"); return; }
    if (uris.size() >= 2) {
      compareUriA = uris.get(0);
      loadCompareBoth(compareUriA, uris.get(1));
    } else {
      compareUriA = uris.get(0);
      toast("已选第一份，请再选择第二份");
      compareOpenB.launch(new String[]{"application/json", "text/*", "*/*"});
    }
  }

  /** 选择第二份：与已保存的第一份直接对比。 */
  private void readCompareFileB(java.util.List<Uri> uris) {
    if (compareUriA == null) { toast("请先选择第一份"); return; }
    if (uris == null || uris.isEmpty()) { toast("未选择文件"); return; }
    loadCompareBoth(compareUriA, uris.get(0));
  }

  /** 依次读取两份文件内容并解析对比。 */
  private void loadCompareBoth(Uri a, Uri b) {
    toast("正在读取两份文件对比…");
    new Thread(() -> {
      String ta = readUriText(a);
      String tb = readUriText(b);
      if (ta == null || tb == null) {
        runOnUiThread(() -> { if (!state.isDestroyed()) toast("读取文件失败"); });
        return;
      }
      SourceParser.ParseResult pa = SourceParser.parseArray(ta, 0);
      SourceParser.ParseResult pb = SourceParser.parseArray(tb, 0);
      runOnUiThread(() -> {
        if (state.isDestroyed()) return;
        showCompareResult(pa.getRecords(), pb.getRecords());
      });
    }, "compare-load").start();
  }

  private String readUriText(Uri u) {
    try (InputStream in = getContentResolver().openInputStream(u); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192]; int n; int total = 0;
      while ((n = in.read(b)) >= 0) {
        total += n;
        // 与本地导入保持同一上限：版本对比读的是两份文件，不设限时两个超大文件会直接把堆撑爆
        if (total > MAX_LOCAL_IMPORT_BYTES) throw new IOException("文件过大（超过 64MB）");
        o.write(b, 0, n);
      }
      return o.toString(StandardCharsets.UTF_8.name());
    } catch (Exception e) { return null; }
  }

  /** 对比两份记录：按 URL 分组，输出新增 / 修改 / 移除。 */
  private void showCompareResult(List<SourceRecord> a, List<SourceRecord> b) {
    SourceDiff.Result diff = SourceDiff.compute(a, b);
    Map<String, SourceRecord> mapA = diff.mapA;
    Map<String, SourceRecord> mapB = diff.mapB;
    List<String> added = diff.added;
    List<String> removed = diff.removed;
    List<String> modified = diff.modified;

    StringBuilder sb = new StringBuilder();
    sb.append("第一份 ").append(mapA.size()).append(" 条，第二份 ").append(mapB.size()).append(" 条\n");
    if (SourceDiff.isIdentical(diff)) { sb.append("\n两份文件内容一致，无差异。"); }
    else {
      appendBlock(sb, "新增（第二份独有：" + added.size() + "）", added, mapB);
      appendBlock(sb, "修改（两边都有但内容不同：" + modified.size() + "）", modified, null);
      appendBlock(sb, "移除（第一份独有：" + removed.size() + "）", removed, mapA);
    }

    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setText(sb.toString());
    tv.setTextIsSelectable(true);
    tv.setTextSize(13);
    int p = dp(16);
    tv.setPadding(p, p, p, p);
    sv.addView(tv);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this)
        .setTitle("版本对比结果")
        .setView(sv)
        .setNegativeButton("复制对比文本", (d, w) -> {
          ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          cm.setPrimaryClip(ClipData.newPlainText("书源版本对比", sb.toString()));
          toast("对比文本已复制");
        })
        .setNeutralButton("并入第二份新增", (d, w) -> {
          // 将第二份独有（新增）书源并入去重流程
          List<SourceRecord> newOnes = new ArrayList<>();
          for (String url : added) newOnes.add(mapB.get(url));
          if (newOnes.isEmpty()) { toast("没有可并入的新增书源"); return; }
          commitRecords("版本对比-新增", newOnes);
          toast("已并入 " + newOnes.size() + " 条新增书源");
        })
        .setPositiveButton("关闭", null)
        .show();
  }

  private void appendBlock(StringBuilder sb, String title, List<String> list, Map<String, SourceRecord> ref) {
    if (list.isEmpty()) return;
    sb.append("\n▶ ").append(title).append('\n');
    int shown = Math.min(list.size(), 20);
    for (int i = 0; i < shown; i++) {
      String url = list.get(i);
      String name = "";
      if (ref != null) { SourceRecord r = ref.get(url); if (r != null) name = r.getName(); }
      sb.append("• ").append(name.isEmpty() ? url : name).append("\n    ").append(url).append('\n');
    }
    if (list.size() > shown) sb.append("… 其余 ").append(list.size() - shown).append(" 条略\n");
  }

  // ================= ==== 第二批：校验历史 / 增量重验 / 质量排序 ================= ====

  /** 校验完成后记录历史（最多保留最近 20 次），持久化到 checkPrefs。 */
  private void rememberCheckHistory(List<CheckSourceResult> results) {
    CheckHistoryStore.Entry entry = CheckHistoryStore.summarize(results, System.currentTimeMillis());
    if (entry == null) return;
    List<CheckHistoryStore.Entry> merged = CheckHistoryStore.prepend(checkHistory, entry);
    checkHistory.clear();
    checkHistory.addAll(merged);
    persistCheckHistory();
  }

  /** 加载历史（内存 + prefs），在 onCreate 调用。 */
  private void loadCheckHistory() {
    checkHistory.clear();
    checkHistory.addAll(CheckHistoryStore.fromJson(checkPrefs().getString("checkHistory", "")));
  }

  private void persistCheckHistory() {
    checkPrefs().edit().putString("checkHistory", CheckHistoryStore.toJson(checkHistory)).apply();
  }

  private void loadHealthData() {
    final String raw = checkPrefs().getString("sourceHealth", "");
    if (raw == null || raw.isEmpty()) return;
    // 与 saveHealthData 对称：这份 JSON 可能十几 MB，在主线程解析会让冷启动明显卡顿。
    // 后台解析完再换进 tracker；这期间读到的仍是空数据，只影响健康看板这个辅助功能。
    new Thread(() -> {
      try {
        Object root = MiniJson.parse(raw);
        if (!(root instanceof java.util.List)) return;
        final SourceHealthTracker loaded = new SourceHealthTracker();
        loaded.fromJsonList((java.util.List<Object>) root);
        runOnUiThread(() -> {
          if (state.isDestroyed()) return;
          // 配置变更已接管旧实例数据时不能被磁盘旧档覆盖
          if (!healthTracker.getAll().isEmpty()) return;
          healthTracker = loaded;
        });
      } catch (Exception ignored) {}
    }, "health-load").start();
  }

  private void saveHealthData() {
    // 健康数据是「每源 × 最多 20 条历史」，万级书源时 JSON 可达十几 MB。
    // 之前直接在 UI 线程序列化，校验一结束就卡住（Android 会把 Input dispatching 判成 ANR）。
    // 改成后台线程序列化 + apply() 异步落盘；先拷一份快照，避免后台序列化时被下一轮校验改动。
    final List<Object> snapshot = healthTracker.toJsonList();
    new Thread(() -> {
      try {
        String json = MiniJson.stringify(snapshot);
        checkPrefs().edit().putString("sourceHealth", json).apply();
      } catch (Exception ignored) {
        // 健康数据属于辅助信息，写失败不影响主流程
      }
    }, "health-save").start();
  }

  /** 展示校验历史：弹窗列出历次记录，可查看详情 / 一键重验该次失败源。 */
  private void showCheckHistory() {
    if (checkHistory.isEmpty()) { toast("暂无校验历史"); return; }
    List<String> lines = new ArrayList<>();
    final java.util.List<CheckHistoryStore.Entry> snapshot = new ArrayList<>(checkHistory);
    for (CheckHistoryStore.Entry e : checkHistory) {
      lines.add(e.timeText + "  共" + e.total + " · 可用" + e.ok + " · 失败" + e.fail + " · 超时" + e.timeout);
    }
    new MaterialAlertDialogBuilder(this)
        .setTitle("校验历史（最近 " + checkHistory.size() + " 次）")
        .setItems(lines.toArray(new String[0]), (d, w) -> {
          CheckHistoryStore.Entry e = snapshot.get(w);
          // "重验"项按有无失败源条件加入，下标会整体后移：必须按实际加入位置分发，
          // 否则全部成功的批次里点"取消"会打开快源排行
          List<String> opts = new ArrayList<>();
          final int idxDetail = opts.size(); opts.add("查看该次失败源明细");
          final int idxRecheck;
          if (!e.badUrls.isEmpty()) { idxRecheck = opts.size(); opts.add("重验该次失败/超时源（" + e.badUrls.size() + " 条）"); }
          else idxRecheck = -1;
          final int idxSpeed = opts.size(); opts.add("查看当前结果最快/最慢");
          opts.add("取消");
          new MaterialAlertDialogBuilder(this)
              .setTitle("第 " + (w + 1) + " 次校验")
              .setItems(opts.toArray(new String[0]), (d2, w2) -> {
                if (w2 == idxDetail) showHistoryBadDetail(e);
                else if (w2 == idxRecheck) recheckUrls(e.badUrls, "该次失败源");
                else if (w2 == idxSpeed) showHistorySpeed(e);
              })
              .show();
        })
        .setNegativeButton("清空历史", (d, w) -> {
          checkHistory.clear(); persistCheckHistory(); toast("已清空校验历史");
        })
        .setPositiveButton("关闭", null)
        .show();
  }

  private void showHistoryBadDetail(CheckHistoryStore.Entry e) {
    if (e.badUrls.isEmpty()) { toast("该次无失败/超时源"); return; }
    StringBuilder sb = new StringBuilder("失败/超时 " + e.badUrls.size() + " 条\n");
    int shown = 0;
    for (String u : e.badUrls) { if (shown >= 100) break; shown++; sb.append("• ").append(u).append('\n'); }
    if (e.badUrls.size() > shown) sb.append("… 其余 ").append(e.badUrls.size() - shown).append(" 条略\n");
    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setText(sb.toString()); tv.setTextSize(13); tv.setTextIsSelectable(true);
    sv.addView(tv);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this).setTitle("失败源明细").setView(sv).setPositiveButton("关闭", null).show();
  }

  /** 展示当前校验结果中的最快可用源（历史记录未保存逐源耗时，无法回放某一次）。 */
  private void showHistorySpeed(CheckHistoryStore.Entry e) {
    if (checkResults.isEmpty()) { toast("当前无校验结果可展示"); return; }
    List<CheckSourceResult> sorted = new ArrayList<>(checkResults);
    sorted.sort((a, b) -> Long.compare(a.respondTimeMs, b.respondTimeMs));
    StringBuilder sb = new StringBuilder();
    sb.append("最快 ").append(sorted.size()).append(" 个可用源：\n");
    int n = 0;
    for (CheckSourceResult r : sorted) {
      if (!r.isUsable()) continue;
      if (n >= 10) break; n++;
      sb.append("• ").append(r.source.getName()).append("  ").append(r.respondTimeMs).append("ms\n");
    }
    if (n == 0) sb.append("（无可用源）\n");
    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setText(sb.toString()); tv.setTextSize(13); tv.setTextIsSelectable(true);
    sv.addView(tv);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this).setTitle("当前结果快源排序").setView(sv).setPositiveButton("关闭", null).show();
  }

  /** 一键重验上次本次失败/超时源。 */
  private void recheckFailedSources() {
    if (result == null || result.getRetained().isEmpty()) { toast("请先解析书源再进行重验"); return; }
    if (!pendingCheckUrls.isEmpty()) {
      toast("从上次中断处继续校验 " + pendingCheckUrls.size() + " 条书源…");
      recheckUrls(new ArrayList<>(pendingCheckUrls), "上次未完成书源");
      return;
    }
    // 用最近一次校验的失败源
    List<String> badUrls = null;
    for (CheckHistoryStore.Entry e : checkHistory) if (!e.badUrls.isEmpty()) { badUrls = e.badUrls; break; }
    if (badUrls == null) { toast("历史上没有失败/超时源可重验"); return; }
    recheckUrls(badUrls, "最近失败源");
  }

  private void recheckUrls(List<String> urls, String label) {
    if (result == null || result.getRetained().isEmpty()) { toast("请先解析书源"); return; }
    List<SourceRecord> targets = RecheckPlanner.plan(result.getRetained(), new HashSet<>(urls));
    if (targets.isEmpty()) { toast("这些失败源已不在当前结果中，无需重验"); return; }
    toast("开始" + label + " " + targets.size() + " 条书源…");
    runCheck(checkSettings, targets);
  }

  /** 单条书源快速重验（明细列表里的「重验」按钮）。 */
  private void recheckSingle(SourceRecord source) {
    if (source == null || source.getUrl() == null) { toast("该书源没有地址，无法校验"); return; }
    List<SourceRecord> targets = RecheckPlanner.planSingle(
        result == null ? null : result.getRetained(), source.getUrl());
    // 校验结果里的源可能已不在 retained（例如被用户手动移除），此时直接用传入对象
    if (targets.isEmpty()) targets = new ArrayList<>(Collections.singletonList(source));
    toast("正在重验「" + source.getName() + "」…");
    runCheck(checkSettings, targets);
  }

  @SuppressWarnings("unchecked")
  private void loadPendingCheckUrls() {
    pendingCheckUrls.clear();
    String raw = checkPrefs().getString("pendingCheckUrls", "");
    if (raw == null || raw.isEmpty()) return;
    try {
      Object value = MiniJson.parse(raw);
      if (!(value instanceof List)) return;
      for (Object url : (List<Object>) value) {
        if (url != null && !String.valueOf(url).trim().isEmpty()) pendingCheckUrls.add(String.valueOf(url));
      }
    } catch (Exception ignored) {}
  }

  private void persistPendingCheckUrls() {
    List<Object> payload = new ArrayList<>();
    payload.addAll(pendingCheckUrls);
    checkPrefs().edit().putString("pendingCheckUrls", MiniJson.stringify(payload)).apply();
  }

  // ================= ==== 第二批：去重规则预设 ================= ====

  private final List<Map<String, Object>> dedupePresets = new ArrayList<>();

  private void loadDedupePresets() {
    dedupePresets.clear();
    String raw = checkPrefs().getString("dedupePresets", "");
    if (raw == null || raw.isEmpty()) return;
    try {
      Object root = MiniJson.parse(raw);
      if (!(root instanceof java.util.List)) return;
      for (Object o : (java.util.List<?>) root) if (o instanceof Map) dedupePresets.add((Map<String, Object>) o);
    } catch (Exception ignored) {}
  }

  private void persistDedupePresets() { checkPrefs().edit().putString("dedupePresets", MiniJson.stringify(dedupePresets)).apply(); }

  private void showDedupePresetMenu() {
    loadDedupePresets();
    List<String> opts = new ArrayList<>();
    opts.add("保存当前规则为新预设");
    List<String> presetNames = new ArrayList<>();
    for (Map<String, Object> p : dedupePresets) { opts.add("套用预设：" + p.get("name")); presetNames.add(String.valueOf(p.get("name"))); }
    opts.add("管理预设（删除）");
    opts.add("取消");
    new MaterialAlertDialogBuilder(this)
        .setTitle("去重规则预设")
        .setMessage("预设 = 去重模式 + 清理名称 + 并发数。当前：" + mode.label()
            + " · 清理" + (cleanNames ? "开" : "关") + " · 并发" + concurrency)
        .setItems(opts.toArray(new String[0]), (d, w) -> {
          if (w == 0) saveCurrentPresetDialog();
          else if (w > 0 && w <= dedupePresets.size()) applyPreset(dedupePresets.get(w - 1));
          else if (w == dedupePresets.size() + 1) managePresets();
        })
        .setPositiveButton("关闭", null)
        .show();
  }

  private void saveCurrentPresetDialog() {
    final android.widget.EditText et = new android.widget.EditText(this);
    et.setHint("预设名称，例如：小说合集");
    new MaterialAlertDialogBuilder(this)
        .setTitle("保存当前规则为新预设")
        .setMessage("模式：" + mode.label() + " · 清理" + (cleanNames ? "开" : "关") + " · 并发" + concurrency)
        .setView(et)
        .setPositiveButton("保存", (d, w) -> {
          String name = et.getText() == null ? "" : et.getText().toString().trim();
          if (name.isEmpty()) { toast("预设名称不能为空"); return; }
          loadDedupePresets();
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("name", name);
          p.put("mode", mode.name());
          p.put("clean", cleanNames);
          p.put("concurrency", concurrency);
          // 同名覆盖
          for (int i = 0; i < dedupePresets.size(); i++) {
            if (name.equals(String.valueOf(dedupePresets.get(i).get("name")))) { dedupePresets.set(i, p); persistDedupePresets(); toast("预设已更新：" + name); return; }
          }
          dedupePresets.add(p); persistDedupePresets(); toast("已保存预设：" + name);
        })
        .setNegativeButton("取消", null)
        .show();
  }

  private void applyPreset(Map<String, Object> p) {
    try {
      String modeName = String.valueOf(p.get("mode"));
      DedupeMode m = DedupeMode.STANDARD;
      if (DedupeMode.STRICT.name().equals(modeName)) m = DedupeMode.STRICT;
      else if (DedupeMode.NAME.name().equals(modeName)) m = DedupeMode.NAME;
      else if (DedupeMode.AGGRESSIVE.name().equals(modeName)) m = DedupeMode.AGGRESSIVE;
      mode = m;
      operationMode.select(m);
      int checkId = m == DedupeMode.STRICT ? R.id.modeStrict : (m == DedupeMode.NAME ? R.id.modeName : (m == DedupeMode.AGGRESSIVE ? R.id.modeAggressive : R.id.modeStandard));
      if (modeGroup != null) modeGroup.check(checkId);
      boolean clean = p.get("clean") instanceof Boolean ? (Boolean) p.get("clean") : Boolean.parseBoolean(String.valueOf(p.get("clean")));
      cleanNames = clean;
      if (switchCleanNames != null) switchCleanNames.setChecked(clean);
      if (p.get("concurrency") instanceof Number) {
        int c = Math.max(1, Math.min(5, ((Number) p.get("concurrency")).intValue()));
        concurrency = c;
        if (sliderConcurrency != null) sliderConcurrency.setValue(c);
      }
      savePrefs();
      if (result != null) recompute(partial);
      toast("已套用预设：" + p.get("name"));
    } catch (Exception e) { toast("套用预设失败：" + e.getMessage()); }
  }

  private void managePresets() {
    loadDedupePresets();
    if (dedupePresets.isEmpty()) { toast("暂无预设"); return; }
    List<String> lines = new ArrayList<>();
    for (Map<String, Object> p : dedupePresets) lines.add(String.valueOf(p.get("name")));
    new MaterialAlertDialogBuilder(this)
        .setTitle("管理预设（点击删除）")
        .setItems(lines.toArray(new String[0]), (d, w) -> {
          dedupePresets.remove(w); persistDedupePresets(); toast("已删除预设");
        })
        .setPositiveButton("关闭", null)
        .show();
  }

  // ============= 第二批：变更差异对比报告 =============

  /** 生成去重/合并后的「本次变更说明」并弹窗，可复制/导出。 */
  private void showChangeReport() {
    if (result == null) { toast("尚无去重结果，无法生成变更报告"); return; }
    DedupeResult r = result;
    int original = r.getOriginalCount();
    int kept = r.getRetained().size();
    int removed = r.getDuplicateCount();
    int invalid = r.getInvalid().size();
    StringBuilder sb = new StringBuilder();
    sb.append("去重模式：").append(mode.label()).append('\n');
    sb.append("本次输入原始书源 ").append(original).append(" 条\n");
    sb.append("· 保留（可用基础）：").append(kept).append(" 条\n");
    sb.append("· 判定重复而移除：").append(removed).append(" 条\n");
    sb.append("· 无效（缺URL/非法URL）：").append(invalid).append(" 条\n");
    int net = original - removed - invalid;
    sb.append("\n净变化：原始 ").append(original).append(" → 保留 ").append(kept).append("（约精简 ")
        .append(Math.max(0, original - kept)).append(" 条重复）\n");
    // 校验结果补充
    if (!checkResults.isEmpty()) {
      int ok = 0, fail = 0, timeout = 0;
      for (CheckSourceResult c : checkResults) {
        if (c.status == CheckSourceResult.Status.SUCCESS) ok++;
        else if (c.status == CheckSourceResult.Status.TIMEOUT) timeout++;
        else if (c.status == CheckSourceResult.Status.FAILED) fail++;
      }
      sb.append("\n最新校验：可用 ").append(ok).append(" · 失败 ").append(fail).append(" · 超时 ").append(timeout).append("（共 ")
          .append(checkResults.size()).append(" 条）\n");
    }
    sb.append("\n生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
    ScrollView sv = new ScrollView(this);
    TextView tv = new TextView(this);
    tv.setText(sb.toString()); tv.setTextSize(14); tv.setTextIsSelectable(true);
    int p = dp(16); tv.setPadding(p, p, p, p); sv.addView(tv);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    new MaterialAlertDialogBuilder(this)
        .setTitle("变更差异对比报告")
        .setView(sv)
        .setNegativeButton("复制报告", (d, w) -> {
          ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          cm.setPrimaryClip(ClipData.newPlainText("书源变更报告", sb.toString()));
          toast("报告已复制");
        })
        .setNeutralButton("导出为 JSON 文件", (d, w) -> {
          // 生成一个包含统计信息的 JSON 报告文件
          Map<String, Object> rep = new LinkedHashMap<>();
          rep.put("mode", mode.label());
          rep.put("originalCount", original);
          rep.put("kept", kept);
          rep.put("removedDuplicate", removed);
          rep.put("invalid", invalid);
          rep.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
          pendingSave = MiniJson.stringify(rep);
          String date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
          createDoc.launch(ExportNaming.changeReport(date));
        })
        .setPositiveButton("关闭", null)
        .show();
  }
}
