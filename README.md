# 📚 阅读书源去重

<p align="center">
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases"><img src="https://img.shields.io/badge/版本-3.7.2-blue.svg" alt="版本"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/许可证-MIT-green.svg" alt="许可证"></a>
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases"><img src="https://img.shields.io/badge/下载-APK-brightgreen.svg" alt="下载"></a>
  <a><img src="https://img.shields.io/badge/Android-7.0%2B-34A853.svg" alt="Android"></a>
  <a><img src="https://img.shields.io/badge/APK_Release-2MB-orange.svg" alt="APK大小"></a>
</p>

<p align="center">轻量原生 Android 书源整理工具 · 合并 · 去重 · 校验 · 健康追踪 · 分享 · 导入</p>

> 本仓库为 **社区维护版**，基于 [Mina-kk/yuedu-source-dedupe](https://github.com/Mina-kk/yuedu-source-dedupe) 二次开发。
> 功能持续增强，感谢原作者 ❤️

---

## ✨ 功能

| 类别 | 功能 |
|---|---|
| 🔄 **去重** | 标准 / 严格 / 激进 / 名称四种模式，支持 `#标签` 区分同站不同源，规则可存为预设 |
| 🔍 **校验** | 搜索 → 发现 → 详情 → 目录 → 正文，失败原因统计，明细筛选排序，历史记录，增量重验失败源，**单条快速重验** |
| 🌙 **界面** | Material 3、深色模式、下载进度/速度实时显示，工具功能收纳于右上角菜单，主界面精简 |
| 🔗 **网络** | 自动直连→代理回退（网络异常 + 内容污染），同域串行下载，GZIP/重试退避，**解析前 URL 预检去重** |
| 📋 **结果** | 分类导出、重复明细、合并同域校验结果、CSV 导出、多选批量、全选/反选 |
| 🧠 **智能** | 快速校验模式、自定义可用状态码、多阅读分支选择、变更差异报告、名称相似度去重 |
| 💾 **持久化** | 状态记忆、URL 历史、自动恢复上次配置、设置一键备份/还原 |
| 📤 **分享** | 分享文本 / JSON 文件 / 二维码 / 自用分享链接，从剪贴板导入书源，自动分类标签 |
| ❤️ **健康追踪** | 书源质量评分（0-100）、历史校验记录、健康看板、质量报告、同域优化建议 |

## 🚀 快速开始

1. 选择本地 JSON 文件，或输入网络 JSON / YCK 地址（多个地址每行一个，重复会自动去重）
2. 选择去重模式和并发数
3. 点击「解析网络源」
4. 可选：点击「校验源是否可用」检测书源连通性
   - 校验完成后点「校验明细」，每行右侧有「重验」按钮，可单独重验某一条
5. 保存结果，或「导入阅读」到手机上的阅读 App

## 📦 下载

[📥 前往 Releases 下载 APK](https://github.com/MIXUULS/yuedu-source-dedupe/releases)

| 版本 | APK 大小 | 说明 |
|------|----------|------|
| 本社区版 v3.7.2 | **~2 MB** | R8 压缩，正式签名 |
| 上游 v3.0.5 | ~6.8 MB | 未压缩 |

当前版本：**v3.7.2**（versionCode 312）· 正式签名发布版

## 📝 更新日志

### v3.7.2（2026-11-25 单条重验 + 解析前预检）

**新功能**

- **单条书源快速重验**：校验明细列表每行右侧新增「重验」按钮。
  之前只能全量重跑或「重验全部失败源」，想单独确认某条源是否恢复必须跑一遍全部，
  万级书源要等几分钟还浪费校验配额
- **解析前 URL 预检**：点「解析网络源」前先告知共几行、可解析几个、几个重复、几行非 http。
  重复地址自动去重，避免同一地址被重复请求

**修复**

- 修复书源健康记录无限增长：`records` 只增不减，万级书源长期使用约 10MB 常驻内存
  且会序列化成十几 MB 的 JSON 存进 SharedPreferences。
  新增 `MAX_RECORDS=20000` 上限与 90 天过期淘汰，优先清理最久未校验的记录

**测试**

- 280 项（34 个套件），比 v3.7.1 新增 30 项

### v3.7.1（2026-11-24 全量审查修复）

> 对全部源码做了一次逐行审查，修复 30 类问题，测试从 89 项增加到 250 项。
> 其中包含两个**必然触发**的严重缺陷，强烈建议升级。

**崩溃修复**

- 修复 `String.join` 在 Android 7.0/7.1 上 `NoSuchMethodError` 闪退 —— 该方法是 API 26 才有的，
  而 minSdk 是 24（新增 `TextKit.join` 替代）
- 修复 `tabs.getTabAt(0)` 未判空导致的 NPE
- 修复 YCK 注入按钮在 UI 线程上自锁（`latch.await` 永久阻塞）

**中文域名书源被误删**

- 修复用 `URI.getHost()` 判空导致 **中文域名 / 带下划线主机名的书源被整条判为无效丢弃**
  （新增 `IDN.toASCII` + `URL.getHost` 多层兜底）

**数据丢失修复**

- 修复切换深色模式后已导入的全部书源、去重与校验结果消失
  （`DayNight` 主题会重建 Activity，改用 `onRetainCustomNonConfigurationInstance` 跨重建保留，
  不走 Bundle 以避免数万条书源触发 `TransactionTooLargeException`）
- 修复 NAME 去重模式下部分书源凭空消失（归一化 key 冲突时被覆盖）
- 修复删除书源后"重复数"仍统计已删书源
- 修复清空后旧的去重结果被在途任务写回（代际守卫缺失）
- 修复新一轮解析/清空后，上一轮校验结果覆盖当前结果、校验历史与健康数据（新增 `checkGen` 守卫）
- 修复校验进行中切换去重模式会让并发守卫失效、可启动第二个校验引擎

**校验准确性**

- 新增响应编码识别：按 BOM → `Content-Type` charset → meta 嗅探 自动识别 GBK/GB2312，
  解决国内老站中文乱码被误判成"搜索失效/正文失效"
- 修复版本对比把键顺序不同的等价书源误报为"已修改"
- 修复书源 JSON 尾部有额外文本时解析失败

**性能**

- 修复分类导出、导出备注的 O(n²) 查找（万级书源从卡死变为秒出）
- 修复健康数据在主线程读写十几 MB JSON 导致的 ANR
- 修复连续切换去重模式时并发跑多份全量去重
- 修复 YCK 批量收集逐条重写输入框的 O(n²)
- 修复版本对比读取文件无大小上限
- 修复下载任务切换时旧线程抢占新队列的数据错乱

### v3.7.0

- 新增名称相似度去重（NAME 模式），按 Levenshtein 距离 + 关键词归一化合并同名镜像站
- 新增全选/反选按钮，校验明细批量操作更便捷
- 修复 runCheck 缺失 cleanLogin 过滤，校验时同时应用两种过滤
- 修复 SourceHealthTracker 评分边界漏洞（avg=3000~10000 区间空档）
- 修复校验超时 Slider 最小值 0→1 秒，避免静默回退
- 优化排版布局：统一文字样式、改善长文本可读性
- 启用 R8 代码压缩，release APK 从 5MB 降至 2MB
- 导出分组标签改为按类型分别标记，如 ✔2026-8-23小说（120）
- 修复 17 项稳定性问题：大书源导入、URL 去重、校验过滤、并发校验、网络重定向、JSON 深度和生命周期等
- 修复“全部放弃”解析后已加载结果残留的问题
- 优化校验结果过滤、导出分组标签和校验历史菜单
- 改进 legado 搜索 URL、POST 参数和嵌套请求头解析
- 重做首页配色，采用蓝绿色明暗主题
- 去重统计改为 2×2 布局，运行状态和停止操作更紧凑
- 校验设置弹窗增加检测步骤、过滤条件、请求参数和书源类型分区
- 修复首页顶部内容被 Tab 栏遮挡的问题
- 保持轻量体积，不新增图片资源和第三方依赖

### v3.6.0

- 新增名称相似度去重（NAME 模式），按 Levenshtein 距离 + 关键词归一化合并同名镜像站
- 新增全选/反选按钮，校验明细批量操作更便捷
- 修复 runCheck 缺失 cleanLogin 过滤，校验时同时应用两种过滤
- 修复 SourceHealthTracker 评分边界漏洞（avg=3000~10000 区间空档）
- 修复校验超时 Slider 最小值 0→1 秒，避免静默回退
- 优化排版布局，统一文字样式并改善长文本可读性
- 启用 R8 代码压缩，release APK 从 5MB 降至 2MB
- 导出分组标签改为按类型分别标记，如 ✔2026-8-23小说（120）

### v3.5.0

- 书源健康追踪系统：每次校验自动记录每个源的历史，质量评分（0-100）
- 健康看板：按评分降序排列，显示建议保留/观察/删除
- 健康报告：统计总数/域名分布/建议删除列表，可复制可导出
- 同域优化建议：按域名分组，建议每个域名保留最快 2 个
- 智能导出：导出优质源（评分≥70），一键导出健康书源
- 修复「健康数据记录错源」bug（deleteCaptcha 过滤后用了错误列表）
- 修复「导出优质源」空指针隐患
- 合并上游 3.0.5-md3：FetchPolicy/重试退避/GZIP/DNS 不重试/BOM 清理
- 新增「清理需要登录的源」+「删除弹窗验证码的源」过滤选项
- UI 整理：分享/导入合并为单按钮，导入/保存并排一行，主界面精简

### v3.4.0

- 书源分享：复制文本 / 保存 JSON / 二维码 / 自用分享链接
- 从剪贴板导入书源文本
- 设置一键备份 / 还原
- 版本对比（两份书源文件）：新增 / 修改 / 移除，可并入新增
- 校验历史记录 + 增量重验失败源 + 快源排序
- 去重规则预设（模式 + 清理 + 并发）
- 变更差异报告（可复制 / 导出 JSON）
- 校验结果多选批量：导出选中 / 复制选中 / 移除选中
- 导出自动附加分类标签
- 工具功能分组卡片整理界面

### v3.3.0

- 校验结果搜索（实时过滤名称/URL）
- 快速校验模式（只跑搜索+详情两步，速度翻倍）
- 导出 CSV（可在电脑上打开筛选）
- 阅读分支选择（带图标显示包名，每次自由选择）

### v3.2.0

- 同域串行下载、合并同域校验结果
- 分类导出（可用/不可用/非HTTP）
- 自定义可用状态码、快速校验模式
- 阅读分支选择（带图标显示）
- 校验内容级代理回退（全自动）
- 校验结果搜索、导出 CSV
- 修复中文编码乱码

### v3.1.0

- 修复大书源包 OOM 闪退、ANR、生命周期泄漏
- 深色模式、自动代理回退、下载进度
- 状态记忆、URL 历史、重复明细
- 校验筛选排序、关于页

<details>
<summary>📜 上游版本历史（3.0.1-md3 / 3.0.0-md3）</summary>

**3.0.1-md3**
- 多轮校验探测、按同域同类型保留最快可用源
- 校验并发 1–500、同域串行下载+短间隔重试
- 阅读分支选择、检验耗时独立显示

**3.0.0-md3**
- 重构为 Material 3 界面
- 标准/严格/激进三种去重模式
- 支持 `#标签` 身份标签
- YCK 页面支持、校验五阶段

</details>

## 🔧 从源码构建

**要求：** JDK 17 + Android SDK Platform 35

```bash
# 1. 生成 debug 签名（密码 android）
keytool -genkeypair -v -keystore keystore/debug.keystore \
  -alias androiddebugkey -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"

# 2. 构建调试版
./gradlew :app:testDebugUnitTest :app:assembleDebug

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 发布版构建见 [构建说明](#构建发布版release)

<details>
<summary>📦 构建发布版（release）</summary>

```bash
# 生成正式签名
keytool -genkeypair -v -keystore keystore/release.keystore \
  -alias yuedu -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <你的密码> -keypass <你的密码> \
  -dname "CN=YueduDedupe,O=You,C=CN"

# 在 local.properties 中配置密码（该文件不提交）
#   releaseStorePassword=你的密码
#   releaseKeyPassword=你的密码
#   releaseKeyAlias=yuedu

# 构建发布版
./gradlew :app:assembleRelease

# 产物：app/build/outputs/apk/release/app-release.apk
```

</details>

## ⚠️ 注意

- 签名文件（`keystore/`）不会提交到仓库，**请自行备份 release.keystore**
- debug 与 release 签名不同，不能互相覆盖安装
- 网络请求：直连优先 → 失败自动走系统代理，无需手动切换
- 单文件导入上限 64MB，超大合集请分批

## 🙏 致谢

- 原作者 [Mina-kk](https://github.com/Mina-kk) 及 [yuedu-source-dedupe](https://github.com/Mina-kk/yuedu-source-dedupe)
- 所有使用与反馈的用户

## 📄 许可证

[MIT License](LICENSE)
