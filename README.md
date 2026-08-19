# 阅读书源去重

用于整理、去重和校验阅读书源 JSON 的 Android 应用。

> 本仓库为 **社区维护版（v3.1.0）**，由 MIXUULS 基于
> [Mina-kk/yuedu-source-dedupe](https://github.com/Mina-kk/yuedu-source-dedupe) 二次开发：
> 修复了多项稳定性问题（OOM 闪退、并发、生命周期等），并新增若干功能，详见 [3.1.0 更新](#310-更新本版)。
> **感谢原作者 [Mina-kk](https://github.com/Mina-kk) 的开源贡献。**

## 3.1.0 更新（本版）

### 修复的 Bug

- **大书源包（万级 / 数十 MB）解析、导出、本地导入的内存溢出（OOM）与闪退**：
  开启 `largeHeap`、消除重复的内存拷贝、解析串行化（下载并发、解析同一时刻只有一个），
  解决"导入超 1 万条闪退""导出 100% 闪退""被系统 o-stop 杀进程"等问题。
- **校验"发现页"误判**：有 `ruleExplore` 规则的书源不再因网络探测失败被判"发现失效"。
- **本地导入阻塞主线程**：读文件与 JSON 解析移至后台线程，避免大文件 ANR。
- **多线程 order 竞争**：并发导入时书源顺序号统一加锁分配，去重择优稳定。
- **Activity 生命周期泄漏**：退出/旋转屏幕时自动停止后台任务，回调加销毁防护。
- **网络下载 body 重复解析**：下载后只解析一次。
- **校验超限误判**：响应超过上限直接报失败，不再截断半页误判；下载上限放宽到 64MB。
- **YCK 页面资源过度拦截**：仅拦截非 YCK 域且非静态资源（图片/字体/css/js）的请求。
- **MiniJson 边界**：`\u` 转义越界、超大整数导致整包解析失败等问题。
- **URL 模板误判**：普通 URL 中的 `,{` 不再被误当 POST 选项截断。
- **每源新建线程池**：校验改为共享线程池，万级校验开销大幅下降。
- 移除未使用的依赖库（okhttp / coroutines / lifecycle），APK 更小。

### 新增功能

- **深色模式**：跟随系统夜间模式自动切换。
- **网络自动代理回退**：先直连，失败（网络异常或 DNS 污染返回错误内容）自动走系统代理，
  无需手动判断哪些源需要梯子；支持 Clash 等"系统代理"模式。
- **下载进度实时显示**：并发数、已下载大小、下载速度（MB/s）。
- **状态记忆**：URL 输入、去重模式、开关、并发数自动保存恢复。
- **URL 历史**：最近 10 条地址一键选择，可清空。
- **重复明细**：点击"重复"统计卡片查看每个重复组保留了哪个、合并了哪些。
- **校验结果**：失败原因 Top 统计 + "校验明细"按状态筛选、按耗时排序。
- **关于页**：点击标题栏查看版本号与项目地址。
- **正式签名**：release 使用独立 keystore 构建，可覆盖升级与分发。

### 注意事项

- **签名文件**（`keystore/`）不会提交到仓库：`debug.keystore` 按下方构建说明生成；
  `release.keystore` 请使用你自己的密钥并**务必妥善备份**，丢失后已发布的 release 无法再升级。
- **代理行为**：直连优先，失败自动走系统代理；若使用规则模式代理（如 Clash），建议开启
  "系统代理"开关，国内源直连、被墙源走代理，全自动无需手动切换。
- **大小限制**：单文件导入/下载上限 64MB，单次解析上限约 2500 万字符，超大合集请分批导入。
- **签名区别**：debug 版与 release 版签名不同，两者不能互相覆盖安装；同一签名版本可覆盖升级。
- **校验限制**：使用真实 HTTP 请求与内容探测，未实现与阅读一致的 JS/CSS/XPath 规则执行，
  复杂动态规则可能误判（同上游）。

## 3.0.1-md3 更新（上游）

### 书源校验

- 校验对话框可填写搜索关键词，默认「我的」。
- 超时、并发、关键词和校验勾选会记住；可用「重置默认」恢复。
- 按书源类型判断小说、漫画、视频、音频和文件，并按类型做内容探测。
- 检验对话框可选择要校验的类型。
- 结果区显示去重和校验统计，并用开关选择导入或保存的类型。
- 内容失败原因按类型区分：正文失效、图片失效、播放失效、音频失效、下载失效。

### 导入阅读

- 导入和保存只处理结果区已打开的类型。
- 仍可启用「仅导出可用源」。
- 继续打开阅读的书源批量选择界面，不直接写入阅读书源数据库。

详细说明见 [docs/CHECK_SOURCE.md](docs/CHECK_SOURCE.md)。

## 3.0.0-md3 更新

### 工程与界面

- 重构为标准 Android Gradle 工程。
- 界面更新为 Material 3。
- 包名保持为 `com.mina.yuedu`。
- 去重和校验任务在切换页面后可继续运行，并显示任务进度。

### 去重

- 提供标准、严格、激进三种去重模式。
- 修复 URL 尾斜杠、默认端口、查询参数和常见跟踪参数的处理。
- 支持 `#简体`、`#大改`、`#🎃` 等书源身份标签。
- 标准和严格模式保留身份标签；激进模式按站点合并。
- 网络请求只使用身份标签前的实际地址。
- 切换去重模式或名称清理选项后重新计算结果。

### 文件与网络导入

- 支持选择一个或多个本地 JSON 文件。
- 支持输入网络 JSON 或 YCK 地址。
- 兼容数组、`data`、`list`、`sources` 包装及单个书源对象。
- 支持重定向、gzip 响应和下载大小限制。
- 网络地址直接请求，不使用额外代理。

### YCK

- 支持主站、备用站和发布页入口切换。
- 新增无缓存刷新。
- 支持从页面收集书源地址并添加到去重工具。

### 书源校验

- 新增搜索、发现、详情、目录和正文阶段校验。
- 默认超时为 180 秒。
- 校验并发范围为 **1–100**，默认 8。
- 支持停止校验并取消活动连接。
- 支持按失败原因查看结果。
- 支持仅导出校验可用的书源。

### 导入阅读

- “导入阅读”会打开阅读的书源批量选择界面。
- 由用户勾选并确认需要导入的书源。
- 不直接写入阅读的书源数据库。
- 使用一次性本机地址传递批量 JSON，避免 Intent 数据过大。

## 使用方法

1. 选择本地 JSON 文件，或输入网络 JSON/YCK 地址。
2. 选择去重模式和网络下载并发数。
3. 开始解析并检查重复、有效和错误数量。
4. 如需检查可用性，打开“校验源是否可用”并设置校验阶段、超时、并发数和搜索关键词。
5. 在结果区选择要保留的书源类型。
6. 保存结果，或点击“导入阅读”后在阅读中选择并确认。

## 下载

APK 发布在本仓库的 [Releases](https://github.com/MIXUULS/yuedu-source-dedupe/releases) 页面。

当前版本：**v3.1.0**（versionCode 304）

```text
文件：app-release.apk
类型：正式签名发布版（安装与分发）
```

> 安装第三方 APK 前，请自行核对发布页提供的 SHA-256 摘要。

## 从源码构建

要求：

- JDK 17
- Android SDK Platform 35

> 项目使用 `keystore/debug.keystore`（debug 签名）打包，仓库未包含该文件。
> 首次构建前请生成（密码均为 `android`）：
>
> ```bash
> keytool -genkeypair -v -keystore keystore/debug.keystore -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
> ```

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

生成的调试 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 构建发布版（release）

release 使用独立的 `keystore/release.keystore` 签名（仓库不包含、也不会提交该文件），
请使用你自己的密钥生成（自行妥善保管密码，丢失后已发布的 release 无法再升级）：

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -alias yuedu -keyalg RSA -keysize 2048 -validity 10000 -storepass <你的密码> -keypass <你的密码> -dname "CN=YueduDedupe,O=You,C=CN"
```

并修改 `app/build.gradle.kts` 中 `releaseKey` 的 `storePassword` / `keyPassword` 为你设置的密码，然后：

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

## 测试

当前 JVM 测试覆盖：

- URL 身份标签和去重模式。
- 一次性本机 JSON 服务。
- 校验并发 1–100 的边界归一化。
- 搜索关键词空白回退和自定义保留。
- 书源类型判断和内容探测。

最终构建中 22 项 JVM 测试全部通过。

## 当前限制

当前校验使用真实 HTTP 请求和返回内容探测，但尚未实现与阅读 WebBook 完全一致的 Rhino JavaScript、CSS 和 XPath 规则执行。复杂动态规则可能出现误判。

## 致谢

- 原作者 [Mina-kk](https://github.com/Mina-kk) 及其 [yuedu-source-dedupe](https://github.com/Mina-kk/yuedu-source-dedupe) 项目
- 所有使用、测试并反馈问题的用户

## 许可证

本项目采用 [MIT License](LICENSE)。
