# 📚 阅读书源去重

<p align="center">
  <b>轻量 Android 书源整理工具</b><br>
  <sub>去重 · 校验 · 清理死站 · 分享给「阅读」App</sub>
</p>

<p align="center">
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases/latest"><img src="https://img.shields.io/github/v/release/MIXUULS/yuedu-source-dedupe?style=flat-square&color=blue" alt="版本"></a>
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases"><img src="https://img.shields.io/badge/Android-7.0%2B-34A853?style=flat-square" alt="Android"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License"></a>
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases"><img src="https://img.shields.io/badge/APK-2MB-brightgreen?style=flat-square" alt="APK"></a>
</p>

<p align="center">
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases/latest"><b>⬇️ 下载 APK</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/MIXUULS/yuedu-source-dedupe/releases"><b>📋 更新日志</b></a>
  &nbsp;·&nbsp;
  <a href="#-快速开始"><b>🚀 快速开始</b></a>
</p>

---

## 这是什么

给「阅读」（legado）用户用的书源整理工具。导入几千条书源后，它会帮你：

- **去掉重复的** —— 按 URL 或名称找出冗余
- **找出不能用的** —— 但只删真正死掉的站，不误伤反爬的
- **导出干净的** —— 直接导回阅读 App

> 基于 [Mina-kk/yuedu-source-dedupe](https://github.com/Mina-kk/yuedu-source-dedupe) 二次开发，感谢原作者 ❤️

---

## 为什么需要它

书源合集动辄几千条，但里面通常有大量问题：

| 问题 | 有多普遍 |
|---|---|
| 同一个站有多个镜像域名 | 2257 条里发现 34 组，可合并 66 条 |
| 站点已经死了 | 1443 个域名里 110 个真死 |
| 只有 URL 没有规则的“空壳” | 少数，但完全不能用 |
| 同一个源被不同人重复收录 | 常见 |

手动清理几乎不可能。这工具就是干这个的。

---

## 核心能力

### 🔍 不会把「反爬」当成「死站」

这是本工具和其他去重脚本最大的区别。

很多工具看到 4xx/5xx 就判“站点失效”，但实测 **1443 个域名里有 173 个返回 4xx/5xx，
站点其实完全活着**——只是拒绝了你这个请求（403 反爬最常见）。

```
正常可用            921 个（63.8%）
有响应但 4xx/5xx    173 个（12.0%）← 站点在线，建议保留
连不上               73 个（5.1%）
DNS 解析失败         37 个（2.6%）← 真正该清理的
```

只有 **DNS 失败 / 连接被拒 / 无路由** 才算死站。

### 📦 认识老格式书源

公开书源里 **73.5% 是 legado 2.x 老格式**。只认新格式的工具会在校验时
报「规则为空」，把这些源当废品扔掉。本工具做了完整的字段映射。

### 🔀 重定向同站检测

很多书源的域名重定向后其实是同一个站：

```
bbqsk.info  ← bbqsk.net / dingdiansk.com / ddsk.la / ddsk.org / ddsge.com
txtshuku.info  ← liudatxt.net / txtshuku.org / liudatxt.la / liudatxt.com
```

普通去重完全看不出这类冗余。检测后支持一键合并。

### 💾 关掉应用不丢数据

导入几千条、跑几十分钟校验，结果自动保存。下次打开直接继续。

---

## 全部功能

<details>
<summary><b>展开查看</b></summary>

**去重**
- 标准 / 严格 / 激进 / 名称 四种模式
- `#标签` 区分同站不同源
- 规则可存为预设

**校验**
- 五步检测：搜索 → 发现 → 详情 → 目录 → 正文
- 快速模式（只跑搜索+详情）
- 自定义可用状态码
- 失败原因统计、明细筛选排序
- 单条快速重验、增量重验失败源
- **区分死站与反爬**

**清理**
- 空壳书源识别
- 站点已死的源一键移除
- 重定向同站一键合并

**结果处理**
- 分类导出（可用 / 不可用 / 死站 / 非HTTP / CSV）
- 重复明细、合并同域
- 多选批量、全选反选
- 变更差异报告

**分享**
- 文本 / JSON 文件 / 二维码 / 自用链接
- 剪贴板导入
- `legado://` 链接直接打开

**其他**
- 书源健康评分（0-100）+ 健康看板
- 同域优化建议
- 深色模式、Material 3
- 自动代理回退
- 设置备份还原
- 状态记忆、URL 历史

</details>

---

## 快速开始

1. **导入书源** —— 选择本地 JSON，或粘贴网络地址（每行一个）
2. **去重** —— 选模式，点「解析网络源」
3. **校验**（可选）—— 点「校验源是否可用」，等它跑完
4. **清理**（可选）—— 从导出菜单里移除死站、合并重定向同站
5. **导出** —— 保存 JSON，或直接「导入阅读」

> 也可以在浏览器里点书源链接，选择本应用直接打开。

---

## 下载

**[⬇️ 前往 Releases 下载最新 APK](https://github.com/MIXUULS/yuedu-source-dedupe/releases/latest)**

| | |
|---|---|
| 当前版本 | **v3.7.4** (versionCode 314) |
| 安装包 | ~2 MB（R8 压缩 + 正式签名） |
| 系统要求 | Android 7.0+ (minSdk 24) |
| 对比上游 | 上游 v3.0.5 约 6.8 MB |

---

## 从源码构建

<details>
<summary><b>展开查看</b></summary>

需要 JDK 17、Android SDK。

```bash
./gradlew assembleDebug      # 产物：app/build/outputs/apk/debug/
./gradlew assembleRelease    # 产物：app/build/outputs/apk/release/（需签名）
```

**签名配置**：在 `local.properties` 里填（该文件不提交）：

```properties
releaseStorePassword=你的密码
releaseKeyPassword=你的密码
releaseKeyAlias=yuedu
```

**测试**：

```bash
./gradlew testDebugUnitTest    # 499 项单元测试
./gradlew lintDebug            # Lint 检查
```
</details>

---

## 注意事项

- **校验结果受网络环境影响很大**。开了代理可能比不开低 30-40%，这不是书源变差。
  建议对比着跑一次，工具会提示你。
- 校验会发真实网络请求。几千条源大约需要十几到几十分钟。
- 移除操作只影响当前列表，不会改动你磁盘上的原文件。

---

## 文档

| 文档 | 内容 |
|---|---|
| [更新日志](CHANGELOG.md) | 各版本详细改动 |
| [校验说明](docs/CHECK_SOURCE.md) | 五步校验的细节 |

---

## 致谢

- [Mina-kk/yuedu-source-dedupe](https://github.com/Mina-kk/yuedu-source-dedupe) —— 原项目
- [gedoor/legado](https://github.com/gedoor/legado) —— 「阅读」App
- 所有在 Issue 里提反馈的用户

---

## 许可证

[MIT](LICENSE)
