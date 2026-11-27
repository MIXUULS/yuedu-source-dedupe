# 交接文档：阅读书源去重项目（给其他 AI）

> 生成时间：2026-11-27
> 项目：https://github.com/MIXUULS/yuedu-source-dedupe
> 当前版本：v3.7.4（versionCode 314）
> 本文档说明：一个 AI 助手在接手此项目时的完整背景、已做的工作、踩过的坑、以及做判断时依据的真实数据。

---

## 一、这个项目是什么

一个 Android 工具应用（包名 `com.mina.yuedu`），给「阅读」App（legado）的用户用：

1. **去重书源**：导入几千条书源 JSON，按 URL / 名称找出重复的，删掉冗余
2. **校验书源**：逐条发请求测试书源是否还能用（五步：搜索/发现/详情/目录/正文）
3. **导出**：把去重后、校验后的书源导出回 JSON 给阅读 App 导入

技术栈：Java（非 Kotlin）、minSdk 24、targetSdk 35、Gradle 8.7、JDK 17、Robolectric 做单测。

---

## 二、最重要的背景：用户的真实诉求

用户（GitHub @xixiaoguo 之外的仓库所有者）的核心目标是两条：

> **1. 我们要去重**
> **2. 去除无论如何都不能用的网站（不管是使用代理还是什么办法都不能用的）**

这第二点极其关键，也是本版最大的改动来源。**不能用的站 ≠ 校验失败的站**。

---

## 三、这个版本（v3.7.4）做了什么

### 3.1 兼容 legado 2.x 老格式书源

**问题**：公开书源里 73.5% 是 v2 老格式（`ruleSearchUrl` / `ruleFindUrl` / `ruleBookName`），
而应用只认 v3 字段（`searchUrl` / `ruleSearch` / `ruleBookInfo`），
导致这些源校验时误报「搜索链接规则为空」，被当废品。

**实测影响**（1399 条公开书源）：

```
适配前报"规则缺失"  1053 条（75.3%）
适配后报"规则缺失"    78 条（5.6%）
修复 975 条
```

**实现**：`core/V2FieldAdapter.java`，把 v2 字段映射成 v3。
完整字段对应表在类注释里。

**关键设计**：只在 v3 字段缺失时补，已有 v3 值一律不动；不修改入参 map。

### 3.2 区分「站点已死」与「站点在线但规则失效」

**这是最重要的一条改动。**

**问题**：原实现把所有非 2xx/3xx 响应都判「网站失效」：

```java
if (!settings.isHttpOk(br.code)) bad.add("网站失效");
// isHttpOk 默认 200-399
```

于是 403 反爬、404 路径改版、429 限流、5xx 网关抖动全被当成死站。

**实测数据**（1443 个唯一域名，来自用户 2257 条书源）：

```
正常可用            921 个（63.8%）
有响应但 4xx/5xx    173 个（12.0%）← 403 占 106，站点完全活着
连不上               73 个（5.1%）
DNS 解析失败         37 个（2.6%）
```

把 4xx/5xx 当死站会误杀 173 个域名，比真正的死站（110 个）还多 57%。

**实现**：
- `check/SiteLiveness.java`：站点存活判定
  ```
  判 DEAD 只有三种：DNS 解析失败 / 连接被拒 / 无路由
  4xx/5xx、超时、SSL 失败、连接 reset → ALIVE 或 UNKNOWN
  ```
- `check/UsabilityBuckets.java`：校验结果分三档
  ```
  USABLE      校验通过
  SITE_ALIVE  站点在线但规则没跑通（403/404/超时）—— 不该删
  SITE_DEAD   站点确定不可用 —— 可清理
  UNKNOWN     跳过或超时
  ```

**一个重要的自查教训**：
我最初用手工构造的裸 HTTP/1.1 请求探测，判定 287 个域名「不可用」。
换成浏览器指纹 + HTTP/2 重测后**只剩 73 个**——**154 个域名其实是好的**，
只是不接受简陋请求。这印证了「连接异常 ≠ 死站」。

