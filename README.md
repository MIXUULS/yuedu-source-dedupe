# 📚 阅读书源去重

<p align="center">
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases"><img src="https://img.shields.io/badge/版本-3.4.0-blue.svg" alt="版本"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/许可证-MIT-green.svg" alt="许可证"></a>
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases"><img src="https://img.shields.io/badge/下载-APK-brightgreen.svg" alt="下载"></a>
</p>

<p align="center">轻量原生 Android 书源整理工具 · 合并 · 去重 · 校验 · 分享 · 导入</p>

> 本仓库为 **社区维护版**，基于 [Mina-kk/yuedu-source-dedupe](https://github.com/Mina-kk/yuedu-source-dedupe) 二次开发。
> 功能持续增强，感谢原作者 ❤️

---

## ✨ 功能

| 类别 | 功能 |
|---|---|
| 🔄 **去重** | 标准 / 严格 / 激进三种模式，支持 `#标签` 区分同站不同源，规则可存为预设 |
| 🔍 **校验** | 搜索 → 发现 → 详情 → 目录 → 正文，失败原因统计，明细筛选排序，历史记录，增量重验失败源 |
| 🌙 **界面** | Material 3、深色模式、下载进度/速度实时显示，工具功能按分组卡片整理 |
| 🔗 **网络** | 自动直连→代理回退（网络异常 + 内容污染），同域串行下载 |
| 📋 **结果** | 分类导出、重复明细、合并同域校验结果、CSV 导出、多选批量操作、质量快源排序 |
| 🧠 **智能** | 快速校验模式、自定义可用状态码、多阅读分支选择、变更差异报告 |
| 💾 **持久化** | 状态记忆、URL 历史、自动恢复上次配置、设置一键备份/还原 |
| 📤 **分享** | 分享文本 / JSON 文件 / 二维码 / 自用分享链接，从剪贴板导入书源，自动分类标签 |

## 🚀 快速开始

1. 选择本地 JSON 文件，或输入网络 JSON / YCK 地址
2. 选择去重模式和并发数
3. 点击「解析网络源」
4. 可选：点击「校验源是否可用」检测书源连通性
5. 保存结果，或「导入阅读」到手机上的阅读 App

## 📦 下载

[📥 前往 Releases 下载 APK](https://github.com/MIXUULS/yuedu-source-dedupe/releases)

当前版本：**v3.4.0**（versionCode 307）· 正式签名发布版

## 📝 更新日志

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