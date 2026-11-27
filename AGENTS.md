# 阅读书源去重 v3.7.4

**仓库：** https://github.com/MIXUULS/yuedu-source-dedupe
**上游：** Mina-kk/yuedu-source-dedupe（remote: upstream）
**目录：** C:\Users\liuss\Desktop\book\yuedu-source-dedupe-main
**环境：** JDK 17 (D:\EXRJ666\jdk)、Android SDK、Gradle 8.7
**签名：** debug(android)、release(yuedu2026, alias yuedu)
**设备：** 64367acb（OPPO PJD110 / Android 17）

## 功能清单
- 去重四模式(标准/严格/激进/名称)+规则预设
- 校验五步(搜索/发现/详情/目录/正文)+快速模式+自定义状态码
- **站点存活判定**：区分「站点已死」与「403反爬/路径变化」
- **重定向同站检测**：发现镜像域名，可一键合并
- **空壳书源判定**：搜索与发现链路都无效的判为无效
- **会话持久化**：重启后书源与校验结果还在
- **legado:// 链接接收**：浏览器点书源链接可直接打开
- 校验历史+增量重验失败源
- 书源健康追踪(评分0-100)+健康看板/报告+同域优化建议
- 深色模式+Material 3+代理回退+下载进度
- 状态记忆+URL历史+设置备份还原
- 版本对比+分享(文本/JSON/二维码/链接)+剪贴板导入
- 分类导出(可用/不可用/站点已死/非HTTP/CSV/合并同域)
- 结果多选批量操作+变更报告+自动分类标签
- 阅读分支选择+YCK(三站切换/一键收集)+右上角更多菜单

## 核心类

### 数据与去重
- `SourceBuckets`: 本地+网络两批书源容器；**removeByUrls 必须用**（否则删了会复活）
- `DedupeEngine`: 去重主逻辑，`run(sources, mode, clean, markEmptyShell)`
- `V2FieldAdapter`: legado 2.x → 3.x 字段映射（公开源 73.5% 是 v2）
- `SourceValidity`: 空壳判定（搜索链路 + 发现链路都无效才算）
- `UrlRecovery`: bookSourceUrl 为空时从 searchUrl/规则 baseUrl 恢复
- `ResultPruner`: 剔除书源时同步 retained/duplicateGroups/checkResults 三份数据
- `OrderAssigner`: 全局顺序号分配（自带锁）

### 校验与可用性
- `CheckSourceEngine`: 五步校验引擎
- `SiteLiveness`: **站点存活判定**——只认 DNS失败/连接被拒/无路由为死站
- `UsabilityBuckets`: 校验结果分三档（USABLE / SITE_ALIVE / SITE_DEAD）
- `EnvironmentSuspicion`: 识别「失败是代理/VPN 造成的」
- `CheckStats`: 校验统计 + 失败原因归一化
- `HttpProbe`: 单次 HTTP 探测（含代理回退）
- `AnalyzeUrlLite`: 搜索地址模板展开

### 重定向
- `RedirectCluster`: 把探测结果聚成「同落地站」的组；`pickBestInGroup` 选保留项
- `RedirectProbe`: 32 并发探测最终落点（HEAD 优先，不读 body）

### 持久化
- `SessionStore`: GZIP 会话文件（header + 每行一条 JSON）

### 其它
- `SourceCleaner`: cleanLogin+deleteCaptcha 过滤（默认关闭）
- `SourceHealthTracker`: 历次校验记录+评分+JSON持久化（MAX_RECORDS=20000）
- `TextKit`: join/isBlank/abbreviate（**替代 API26 的 String.join**）
- `SessionState`: 跨配置变更保留数据（深色模式切换不丢）
- `MainState`: 并发状态与任务代际（invalidateAll 是三条链路一起作废的唯一实现点）
- `CheckResultFilter` / `CheckHistoryStore` / `ExportPayloadBuilder` / `CsvBuilder`
- `SettingsBackup` / `ExportRecordSelector` / `ExportNaming` / `CheckTargetPreparer`
- `UrlInputParser` / `UrlPreflight` / `RecheckPlanner` / `FetchResponseClassifier`

## 测试基建
- Robolectric 4.13（仅 testImplementation，不进 APK）
- `MainActivityBehaviorTest`：生命周期/配置变更/代际守卫
  * 配置变更必须用 `ActivityController.recreate()`
  * 项目用 AppCompat AlertDialog，`ShadowAlertDialog` 抓不到；用 `ShadowDialog.getShownDialogs()`
- **测试数据要符合真实形态**：`SourceRecord` 要带 `searchUrl` + `ruleSearch`，
  否则会被空壳判定当成无效源（这个坑踩过，7 个测试类同时回归）

## 构建
- Debug: `./gradlew assembleDebug`
- Release: `./gradlew assembleRelease`（**必跑**，见踩坑）
- 测试: `./gradlew testDebugUnitTest`（499 项 / 49 套件）
- Lint: `./gradlew lintDebug`（当前 Error 0）
- 装机: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

## 开发注意事项（踩过的坑）

