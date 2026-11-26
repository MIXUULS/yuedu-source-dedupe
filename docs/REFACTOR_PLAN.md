# 深水区重构 + Issue #1 修复 · 执行计划

**制定日期：** 2026-11-26
**基线：** v3.7.2 / `6c39050` / 280 项测试 / MainActivity 2809 行

---

## 决策记录

用户选择：

| 议题 | 决定 |
|---|---|
| 优先级 | **先重构，再一起修 issue** |
| 空壳书源 | 归入错误/无效源 |
| www/手机站归一化 | **只在严格模式启用** |

---

## Issue #1 核实结论（已实测）

| 用户反馈 | 核实 | 依据 |
|---|---|---|
| loginUrl 非空的书源被误剔 | ❌ 不成立 | `SourceCleaner.hasValue()` L32-35 已判空，空串不触发 |
| bookSourceUrl 为空被剔除 | ✅ 成立 | `UrlNormalizer.validate()` 只认 scheme，空 URL 直接抛异常 |
| 空壳书源应视为错误 | ✅ 成立 | `RuleCompleteness` 仅诊断不判定；空壳源仍计入"有效" |
| URL 相同但 type 不同 | ⚠️ 设计如此 | 标准模式按完整 URL 去重，type 不参与 key |
| 重定向去重 | 💡 建议 | 需发网络请求，宜做成可选开关 |
| www/手机站归一化 | 💡 建议 | 采纳，仅严格模式 |

---

## 阶段划分

### 阶段 A：为重构建立测试安全网（不可跳过）

**目标：** 让三大流程方法有测试覆盖，否则重构等于裸奔。

当前状态：`MainActivity` 有 16 个 Robolectric 行为测试，但**没覆盖** `startFetch` / `recompute` / `runCheck` 的内部逻辑（它们依赖网络与线程）。

| 步骤 | 内容 | 验证 |
|---|---|---|
| A1 | 抽 `IFetchGateway` 接口，让 `FetchManager` 实现；`MainActivity` 持有接口而非具体类 | 编译通过，现有测试全绿 |
| A2 | 抽 `ICheckGateway` 接口，让 `CheckSourceEngine` 实现 | 同上 |
| A3 | 用 fake gateway 补 `startFetch` 行为测试（成功/失败/取消/代际作废） | 新增 8-10 项 |
| A4 | 补 `runCheck` 行为测试（并发守卫/停止/结果写入/健康记录） | 新增 8-10 项 |
| A5 | 补 `recompute` 行为测试（模式切换/代际丢弃/清空后不复活） | 新增 6-8 项 |

**风险控制：** A1/A2 只改类型声明，不改逻辑；每步都能独立编译验证。

**产出：** 测试 280 → 约 305 项

---

### 阶段 B：抽 `MainState` 收敛并发状态

**目标：** 消掉"状态散落导致踩踏"的根源。

当前散落在 `MainActivity` 的并发相关字段：

```
destroyed, fetchRunning, checkRunning, checkCancelRequested,
fetchGen, recomputeGen, checkGen, discard
```

| 步骤 | 内容 |
|---|---|
| B1 | 新建 `core/MainState`：持有上述 8 个字段 + 代际自增方法 + 状态查询 |
| B2 | 先只做"影子替换"——`MainActivity` 内部转发到 `MainState`，外部行为不变 |
| B3 | 补 `MainState` 单元测试（代际递增/守卫判定/状态转换） |
| B4 | 移除 Activity 里的重复字段 |

**为什么分 B2/B4：** 60+ 处引用一次性改完风险高。影子替换让每步都可回滚。

**产出：** 测试 +约 15 项，`MainActivity` 减少约 10 行（但消除 8 处状态耦合）

---

### 阶段 C：抽 `FetchController`

**目标：** 把网络抓取流程从 Activity 剥离。

| 步骤 | 内容 |
|---|---|
| C1 | 新建 `controller/FetchController`：持有 gateway、消费 URL 队列、回调进度 |
| C2 | UI 回调通过 listener 接口暴露，Controller 不碰 View |
| C3 | `MainActivity` 只保留"启动/停止/接结果"三件事 |
| C4 | 用 A3 的 fake gateway 测 Controller |

**预期：** `MainActivity` 减少约 120 行

---

### 阶段 D：抽 `CheckController`

**目标：** 同理剥离校验流程。

| 步骤 | 内容 |
|---|---|
| D1 | 新建 `controller/CheckController`：目标准备 → 执行 → 结果汇总 → 历史/健康记录 |
| D2 | 复用已有的 `CheckTargetPreparer` / `CheckHistoryStore` / `RecheckPlanner` |
| D3 | `MainActivity` 保留 UI 绑定与结果展示 |

**预期：** `MainActivity` 减少约 150 行

---

### 阶段 E：修复 Issue #1（重构完成后）

**E1. 空 bookSourceUrl 降级**

```
bookSourceUrl 为空
  → 尝试 searchUrl 的 scheme://host
  → 尝试 ruleSearch.baseUrl / ruleExplore.baseUrl
  → 都无 → 判为无效（附明确原因）
```

新增 `core/UrlRecovery`，纯逻辑可测。

**E2. 空壳书源识别**

复用 `RuleCompleteness`，新增判定：

```
搜索链路 = searchUrl 非空 AND ruleSearch 非空
发现链路 = exploreUrl 非空 OR ruleExplore 非空
两者皆无 → 空壳书源 → 归入 invalid，reason="无搜索与发现链路"
```

新增 `core/SourceValidity`，把"仅诊断"升级为"可判定"。**保留 `RuleCompleteness` 原行为**（诊断提示），新增独立判定入口，避免破坏现有 UI 提示。

**E3. strict 模式 www/手机站归一化**

`UrlNormalizer` 在 STRICT 模式下：
- 剥离 `www.` 前缀
- 剥离 `m.` / `wap.` / `mobile.` 前缀
- 三者归一到裸域

**仅 STRICT 模式**，STANDARD 与 AGGRESSIVE 行为不变（用户明确要求）。

**产出：** 测试 +约 25 项

---

## 验证标准（每阶段结束必须满足）

```
1. ./gradlew testDebugUnitTest    → 0 失败
2. ./gradlew assembleDebug        → BUILD SUCCESSFUL
3. ./gradlew lintDebug            → Error 0
4. 真机冒烟（设备在线时）：
   - 启动无崩溃
   - 2257 条书源导入 → 去重结果与重构前一致
   - 深色模式切换不丢数据
5. 独立 commit，独立可回滚
```

---

## 风险与对策

| 风险 | 对策 |
|---|---|
| 重构引入行为回归 | 每阶段跑全量测试 + 真机比对去重结果（2257 条的基准值已记录：2085 小说/102 漫画/7 视频/32 音频/31 文件） |
| 接口抽取破坏现有调用 | A1/A2 只改声明不改逻辑，先编译验证再继续 |
| 状态收敛遗漏引用 | B2 用影子替换，编译器会抓出所有遗漏 |
| 测试运行变慢 | Robolectric 已缓存 android-all jar；单次全量约 30-40 秒 |
| 破坏 issue 用户提到的行为 | E 阶段新增判定都走独立入口，不改既有过滤链 |

---

## 预计产出

| 指标 | 现在 | 完成后 |
|---|---|---|
| 测试数 | 280 | 约 355 |
| MainActivity 行数 | 2809 | 约 2540 |
| 可测类 | 51 | 约 58 |
| Issue #1 真实缺陷 | 2 个未修 | 全部修复 |
