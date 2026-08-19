# 阅读书源去重

用于整理、去重和校验阅读书源 JSON 的 Android 应用。

## 3.1.0 更新

### 稳定性（重要）

- 修复大书源包（万级 / 数十 MB）解析、导出、本地导入的内存溢出（OOM）与闪退：
  开启 `largeHeap`、消除重复的内存拷贝、解析串行化（下载并发、解析同一时刻只有一个）。
- 修复 Activity 生命周期泄漏：退出/旋转屏幕时自动停止后台任务。
- 修复网络下载 body 重复解析、校验超限误判、下载大小上限放宽到 64MB。
- 网络请求自动回退：先直连，失败（网络异常或内容被污染/DNS 污染）自动走系统代理，
  无需手动判断哪些源需要梯子。

### 新功能

- 深色模式：跟随系统夜间模式自动切换。
- 下载进度实时显示：并发数、已下载大小、下载速度（MB/s）。
- 状态记忆：URL 输入、去重模式、开关、并发数自动保存恢复。
- URL 历史：最近 10 条地址一键选择。
- 重复明细：点击"重复"统计卡片查看每个重复组保留了哪个、合并了哪些。
- 校验结果：失败原因 Top 统计 + "校验明细"按状态筛选、按耗时排序。
- 关于页：点击标题栏查看版本号与项目地址。
- 正式签名：release 使用独立 keystore 构建，可覆盖升级与分发。

### 其它

- 移除未使用的依赖库（okhttp / coroutines / lifecycle），APK 更小。
- 版本号：3.1.0（versionCode 304）。

## 3.0.1-md3 更新

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

APK 在 [GitHub Releases](https://github.com/Mina-kk/yuedu-source-dedupe/releases) 发布。

```text
文件：yuedu-3.0.1-md3.apk
大小：6,733,138 字节
SHA-256：302201565cd90920afa3e9eb71139c139b1d00ed05a9c6687b8cee3d5d5841f4
签名证书 SHA-1：4b8fda609b4a959f0b93937b14ef42ead81fae54
```

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

## 许可证

本项目采用 [MIT License](LICENSE)。