### 语言/API 限制
- **不要用 `String.join` / `StringJoiner`**：API 26 才有，minSdk 24 上
  `NoSuchMethodError` 闪退，且属于 Error，`catch (Exception)` 拦不住。用 `TextKit.join`。
- **不要用 `URI.getHost()` 判定主机名**：中文域名与下划线主机会返回 null，
  合法书源被整条丢弃。走 `UrlNormalizer.hostOf()`（URI → IDN → URL 三层兜底）。

### 数据正确性
- **移除书源必须同步 `buckets`**：只改 result/checkResults 的话，
  `recompute` 从 `buckets.all()` 重算会把删掉的源全部复活。
- **不要往 Bundle 里放书源**：数万条超 Binder 1MB 上限，
  `TransactionTooLargeException`。用 `SessionState` + `onRetainCustomNonConfigurationInstance`。
- **任何异步回调都要带代际守卫**：`fetchGen`/`recomputeGen`/`checkGen` 三套，
  清空/重新解析/重新校验时自增作废在途回调。

### UI 与生命周期
- **一个 View 只能有一个父容器**：`ll.addView(sc)` 之后再 `setView(sc)` 会抛
  `IllegalStateException: The specified child already has a parent`。
  要传外层容器。这个编译期发现不了，单测也覆盖不到，**只有真机点一遍才暴露**。
- **异步回调里的 `this` 指向 lambda**：`runOnUiThread(() -> new Dialog(this))` 编译报错，
  必须写 `MainActivity.this`。
- **Dialog 前必须检查 `isDestroyed() || isFinishing()`**：否则 Activity 销毁后 show 会崩。
- **`onDestroy` 里要取消所有后台任务**：FetchManager / CheckEngine / RedirectProbe /
  recomputeExecutor / sessionExecutor / WebView，漏一个就泄漏 Activity。

### 性能
- **URL 输入框不要在循环里逐条 `setText`**：O(n²)，批量场景卡死，先合并再写。
- **主线程不要做大 JSON 序列化/解析**：健康数据、导出 payload、会话读写都要后台线程。
- **SharedPreferences 存 URL 输入要限长**：`savePrefs` 存整个 etUrls，无上限会
  让 prefs 膨胀、退出卡顿。已加 8KB 上限。
- **会话恢复不要用异步 recompute**：recompute 带代际守卫，冷启动时会被后续调用作废，
  表现为「数据读到了但界面不显示」。恢复路径直接主线程同步算。

### 构建
- **删资源必须跑 `assembleRelease`**：删了 `values/colors.xml` 里的颜色但要
  `values-night/colors.xml` 里还有同名定义，`lintVitalRelease` 会失败。
  debug 构建不触发 lintVitalRelease，只测 debug 发现不了。

### 测试
- **单元测试固定 UTF-8**：`systemOptions { unitTests.all { systemProperty("file.encoding","UTF-8") } }`
  已配好，涉及中文与 URL 编码的用例在默认 GBK 的机器上会误报，不要删这段配置。

### 真机验证
- **adb 无法向 URL 输入框输入文本**：`input text` 和逐字符 keyevent 都失败，
  唯一成功的一次被中文输入法转成了全角（`https：、、`）。
  验证导入请用 `legado://` intent 或手动操作。
- **文件选择器无法用 adb 可靠操作**：需要人工点选。

## 关键数据（做判定时的依据）

```
用户真实书源（终极.json）：2257 条，100% v3 格式
  loginUrl 非空 335 条（14.8%），其中大部分是空 JS 函数或配置钩子
  去重后：小说 2085 / 漫画 102 / 视频 7 / 音频 32 / 文件 31

公开书源聚合（6 个镜像去重）：1295-1399 条
  73.5% 是 v2 老格式

域名探测（1443 个唯一域名）：
  正常可用        921（63.8%）
  4xx/5xx 但站活着 173（12.0%）← 403 占 106
  连不上           73（5.1%）
  DNS 失败         37（2.6%）

重定向（2257 条 / 1441 主机）：
  133 个主机跨域重定向，34-36 组多主机指向同一站，65-66 条冗余

校验可用数受网络环境影响极大：
  无代理 857 / 代理1 626 / 代理2 538
```

**文档分工：**
- `README.md` —— 用户向首页，保持精简（176 行）。**不要把更新日志写进 README**，
  历史版本改动放 `CHANGELOG.md`，当前版本说明放 GitHub Release。
- `CHANGELOG.md` —— 各版本详细改动。
- `docs/AI_HANDOVER.md` —— 完整交接文档（背景/坑/数据）。
- `项目工作日志.md` —— 工作记录。

**状态：** v3.7.4（versionCode 314）已发布。
v3.7.0 / v3.7.1 / v3.7.2 / v3.7.3 均已发布为正式 Release（含 APK）。

v3.7.4 相比 v3.7.3 的变化见 `CHANGELOG.md`。
核心是：V2 兼容、站点存活判定、重定向检测、空壳判定、会话持久化、Intent 接收。

测试 280 → 499 项（49 个套件，0 失败），Lint Error 0，debug + release 通过。
MainActivity 2809 → 约 3100 行，共抽出 30 个可测类。