### 3.3 空壳书源判定

**用户需求**：搜索链路与发现链路同时缺失的源应列为错误源。

他给的例子（真实数据）：

```json
{
  "bookSourceGroup": "搜索链接规则为空,📖 小说",
  "bookSourceName": "啃文书库",
  "bookSourceUrl": "https://www.kenwen.com",
  "loginUrl": "",
  "enabled": true,
  "enabledExplore": true
}
```

**实现**：`core/SourceValidity.java`

```text
搜索链路有效 = (searchUrl 或 bookSourceUrl 非空) AND ruleSearch 有 bookList 或 name
发现链路有效 = exploreUrl 非空且(含内嵌规则语法 或 ruleExplore 有规则)
两者都无效 -> 空壳
```

**与用户提供的参考代码的两处修正**：
1. 兼容 v2 格式（他的代码只看 v3，会让 73.5% 的源误判）
2. 不强制要求 `searchUrl` 独立存在（legado 允许用 `bookSourceUrl` 作搜索根地址）

**验证**：
```
用户 2257 条：空壳 0 条，搜索不完整 1 条
公开 1295 条：空壳 0 条
用户给的「啃文书库」：正确判为空壳
```

**接入方式**：`DedupeEngine.run` 增加 `markEmptyShell` 参数，**默认 false，由 UI 开启**。

### 3.4 重定向同站检测（新增功能）

**用户需求**：很多书源 URL 重定向后其实是同一个网站。

**实现**：
- `check/RedirectCluster.java`：纯逻辑，把探测结果聚成「同落地站」的组
- `check/RedirectProbe.java`：32 并发探测（HEAD 优先，不读 body，可取消）

**真机实测**（2257 条 / 1441 个主机）：

```
扫描 2257 条，发现 34-36 组重定向同站，可合并 65-66 条冗余

bbqsk.info（7 条）    ← bbqsk.net / dingdiansk.com / ddsk.la / ddsk.org / ddsge.com
txtshuku.info（5 条） ← liudatxt.net / txtshuku.org / liudatxt.la / liudatxt.com
xuanhuange.info（5 条）← xuanyge.org / xuanshu.org / xuanyge.info / xuanyge.com
```

结果支持一键合并，保留策略：规则更完整的优先，URL 更短的优先（主域名比轮换域名稳定）。

### 3.5 会话持久化（新增功能）

**痛点**：导入 2257 条 + 跑 25 分钟校验后，应用被杀或重装全部归零。

**实现**：`core/SessionStore.java`
- GZIP 压缩（12 MB JSON → 412 KB）
- 原子写（先写 `.tmp` 再 rename）
- 容错（损坏/版本不匹配/解压超限 → 返回 null，绝不让应用起不来）

**内存影响**（用户专门问过）：**常驻增量 ≈ 0**。
书源对象 5.5 MB + 校验结果 1.1 MB 本来就在内存里，持久化只多一份磁盘副本。
真正要防的是反序列化峰值，GZIP 让它从 12 MB 降到 2-3 MB。

### 3.6 外部 Intent 接收（补上声明了却没实现的功能）

**发现**：Manifest 里早就写了 `legado://` scheme，但那是放在 `<queries>` 里——
**那只是查询权限，不是接收能力**。Activity 的 intent-filter 只有 `MAIN/LAUNCHER`，
且 `MainActivity` 从不读 `getIntent()`。

后果：用户在浏览器点书源链接、选择本应用，**什么都不会发生**。

现在支持 `legado://import?a=<url>`、`http(s)` 链接、`ACTION_SEND` 分享文本。

### 3.7 loginUrl 判定的改进

**用户反馈**：「loginUrl 不只是登录功能，一些源使用它作为高级自定义功能，如切换 URL 等等」。

**我用真实数据验证，用户完全正确**（2257 条里 335 条有 loginUrl）：

```
JS 函数/脚本     如 "function login(){}"        —— 根本不是登录页
JSON 配置钩子    如 {"url": "null"} / {"url": ""}
HTTP 地址        可能是真登录页
```

