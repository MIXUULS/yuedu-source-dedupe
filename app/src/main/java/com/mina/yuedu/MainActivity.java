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
import com.mina.yuedu.model.*;
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
  private MaterialButton btnCheckDetail, btnUrlHistory, btnExportOk, btnExportBad, btnExportSkip, btnMergeDomain, btnExportCsv;
  private TextInputLayout tilCheckSearch;
  private TextInputEditText etCheckSearch;
  /** 搜索过滤后的校验结果列表，null 表示不使用过滤。 */
  private List<CheckSourceResult> filteredCheckResults;
  private View cardRunning, cardResult;
  private LinearLayout boxKindExport, rowCheckTiles;
  private TextView tvExportPreview;
  private final Map<SourceKind, KindRow> kindRows = new EnumMap<>(SourceKind.class);
  private LinearProgressIndicator progressBar;
  private MaterialSwitch switchCleanNames, switchOnlyUsable;
  private MaterialButtonToggleGroup modeGroup;
  private Slider sliderConcurrency;
  private MaterialButton btnParse, btnStop, btnCheck, btnImport, btnSave;

  private final SourceBuckets buckets = new SourceBuckets();
  private final List<InvalidSource> extraInvalid = new ArrayList<>();
  private final List<String> discovered = new ArrayList<>();
  private static final int MAX_LOCAL_IMPORT_BYTES = 64 * 1024 * 1024; // 本地导入单文件上限 64MB
  private final OperationMode operationMode = new OperationMode(DedupeMode.STANDARD);
  private DedupeMode mode = DedupeMode.STANDARD;
  private DedupeResult result;
  private List<CheckSourceResult> checkResults = new ArrayList<>();
  private FetchManager fetchManager;
  private CheckSourceEngine checkEngine;
  private boolean cleanNames, partial, discard, onlyUsable;
  private boolean fetchRunning, checkRunning, checkCancelRequested;
  private volatile boolean destroyed;
  private int recomputeGen;
  private volatile int fetchGen;
  private int concurrency = 4, nextOrder, localFileCount, currentTab;
  private final CheckSourceSettings checkSettings = new CheckSourceSettings();
  private String pendingSave, backgroundTaskText = "";
  private YckSite yckSite = YckSite.MAIN;
  private YckWebClient yckClient;
  private boolean yckLoaded, yckAutoFellBack;

  private ActivityResultLauncher<String[]> openDocs;
  private ActivityResultLauncher<String> createDoc, csvDoc;

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
    toolbar.setOnClickListener(v -> showAbout());
    tabs = findViewById(R.id.tabs);
    pageContainer = findViewById(R.id.pageContainer);
    applySystemBarInsets(findViewById(R.id.rootCoordinator), findViewById(R.id.appBar), pageContainer);
    tabs.addTab(tabs.newTab().setText(R.string.tab_dedupe));
    tabs.addTab(tabs.newTab().setText(R.string.tab_yck));

    yckSite = YckSite.fromPreference(getSharedPreferences("yck", MODE_PRIVATE).getString("site", "main"));
    loadCheckSettings();

    dedupePage = getLayoutInflater().inflate(R.layout.page_dedupe, pageContainer, false);
    pageContainer.addView(dedupePage);
    bindDedupe(dedupePage);
    restorePrefs();

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
      try (OutputStream o = getContentResolver().openOutputStream(uri)) {
        o.write(pendingSave.getBytes(StandardCharsets.UTF_8));
        toast("已保存");
      } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
    });
    csvDoc = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
      if (uri == null || pendingSave == null) return;
      try (OutputStream o = getContentResolver().openOutputStream(uri)) {
        o.write(pendingSave.getBytes(StandardCharsets.UTF_8));
        toast("已导出 CSV");
      } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
    });

    updateModeDesc();
  }

  @Override protected void onDestroy() {
    destroyed = true;
    savePrefs(); // 记住最后输入的 URL 等状态
    // 停止后台任务，避免旋转屏幕/退出后回调操作已销毁的视图（ANR/崩溃/泄漏）
    if (fetchManager != null) fetchManager.cancel(true);
    if (checkEngine != null) checkEngine.cancel();
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
    btnExportOk = root.findViewById(R.id.btnExportOk);
    btnExportBad = root.findViewById(R.id.btnExportBad);
    btnExportSkip = root.findViewById(R.id.btnExportSkip);
    btnMergeDomain = root.findViewById(R.id.btnMergeDomain);
    btnExportCsv = root.findViewById(R.id.btnExportCsv);
    btnExportOk.setOnClickListener(v -> exportByCategory("可用"));
    btnExportBad.setOnClickListener(v -> exportByCategory("不可用"));
    btnExportSkip.setOnClickListener(v -> exportByCategory("非HTTP"));
    btnMergeDomain.setOnClickListener(v -> showMergeDomain());
    btnExportCsv.setOnClickListener(v -> exportCsv());
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
      else mode = DedupeMode.AGGRESSIVE;
      operationMode.select(mode);
      updateModeDesc();
      savePrefs();
      if (result != null) recompute(partial);
    });
    sliderConcurrency.addOnChangeListener((s, value, fromUser) -> concurrency = Math.round(value));
    switchCleanNames.setOnCheckedChangeListener((b, on) -> { cleanNames = on; savePrefs(); if (result != null) recompute(partial); });
    switchOnlyUsable.setOnCheckedChangeListener((b, on) -> { onlyUsable = on; savePrefs(); refreshExportPreview(); });
    btnParse.setOnClickListener(v -> startParse());
    btnStop.setOnClickListener(v -> stopCurrentTask());
    btnCheck.setOnClickListener(v -> showCheckDialog());
    btnImport.setOnClickListener(v -> importReader());
    btnSave.setOnClickListener(v -> saveJson());
  }

  private void updateModeDesc() { tvModeDesc.setText(mode.description()); }

  /** 必须在 buckets 锁内调用：为记录分配互不冲突的全局顺序号（消除多线程 order 竞争）。 */
  private List<SourceRecord> reorder(List<SourceRecord> records) {
    if (records == null || records.isEmpty()) return records;
    List<SourceRecord> out = new ArrayList<>(records.size());
    for (SourceRecord s : records) out.add(s.withOrder(nextOrder++));
    return out;
  }

  private List<String> getUrls() {
    List<String> x = new ArrayList<>();
    CharSequence cs = etUrls.getText();
    if (cs == null) return x;
    for (String s : cs.toString().split("\\r?\\n")) if (!s.trim().isEmpty()) x.add(s.trim());
    return x;
  }

  private void startParse() {
    operationMode.select(mode); operationMode.start(); mode = operationMode.resultMode();
    cleanNames = switchCleanNames.isChecked();
    concurrency = Math.round(sliderConcurrency.getValue());
    savePrefs();
    partial = false; discard = false; checkResults.clear();
    synchronized (buckets) { buckets.replaceNetwork(Collections.emptyList()); }
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    List<String> urls = getUrls();
    if (!ParseRequestDecision.shouldRun(buckets.localCount(), urls.size())) { toast("请选择本地文件或输入网络地址"); return; }
    if (urls.isEmpty()) { recompute(false); return; }
    rememberUrls(urls);
    final int gen = ++fetchGen; // 新一轮任务代际：旧任务的数据/收尾不再生效
    startFetch(urls, false, gen);
  }

  private void startFetch(List<String> urls, boolean followUp, final int gen) {
    fetchRunning = true;
    checkRunning = false;
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
          if (destroyed || gen != fetchGen) return;
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
        if (gen != fetchGen) return; // 已有新任务，旧任务数据不再提交
        // p 已在 FetchManager 解析完成，无需重复解析
        synchronized (buckets) {
          if (!p.getRecords().isEmpty()) {
            buckets.addNetwork(reorder(p.getRecords()));
          } else {
            String indirect = SourceParser.extractIndirectUrl(body);
            List<String> found = new ArrayList<>();
            if (indirect != null && !indirect.isEmpty()) found.add(indirect);
            found.addAll(SourceParser.discoverYckJsonUrls(body, url));
            if (found.isEmpty()) {
              synchronized (extraInvalid) {
                if (!p.getInvalid().isEmpty()) extraInvalid.addAll(p.getInvalid());
                else extraInvalid.add(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, url + " · 响应非书源数组"));
              }
            } else synchronized (discovered) { discovered.addAll(found); }
          }
        }
      }
      @Override public void onFailure(String u, String m) {
        if (gen != fetchGen) return;
        synchronized (extraInvalid) { extraInvalid.add(new InvalidSource(InvalidSource.Kind.NETWORK_FAILURE, u + " · " + m)); }
      }
      @Override public void onFinished(boolean cancelled, boolean keep) {
        runOnUiThread(() -> {
          if (destroyed || gen != fetchGen) return;
          if (discard) {
            discard = false;
            fetchRunning = false;
            fetchManager = null;
            updateBackgroundTask("");
            setTaskRunning(false);
            cardRunning.setVisibility(View.GONE);
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
    if (checkRunning) {
      stopCheck();
      return;
    }
    if (fetchRunning) stopParse();
  }

  private void stopCheck() {
    if (checkEngine == null || checkCancelRequested) return;
    checkCancelRequested = true;
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
        else if (w == 1) { discard = true; fetchManager.cancel(false); }
      }).show();
  }

  private void recompute(boolean isPartial) {
    final int gen = ++recomputeGen;
    fetchRunning = false;
    checkRunning = false;
    checkCancelRequested = false;
    updateBackgroundTask("");
    List<SourceRecord> copy; synchronized (buckets) { copy = buckets.all(); }
    final DedupeMode m = mode;
    final boolean clean = cleanNames;
    final boolean partialFlag = isPartial;
    // 去重计算移入后台线程：万级书源在主线程做 URL 规范化+分组+排序会 ANR/闪退
    new Thread(() -> {
      final DedupeResult base = DedupeEngine.run(copy, m, clean);
      runOnUiThread(() -> {
        if (destroyed || gen != recomputeGen) return; // Activity 已销毁，或已有更新的计算结果
        List<InvalidSource> all = new ArrayList<>(base.getInvalid());
        synchronized (extraInvalid) { all.addAll(extraInvalid); }
        result = new DedupeResult(copy.size(), base.getRetained(), base.getDuplicateGroups(), all);
        partial = partialFlag;
        setTaskRunning(false);
        cardRunning.setVisibility(View.GONE);
        cardResult.setVisibility(View.VISIBLE);
        boolean hasRetained = result != null && !result.getRetained().isEmpty();
        // 校验入口始终可点击；无书源时 showCheckDialog 会给出明确提示，避免灰色看起来像未实现
        btnCheck.setEnabled(true);
        btnImport.setEnabled(hasRetained);
        btnSave.setEnabled(hasRetained);
        tvStats.setText(ResultSummary.format(mode, buckets.localCount(), buckets.networkCount(), result, isPartial));
        setStatValue(R.id.tileOriginal, result.getOriginalCount());
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
      });
    }, "dedupe-recompute").start();
  }

  private void setTaskRunning(boolean running) {
    btnParse.setEnabled(!running);
    btnCheck.setEnabled(!running);
    btnImport.setEnabled(!running && result != null && !result.getRetained().isEmpty());
    btnSave.setEnabled(!running && result != null && !result.getRetained().isEmpty());
    btnStop.setEnabled(running && !checkCancelRequested);
    if (!running) btnStop.setText("停止");
    for (int i = 0; i < modeGroup.getChildCount(); i++) modeGroup.getChildAt(i).setEnabled(!running);
    switchCleanNames.setEnabled(!running);
    sliderConcurrency.setEnabled(!running);
    for (KindRow row : kindRows.values()) {
      if (row.sw != null) row.sw.setEnabled(!running);
    }
  }

  private void clearAll() {
    if (fetchManager != null) { discard = true; fetchManager.cancel(false); }
    if (checkEngine != null) checkEngine.cancel();
    fetchGen++; // 使进行中的旧任务回调全部失效
    synchronized (buckets) { buckets.clearAll(); nextOrder = 0; }
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    localFileCount = 0; result = null; checkResults.clear(); partial = false;
    fetchRunning = false; checkRunning = false; checkCancelRequested = false;
    updateBackgroundTask("");
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
        runOnUiThread(() -> { if (!destroyed) toast("文件读取失败：" + e.getMessage()); });
        return;
      }
      runOnUiThread(() -> { if (destroyed) return; commitLocal(name, p); });
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
    else if (DedupeMode.AGGRESSIVE.name().equals(m)) checkId = R.id.modeAggressive;
    if (modeGroup != null) modeGroup.check(checkId);
    if (switchCleanNames != null) switchCleanNames.setChecked(p.getBoolean("clean", false));
    if (switchOnlyUsable != null) switchOnlyUsable.setChecked(p.getBoolean("onlyUsable", false));
    if (sliderConcurrency != null) {
      sliderConcurrency.setValue(Math.max(1f, Math.min(5f, (float) p.getInt("concurrency", 4))));
    }
    if (btnUrlHistory != null) btnUrlHistory.setVisibility(getUrlHistory().isEmpty() ? View.GONE : View.VISIBLE);
  }

  /** 保存当前 URL 输入、模式、开关与并发数，下次打开自动恢复。 */
  private void savePrefs() {
    appPrefs().edit()
        .putString("urls", etUrls.getText() == null ? "" : etUrls.getText().toString())
        .putString("mode", mode.name())
        .putBoolean("clean", cleanNames)
        .putBoolean("onlyUsable", onlyUsable)
        .putInt("concurrency", concurrency)
        .apply();
  }

  /** 最近使用的 URL 地址（最多 10 条，最新在前）。 */
  private List<String> getUrlHistory() {
    String raw = appPrefs().getString("urlHistory", "");
    List<String> list = new ArrayList<>();
    if (raw != null && !raw.isEmpty()) {
      for (String s : raw.split("\n")) if (!s.trim().isEmpty()) list.add(s.trim());
    }
    return list;
  }

  private void rememberUrls(List<String> urls) {
    if (urls == null || urls.isEmpty()) return;
    List<String> hist = new ArrayList<>(getUrlHistory());
    for (String u : urls) {
      hist.remove(u);
      hist.add(0, u);
    }
    while (hist.size() > 10) hist.remove(hist.size() - 1);
    StringBuilder sb = new StringBuilder();
    for (String s : hist) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(s);
    }
    appPrefs().edit().putString("urlHistory", sb.toString()).apply();
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

  private void saveCheckSettings() {
    checkSettings.normalize();
    checkPrefs().edit()
        .putInt("timeout", (int) checkSettings.timeoutSeconds)
        .putInt("concurrency", checkSettings.concurrency)
        .putString("keyword", checkSettings.keyword)
        .putString("okStatus", checkSettings.okStatusRanges)
        .putBoolean("quickMode", checkSettings.quickMode)
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

    slider.setValue(checkSettings.timeoutSeconds);
    label.setText("校验超时(秒)：" + checkSettings.timeoutSeconds);
    checkConcurrencySlider.setValue(checkSettings.concurrency);
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
    List<SourceRecord> targets = new ArrayList<>(result.getRetained());
    fetchRunning = false;
    checkRunning = true;
    checkCancelRequested = false;
    checkResults.clear();
    cardRunning.setVisibility(View.VISIBLE);
    setTaskRunning(true);
    btnStop.setText("停止校验");
    btnStop.setEnabled(true);
    updateBackgroundTask("后台校验 0 / " + targets.size());
    tvProgress.setText("正在校验书源 0 / " + targets.size());
    tvStatus.setText("超时 " + settings.timeoutSeconds + "s · 并发 " + settings.concurrency
        + " · 关键词 " + settings.keyword);
    progressBar.setProgressCompat(0, false);
    checkEngine = new CheckSourceEngine(settings.concurrency);
    new Thread(() -> checkEngine.checkAll(targets, settings, new CheckSourceEngine.Listener() {
      @Override public void onProgress(int done, int total, String currentName) {
        runOnUiThread(() -> {
          if (destroyed) return;
          progressBar.setProgressCompat(done * 100 / Math.max(1, total), true);
          tvProgress.setText("正在校验书源 " + done + " / " + total);
          tvStatus.setText(currentName == null ? "" : currentName);
          updateBackgroundTask("后台校验 " + done + " / " + total);
        });
      }
      @Override public void onItem(CheckSourceResult r) {}
      @Override public void onFinished(List<CheckSourceResult> results) {
        runOnUiThread(() -> {
          if (destroyed) return;
          boolean wasCancelled = checkCancelRequested || (checkEngine != null && checkEngine.isCancelled());
          checkResults = results;
          checkRunning = false;
          checkCancelRequested = false;
          checkEngine = null;
          updateBackgroundTask("");
          setTaskRunning(false);
          cardRunning.setVisibility(View.GONE);
          switchOnlyUsable.setVisibility(checkResults.isEmpty() ? View.GONE : View.VISIBLE);
          renderCheckStats();
          toast(wasCancelled ? "校验已停止，保留已完成 " + results.size() + " 条" : "校验完成");
        });
      }
    }), "source-check").start();
  }

  private void renderCheckStats() {
    int ok = 0, fail = 0, timeout = 0;
    Map<String, Integer> reasonCount = new LinkedHashMap<>();
    for (CheckSourceResult r : checkResults) {
      if (r.status == CheckSourceResult.Status.SKIPPED) continue;
      if (r.status == CheckSourceResult.Status.SUCCESS) ok++;
      else if (r.status == CheckSourceResult.Status.TIMEOUT) timeout++;
      else {
        fail++;
        String reason = r.message;
        if (reason == null || reason.trim().isEmpty()) reason = "未知原因";
        reason = reason.trim();
        int idx = reason.indexOf('，');
        if (idx < 0) idx = reason.indexOf(',');
        if (idx > 0) reason = reason.substring(0, idx);
        if (reason.length() > 14) reason = reason.substring(0, 14) + "…";
        reasonCount.put(reason, reasonCount.getOrDefault(reason, 0) + 1);
      }
    }
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
    // 分类导出与合并同域：有校验结果时显示
    boolean hasCheck = !checkResults.isEmpty();
    if (btnExportOk != null) { /* 显隐由 renderCheckStats 的判断控制，由 exportByCategory 方法检查具体数量 */ }
    // 控制分类导出区域的显隐（在 renderCheckStats 中通过 rowExportCat 控制）
    LinearLayout rowCat = findViewById(R.id.rowExportCat);
    if (rowCat != null) rowCat.setVisibility(hasCheck ? View.VISIBLE : View.GONE);
    // 合并同域按钮
    if (btnMergeDomain != null) btnMergeDomain.setVisibility(hasCheck ? View.VISIBLE : View.GONE);
    if (btnExportCsv != null) btnExportCsv.setVisibility(hasCheck ? View.VISIBLE : View.GONE);
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
    if (keyword.isEmpty()) {
      filteredCheckResults = null;
    } else {
      filteredCheckResults = new ArrayList<>();
      String kw = keyword.toLowerCase(Locale.CHINA);
      for (CheckSourceResult r : checkResults) {
        String name = r.source.getName();
        String url = r.source.getUrl();
        if ((name != null && name.toLowerCase(Locale.CHINA).contains(kw))
            || (url != null && url.toLowerCase(Locale.CHINA).contains(kw))) {
          filteredCheckResults.add(r);
        }
      }
    }
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
    // 按耗时降序（最慢的排前面，便于发现慢源）
    list.sort((a, b) -> Long.compare(b.respondTimeMs, a.respondTimeMs));
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(8), dp(20), dp(8));
    int shown = 0;
    for (CheckSourceResult r : list) {
      if (shown >= 200) break;
      shown++;
      String statusText;
      int colorRes;
      switch (r.status) {
        case SUCCESS: statusText = "✓"; colorRes = R.color.md_theme_success; break;
        case TIMEOUT: statusText = "⏱"; colorRes = R.color.md_theme_secondary; break;
        default: statusText = "✗"; colorRes = R.color.md_theme_error;
      }
      String name = r.source.getName();
      if (name == null || name.trim().isEmpty()) name = r.source.getUrl();
      String msg = r.message == null || r.message.trim().isEmpty() ? r.status.name() : r.message.trim();
      TextView tv = new TextView(this);
      tv.setTextSize(13);
      tv.setTextColor(ContextCompat.getColor(this, colorRes));
      tv.setText(statusText + " " + (name == null ? "(无名)" : name)
          + "   " + r.respondTimeMs + "ms\n    " + msg);
      tv.setPadding(0, dp(5), 0, dp(5));
      content.addView(tv);
    }
    ScrollView sv = new ScrollView(this);
    sv.addView(content);
    int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
    sv.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxH));
    String title = (filter == 1 ? "成功" : filter == 2 ? "失败" : filter == 3 ? "超时" : "全部")
        + " " + list.size() + " 条" + (list.size() > shown ? "（显示前 " + shown + " 条）" : "");
    new MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(sv)
        .setPositiveButton("关闭", null)
        .show();
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
      sb.append("    合并 ").append(g.getRemoved().size()).append(" 个：");
      int c = 0;
      for (SourceRecord r : g.getRemoved()) {
        if (c >= 3) { sb.append("…"); break; }
        String n = r.getName();
        if (n == null || n.trim().isEmpty()) n = r.getUrl();
        if (c > 0) sb.append("、");
        sb.append(n == null ? "(无名)" : n);
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
        .setTitle("重复组 " + groups.size() + " 个" + (groups.size() > shown ? "（显示前 " + shown + " 个）" : ""))
        .setView(sv)
        .setPositiveButton("关闭", null)
        .show();
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
    List<SourceRecord> base;
    if (!onlyUsable || checkResults.isEmpty()) {
      base = result.getRetained();
    } else {
      Set<String> good = new HashSet<>();
      for (CheckSourceResult r : checkResults) if (r.isUsable() && r.source.getUrl() != null) good.add(r.source.getUrl());
      base = new ArrayList<>();
      for (SourceRecord s : result.getRetained()) if (s.getUrl() != null && good.contains(s.getUrl())) base.add(s);
    }
    Set<SourceKind> allow = selectedExportKinds();
    if (allow.isEmpty()) return Collections.emptyList();
    return SourceKind.filter(base, allow);
  }

  private List<Object> exportPayload() {
    return exportPayload(exportRecords());
  }

  private List<Object> exportPayload(List<SourceRecord> list) {
    if (list == null || list.isEmpty()) return Collections.emptyList();
    List<Object> payload = new ArrayList<>();
    String stamp = new SimpleDateFormat("yyyy-M-d", Locale.CHINA).format(new Date());
    String mark = "✔" + stamp + "检验去重（优质" + list.size() + "）";
    for (SourceRecord s : list) {
      Map<String, Object> m = new LinkedHashMap<>(s.getRaw());
      m.put("bookSourceName", s.getName());
      Object g = m.get("bookSourceGroup");
      String gs = g == null ? "" : String.valueOf(g);
      // replace previous inspection marks to avoid stacking
      gs = gs.replaceAll("(?:,)?✔\\d{4}-\\d{1,2}-\\d{1,2}检验去重（优质\\d+）", "");
      if (gs.startsWith(",")) gs = gs.substring(1);
      m.put("bookSourceGroup", gs.isEmpty() ? mark : gs + "," + mark);
      // attach check groups if any
      for (CheckSourceResult cr : checkResults) {
        if (cr.source.getUrl() != null && cr.source.getUrl().equals(s.getUrl()) && !cr.groups.isEmpty()) {
          String prefix = (cr.kind == null ? SourceKind.NOVEL : cr.kind).label;
          String cg = prefix + ":" + String.join(",", cr.groups);
          Object comment = m.get("bookSourceComment");
          String c = comment == null ? "" : String.valueOf(comment);
          if (!c.contains(cg)) m.put("bookSourceComment", c.isEmpty() ? cg : c + " | " + cg);
        }
      }
      payload.add(m);
    }
    return payload;
  }

  private String exportJson(List<SourceRecord> list) {
    List<Object> payload = exportPayload(list);
    if (payload.isEmpty()) return null;
    // 预分配容量：按平均 2KB/条估算，上限 32MB，避免万级导出时 StringBuilder 反复扩容 OOM
    int cap = (int) Math.min((long) payload.size() * 2048L, 32L * 1024L * 1024L);
    return MiniJson.stringify(payload, cap);
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
        if (destroyed) return;
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
        if (destroyed) return;
        if (j == null) { toast("没有可保存的数据"); return; }
        pendingSave = j;
        String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        createDoc.launch("去重_" + list.size() + "_" + d + ".json");
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
      TabLayout.Tab tab = tabs.getTabAt(0);
      if (tab != null) tabs.selectTab(tab);
      showDedupe();
    });
  }

  private void updateBackgroundTask(String text) {
    backgroundTaskText = text == null ? "" : text;
    if (backgroundTaskChip == null) return;
    backgroundTaskChip.setText(backgroundTaskText);
    backgroundTaskChip.setVisibility(currentTab == 1 && (fetchRunning || checkRunning) ? View.VISIBLE : View.GONE);
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
    yck.addJavascriptInterface(new YckBridge(this::collectYckUrl), "YckDedupe");
    yckClient = new YckWebClient(new YckWebClient.Listener() {
      public void onJsonLink(String u) { showJsonMenu(u); }
      public void onExternal(String u) { toast("已拦截非 YCK 页面"); }
      public void onLoadError(String u) {
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
    final String[] out = {"invalid"};
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    runOnUiThread(() -> {
      boolean added = appendUrlIfAbsent(url);
      out[0] = added ? "added" : "duplicate";
      // 与 2.3.10 一致：仅写入 URL 列表，不强制切 Tab；由注入按钮 flash 反馈
      latch.countDown();
    });
    try {
      if (!latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) return "invalid";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "invalid";
    }
    return out[0];
  }
  private void injectYckCollector() {
    yck.post(() -> yck.evaluateJavascript(YckCollectorScript.source(), null));
    yck.postDelayed(() -> yck.evaluateJavascript(YckCollectorScript.source(), null), 800);
  }

  private boolean appendUrlIfAbsent(String u) {
    String clean = u == null ? "" : u.trim();
    if (clean.isEmpty()) return false;
    CharSequence cur = etUrls.getText();
    String s = cur == null ? "" : cur.toString();
    for (String x : s.split("\\r?\\n")) if (x.trim().equals(clean)) return false;
    etUrls.setText(s.trim().isEmpty() ? clean : s + "\n" + clean);
    return true;
  }

  private void showJsonMenu(String url) {
    new MaterialAlertDialogBuilder(this).setTitle("发现书源链接")
      .setItems(new String[]{"添加到去重工具", "在当前页面打开", "复制链接", "取消"}, (d, w) -> {
        if (w == 0) {
          boolean added = appendUrlIfAbsent(url);
          toast(added ? "已添加到去重工具" : "已在列表中");
          showDedupe();
          tabs.selectTab(tabs.getTabAt(0));
        } else if (w == 1) {
          yckClient.allowNextJson();
          yck.loadUrl(url);
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
    new MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.app_name) + "  v" + BuildConfig.VERSION_NAME)
        .setMessage("轻量原生 Android 阅读书源整理工具：合并、去重、校验、导入。\n\n"
            + "GitHub：https://github.com/MIXUULS/yuedu-source-dedupe\n\n"
            + "本机构建版（debug 签名）。")
        .setPositiveButton("确定", null)
        .setNeutralButton("重置阅读分支", (d, w) -> {
          appPrefs().edit().remove("readerBranch").apply();
          toast("已重置阅读分支选择，下次导入时将重新选择");
        })
        .show();
  }

  /** 按分类导出校验结果（可用/不可用/非HTTP）。 */
  private void exportByCategory(String cat) {
    List<SourceRecord> list = new ArrayList<>();
    for (SourceRecord s : exportRecords()) {
      CheckSourceResult matched = null;
      for (CheckSourceResult r : getCheckResultsForDisplay()) {
        if (r.source.getUrl() != null && r.source.getUrl().equals(s.getUrl())) { matched = r; break; }
      }
      if (matched == null) continue;
      String url = s.getUrl();
      boolean isHttp = url != null && (url.startsWith("http://") || url.startsWith("https://"));
      if ("可用".equals(cat) && matched.isUsable()) list.add(s);
      else if ("不可用".equals(cat) && !matched.isUsable() && isHttp) list.add(s);
      else if ("非HTTP".equals(cat) && !isHttp) list.add(s);
    }
    if (list.isEmpty()) { toast("没有符合条件的书源"); return; }
    toast("正在生成 JSON…");
    new Thread(() -> {
      final String json = exportJson(list);
      runOnUiThread(() -> {
        if (destroyed) return;
        if (json == null) { toast("生成失败"); return; }
        pendingSave = json;
        String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        createDoc.launch("去重_" + cat + "_" + list.size() + "_" + d + ".json");
      });
    }, "export-cat").start();
  }

  /** 导出校验结果为 CSV 文件（可在电脑上打开筛选排序）。 */
  private void exportCsv() {
    List<CheckSourceResult> list = getCheckResultsForDisplay();
    if (list.isEmpty()) { toast("没有可导出的数据"); return; }
    list.sort((a, b) -> Long.compare(b.respondTimeMs, a.respondTimeMs));
    StringBuilder sb = new StringBuilder();
    sb.append("名称,网址,结果,说明,耗时(ms),类型\n");
    for (CheckSourceResult r : list) {
      String name = r.source.getName();
      String url = r.source.getUrl();
      String status = r.status == CheckSourceResult.Status.SUCCESS ? "可用" : (r.status == CheckSourceResult.Status.TIMEOUT ? "超时" : "失败");
      String msg = r.message == null ? "" : r.message;
      String kind = r.kind.label;
      sb.append(csvEscape(name)).append(',');
      sb.append(csvEscape(url)).append(',');
      sb.append(status).append(',');
      sb.append(csvEscape(msg)).append(',');
      sb.append(r.respondTimeMs).append(',');
      sb.append(kind).append('\n');
    }
    pendingSave = sb.toString();
    String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    csvDoc.launch("校验结果_" + list.size() + "_" + d + ".csv");
  }
  private static String csvEscape(String s) {
    if (s == null) return "";
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
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
      String steps = m.mergedSucceededSteps.isEmpty() ? "无成功步骤" : String.join("、", m.mergedSucceededSteps);
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
    if (currentTab == 1 && yck.canGoBack()) yck.goBack();
    else if (currentTab == 1) { tabs.selectTab(tabs.getTabAt(0)); showDedupe(); }
    else new MaterialAlertDialogBuilder(this).setTitle("退出确认").setMessage("确定要退出阅读书源去重吗？")
      .setPositiveButton("退出", (d, w) -> finish()).setNegativeButton("取消", null).show();
  }
}
