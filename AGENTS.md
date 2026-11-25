# 阅读书源去重 v3.7.2

**仓库：** https://github.com/MIXUULS/yuedu-source-dedupe
**上游：** Mina-kk/yuedu-source-dedupe（remote: upstream）
**目录：** C:\Users\liuss\Desktop\book\yuedu-source-dedupe-main
**环境：** JDK 17 (D:\EXRJ666\jdk)、Android SDK、Gradle 8.7
**签名：** debug(android)、release(yuedu2026, alias yuedu)
**设备：** 64367acb

## 功能清单
- 去重三模式(标准/严格/激进)+规则预设
- 校验五步(搜索/发现/详情/目录/正文)+快速模式+自定义状态码
- 校验历史+增量重验失败源
- 书源健康追踪(评分0-100)+健康看板/报告+同域优化建议+智能导出优质源
- 深色模式+Material 3+代理回退+下载进度
- 状态记忆+URL历史+设置备份还原
- 版本对比(两份文件)+分享(文本/JSON/二维码/链接)+剪贴板导入
- 分类导出(可用/不可用/非HTTP/CSV/合并同域)
- 结果多选批量操作(导出/复制/移除)+变更报告+自动分类标签
- 阅读分支选择+YCK(三站切换/一键收集)+右上角更多菜单
- 过滤区(清理登录源+删除验证码源)
- 合并上游3.0.5: FetchPolicy/GZIP/重试/DNS不重试/BOM清理

## 核心类
- SourceCleaner: cleanLogin+deleteCaptcha 过滤(11项测试)
- SourceHealthTracker: 历次校验记录+评分+建议+JSON持久化
- SourceDiff: 版本对比diff
- TextKit: join/isBlank/abbreviate（替代 API26 的 String.join，minSdk 24 必须用它）
- SessionState: 跨配置变更保留书源/校验结果（深色模式切换不丢数据）
- ResultPruner: 剔除书源时同步 retained/duplicateGroups/checkResults 三份数据
- UrlHistory: 最近 URL（去重/置顶/上限 10）
- CheckResultFilter: 校验结果搜索过滤（null=未启用过滤）
- CheckHistoryStore: 校验历史汇总/截断(20)/JSON 编解码
- ExportPayloadBuilder: 导出时的分组标签与校验备注改写
- CsvBuilder: 校验结果导出 CSV（含 UTF-8 BOM，Excel 中文不乱码）
- SettingsBackup: 设置备份/还原（宽类型解析，不会半途失败留下半套配置）
- ExportRecordSelector: 决定导出范围（仅可用过滤 → 类型过滤）
- ExportNaming: 文件名生成 + 非法字符清洗（含 null/纯分隔符兜底）
- CheckTargetPreparer: 校验前清理编排（验证码源 → 登录源 → 规则完整度）
- UrlInputParser: URL 输入切分/合并/序列化/剪贴板提取
- OrderAssigner: 全局顺序号分配（自带锁，消除"必须在 buckets 锁内调用"的隐式约定）
- RecheckPlanner: 重验范围规划（单条 / 批量 / URL 为 null 与已移除的兜底）
- UrlPreflight: 解析前预检（去重 / 非 http 行 / 摘要文案）

## 测试基建
- Robolectric 4.13（仅 testImplementation，不进 APK）
- MainActivityBehaviorTest：Activity 生命周期 / 配置变更 / 代际守卫 / 剪贴板分支
  * 配置变更必须用 `ActivityController.recreate()`，手动 finish+新建**不会**传递
    onRetainCustomNonConfigurationInstance
  * 项目用 AppCompat AlertDialog，ShadowAlertDialog 抓不到；用 ShadowDialog.getShownDialogs()

## 构建
- Debug: `./gradlew assembleDebug`
- Release: `./gradlew assembleRelease`
- 测试: `./gradlew testDebugUnitTest`(280 项，34 个套件)
- Lint: `./gradlew lintDebug`（当前 Error 0）
- 装机: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

## 开发注意事项（踩过的坑）
- **不要用 `String.join` / `StringJoiner`**：API 26 才有，minSdk 24 上会 `NoSuchMethodError` 闪退，
  且属于 Error，`catch (Exception)` 拦不住。统一用 `TextKit.join`。
- **不要用 `URI.getHost()` 判定主机名是否为空**：中文域名与带下划线的主机名会返回 null，
  导致合法书源被整条丢弃。统一走 `UrlNormalizer.hostOf()`（URI → IDN → URL 三层兜底）。
- **不要往 Bundle 里放书源**：数万条会超过 Binder 1MB 上限，直接 `TransactionTooLargeException`。
  需要跨配置变更保留就用 `SessionState` + `onRetainCustomNonConfigurationInstance`。
- **任何异步回调都要带代际守卫**：`fetchGen` / `recomputeGen` / `checkGen` 三套，
  清空、重新解析、重新校验时都要自增作废在途回调，否则旧结果会覆盖新状态。
- **URL 输入框不要在循环里逐条 `setText`**：O(n²)，批量场景会卡死，先合并再一次写入。
- **主线程不要做大 JSON 序列化/解析**：健康数据、导出 payload 都必须在后台线程。
- **单元测试固定 UTF-8**：`testOptions { unitTests.all { systemProperty("file.encoding","UTF-8") } }` 已配好，
  涉及中文与 URL 编码的用例在默认 GBK 的机器上会误报，改动时不要删掉这段配置。

**状态：** v3.7.2（versionCode 312）准备发布。
v3.7.1 已于 2026-11-24 发布（Release 页面含 APK，tag 已推送）。
v3.7.2 相比 v3.7.1 新增：
- 单条书源快速重验（校验明细每行带「重验」按钮）
- 解析前 URL 预检（重复地址自动去重 + 提示非 http 行）
- 修复健康记录无限增长（MAX_RECORDS=20000 + 90 天过期淘汰）

测试 89 → 280 项（34 套件，0 失败），Lint Error 0，debug+release 通过。
真机（PJD110/Android 17）验证：2257 条书源去重结果一致、
深色模式重建后数据保留、清空后旧结果不复活、无崩溃。
MainActivity 2958 → 2809 行，抽出 19 个可测类。