**实现**：`SourceCleaner.looksLikeRealLoginUrl()` 排除 JS 代码、空 url 的 JSON 配置、
纯分隔符。效果：旧逻辑剔除 335 条 → 新逻辑 265 条，**挽救 70 条主流源**。

### 3.8 其它修复

- **移除的源会"复活"**：移除只改了 result/checkResults，没动 `buckets`，
  而 `recompute` 从 `buckets.all()` 重算 → 删掉的源全部回来。
  已加 `SourceBuckets.removeByUrls()`，三处移除操作全部同步。
- **过滤开关默认开启**：`cleanLogin` / `deleteCaptcha` 默认 true，
  用户什么都没做就丢书源。改为默认 false，并做了老用户迁移。
- **开关位置错误**：这两个开关藏在「校验设置」弹窗里，却在去重阶段就生效。
  移到主界面过滤区。
- **代理环境提示**：实测开代理后可用数从 857 掉到 538，用户会误以为书源变差。
  新增 `EnvironmentSuspicion` 识别并提示。
- **失败原因逗号缺陷**：旧代码 `if (idx > 0)` 判断，以逗号开头的消息不截断，
  界面显示前导逗号。改为 `idx >= 0`。

---

## 四、踩过的坑（请务必先读这一节）

### 4.1 编译期发现不了、单测覆盖不到的问题

**一个 View 只能有一个父容器**

```java
ll.addView(sc);
new MaterialAlertDialogBuilder(this).setView(sc)  // ← 崩：child already has a parent
```

抛 `IllegalStateException: The specified child already has a parent`。
**只有真机点一遍才暴露**。要传外层容器 `setView(ll)`。

**Dialog 在 Activity 销毁后 show 会崩**

```java
runOnUiThread(() -> {
  if (state.isDestroyed() || isFinishing()) return;   // ← 这行必须有
  new MaterialAlertDialogBuilder(...).show();
});
```

**异步回调里的 `this` 指向 lambda**

```java
runOnUiThread(() -> new MaterialAlertDialogBuilder(this))      // 编译报错
runOnUiThread(() -> new MaterialAlertDialogBuilder(MainActivity.this))  // 正确
```

### 4.2 数据正确性陷阱

**移除书源必须同步 `buckets`**

`recompute` 每次都从 `buckets.all()` 全量重算。只改 `result` 和 `checkResults`
的话，删掉的源在下次重算时全部复活。

**这是原代码就有的缺陷**：移除后的 toast 写的是
「已移除选中书源，可从「解析网络源」重新去重」——而"重新去重"正是会复活的路径。
等于用提示掩盖了缺陷。

**会话恢复不能走异步 recompute**

`recompute()` 带代际守卫：

```java
final int gen = state.nextRecomputeGeneration();
recomputeExecutor.execute(() -> {
  if (gen != state.recomputeGeneration()) return;   // 被后续调用作废
});
```

冷启动时后续初始化触发别的 recompute，把恢复这次的结果作废，
表现为「数据读到了但界面不显示」。恢复路径直接主线程同步算。

### 4.3 语言/API 限制

**不要用 `String.join` / `StringJoiner`**：API 26 才有，minSdk 24 上 `NoSuchMethodError` 闪退，
且属于 Error，`catch (Exception)` 拦不住。用 `TextKit.join`。

**不要用 `URI.getHost()` 判定主机名**：中文域名与下划线主机会返回 null，
合法书源被整条丢弃。用 `UrlNormalizer.hostOf()`（URI → IDN → URL 三层兜底）。

### 4.4 构建

**删资源必须跑 `assembleRelease`**：
删了 `values/colors.xml` 里的颜色，但 `values-night/colors.xml` 里还有同名定义，
`lintVitalRelease` 会失败。**debug 构建不触发 lintVitalRelease，只测 debug 发现不了。**

### 4.5 测试

**测试数据要符合真实形态**：`SourceRecord` 要带 `searchUrl` + `ruleSearch`。
接入空壳判定时，7 个测试类因为只构造了 URL 而同时回归。

**单元测试固定 UTF-8**：`systemProperty("file.encoding","UTF-8")` 已配好，
涉及中文与 URL 编码的用例在默认 GBK 的机器上会误报，不要删这段配置。

### 4.6 真机验证

**adb 无法向 URL 输入框输入文本**：`input text` 和逐字符 keyevent 都失败，
唯一成功的一次被中文输入法转成了全角（`https：、、`）。
验证导入请用 `legado://` intent 或手动操作。

**文件选择器无法用 adb 可靠操作**：需要人工点选。

---

## 五、做判断时依据的真实数据

### 5.1 用户真实书源（`终极.json`，2257 条）

```
格式：100% v3
loginUrl 非空：335 条（14.8%）
  其中大部分是空 JS 函数（function login(){}）或配置钩子（{"url":"null"}）
bookSourceUrl 为空：0 条
去重后：小说 2085 / 漫画 102 / 视频 7 / 音频 32 / 文件 31
空壳：0 条
```

### 5.2 公开书源聚合（1295-1399 条，6 个镜像去重）

```
73.5% 是 v2 老格式
```

### 5.3 域名探测（1443 个唯一域名）

```
正常可用        921（63.8%）
4xx/5xx 但站活着 173（12.0%）← 403 占 106
连不上           73（5.1%）
DNS 失败         37（2.6%）
```

### 5.4 校验可用数受网络环境影响极大

同一批 2257 条书源：

```
无代理：857 可用 / 1400 失败
代理1 ：626 可用 / 1631 失败
代理2 ：538 可用 / 1719 失败
```

**这不是书源变差，是代理链路问题。**

### 5.5 重定向

```
2257 条 / 1441 主机 → 133 个主机跨域重定向
→ 34-36 组多主机指向同一站 → 65-66 条冗余
```

---

## 六、项目结构与关键文件

```
app/src/main/java/com/mina/yuedu/
├── MainActivity.java          # 约 3100 行，UI 与流程控制
├── core/                      # 纯逻辑（可单测）
│   ├── DedupeEngine.java      # 去重主逻辑
│   ├── SourceBuckets.java     # 书源容器（注意 removeByUrls）
│   ├── V2FieldAdapter.java    # v2→v3 字段映射
│   ├── SourceValidity.java    # 空壳判定
│   ├── UrlRecovery.java       # URL 恢复
│   ├── ResultPruner.java      # 剔除时同步三份数据
│   ├── SessionStore.java      # 会话持久化
│   ├── MainState.java         # 并发状态与代际
│   └── ...
├── check/                     # 校验相关
│   ├── CheckSourceEngine.java # 五步校验
│   ├── SiteLiveness.java      # 站点存活判定
│   ├── UsabilityBuckets.java  # 三档分类
│   ├── RedirectCluster.java   # 重定向分组
│   ├── RedirectProbe.java     # 并发探测
│   ├── EnvironmentSuspicion.java # 代理影响识别
│   └── ...
├── model/                     # 数据模型
├── export/                    # 导出
└── network/                   # 网络层
```

**文档**：
- `README.md`：用户向的功能说明与更新日志
- `AGENTS.md`：给 AI 的快速交接（简版，本文档的浓缩）
- `项目工作日志.md`：完整的工作记录，含每次改动的原因与验证
- `docs/CHECK_SOURCE.md`：校验功能说明
- `docs/REFACTOR_PLAN.md`：重构计划

---

## 七、给接手 AI 的建议

### 7.1 改动前必做

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

**必须跑 `assembleRelease`**，debug 不触发 `lintVitalRelease`。

### 7.2 判断「能不能用」的正确姿势

**不要只看 HTTP 状态码。** 按这个层次：

```
1. DNS 能不能解析？       不能 → 死站
2. TCP 能不能连上？       被拒 → 死站
3. 有没有 HTTP 响应？     有 → 站点活着（哪怕 500）
4. 书源规则能不能跑通？   不能 → 规则失效，但站点还在
```

**403 是最常见的反爬，不是死站。** 实测 173 个 4xx/5xx 里 106 个是 403。

### 7.3 探测要用真实浏览器指纹

用手工构造的裸 HTTP/1.1 请求探测，误判率极高（实测 287 → 73，误判 154 个）。
要用完整浏览器头 + 支持 HTTP/2。

### 7.4 用户反馈要认真核实，但也要拿数据验证

本项目有过一次教训：AI 回复用户说「这条不成立」，但**逻辑上自相矛盾**
（用户说 loginUrl 非空被剔除，AI 反驳"空串不触发"）。用户明确批评了这点。

**正确做法**：拿真实样本验证，用数据说话。本文档第五节的数据都是这么来的。

### 7.5 保守优于激进

- V2 适配后导出的 JSON 会同时带 v2 和 v3 两套字段（文件变大 30-50%），
  但**不清理 v2 字段**——它是原始数据的保险，万一 v3 适配有遗漏还能救。
- 空壳判定 `markEmptyShell` 默认 false，由 UI 显式开启。
- 会话文件损坏时返回 null，绝不让应用起不来。

### 7.6 Release Notes 要精简（用户明确反馈过）

v3.7.4 的 Release Notes 被用户评价为「**太多太杂**」。

**问题**：写了 5000+ 字，把所有改动、所有实测数据、所有实现细节都堆上去，
像一份开发日志，不像给用户看的发布说明。

**正确写法**：
- 先说「最重要的 1-2 件事」，其他降级为短条目
- 每条控制在 2-3 句，讲**用户能感知到什么变化**，不讲实现细节
- 实测数据只留最有说服力的 1-2 个数字，不要贴完整表格
- 「其它修复」用一个列表带过，每条一行
- 结尾放安装信息（版本/大小/SHA256）+ 一句注意事项

**反面例子**（v3.7.4 写的）：
> 写了「统计卡片、导出预览、分类开关全部正常」这种开发自测内容，
> 用户不关心；写了「GZIP 压缩 + 流式写出」这种实现细节，用户也看不懂。

**正面例子**：
> 「会话持久化 — 重启后书源和校验结果还在。GZIP 压缩 412 KB，
> 内存增量约 0（数据本来就在内存里）。」

### 7.7 发版检查清单

```
1. 改版本号（build.gradle.kts 的 versionCode + versionName）
2. 四处版本号同步：build.gradle.kts / README / AGENTS.md / 项目工作日志
3. ./gradlew clean testDebugUnitTest assembleDebug assembleRelease lintDebug
4. 真机装 release 包验证（不能只测 debug）
5. 打 tag 并推送
6. 写 Release Notes（精简！见 7.6）
7. 推送到 GitHub
8. 用户在网页创建 Release 并上传 APK
9. 下载已发布的 APK 校验 SHA256 与本地一致
```

---

## 八、当前状态

```
版本：v3.7.4（versionCode 314）
测试：499 项 / 49 套件 / 0 失败
Lint：Error 0
构建：debug + release 通过
真机：OPPO PJD110 / Android 17，启动正常、无崩溃、无 ANR
仓库：https://github.com/MIXUULS/yuedu-source-dedupe
```

**已发布版本**：v3.7.0、v3.7.1、v3.7.2、v3.7.3、v3.7.4（均含 APK）

---

## 九、还可以继续做的事

按价值排序：

1. **校验超时的默认值**：现在 180 秒，慢站会拖到 100+ 秒。
   降到 60 秒能快 40-50%，但会影响极慢站。需要用户确认。
2. **`CheckSourceEngine` 的 v2 适配**：已接入，但 v2 源的校验成功率还没大规模验证。
3. **重定向检测的增量探测**：每次全量探测几分钟，可加缓存。
4. **多份历史会话**：现在只存最近一次。要这个能力才需要上 Room。
5. **同 URL 不同 bookSourceType 的提示**：用户提过，当前设计是保留不合并，
   但可以在明细里标注出来让人工判断。
