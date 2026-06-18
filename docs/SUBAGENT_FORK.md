# RikkaHub Subagent Fork

基于 **kimi-code** 的 subagent 体系，为 RikkaHub 移植的「子代理委派」能力。

## 设计来源对照

| kimi-code (TypeScript) | RikkaHub (Kotlin) | 说明 |
|---|---|---|
| `SessionSubagentHost` | `data/ai/subagent/SubagentHost.kt` | 子代理宿主：派生 / 运行 / 回收摘要 |
| `subagent-batch.ts` | （未移植批量调度） | RikkaHub 暂以单任务派发为主 |
| `ResolvedAgentProfile` / `DEFAULT_AGENT_PROFILES.subagents` | `SubagentProfile` + `SubagentProfile.BUILTIN` | 子代理配置档（系统提示 / 模型 / 工具子集 / 步数） |
| `collaboration/agent.ts` (spawn tool) | `SubagentTools.kt` → `spawn_subagent` | 父代理调用以委派任务 |
| `startBtw` (side-channel) | `SubagentTools.kt` → `ask_btw` | 轻量无工具"顺便问一句" |
| `SUMMARY_MIN_LENGTH` / `summary-continuation.md` | `SubagentProfile.summaryMinLength` + continuation | 摘要过短时追问扩写 |
| Agent turn loop | `GenerationHandler.generateText` | 子代理"运行到完成"= 一次完整 generate 循环 |

## 核心思路

RikkaHub 没有 kimi-code 的 Session/Agent/Turn 抽象，但其 `GenerationHandler.generateText()`
本身就是一个完整的「模型 ↔ 工具」自主循环。因此**子代理 = 一次嵌套的 `generateText` 调用**：

1. 父代理获得 `spawn_subagent` 工具（仅当 `Assistant.enableSubagents = true`）。
2. 模型调用 `spawn_subagent(profile_name, task, description)`。
3. `SubagentHost` 按 profile 构造一个子 `Assistant`（独立系统提示 / 可换模型 / 工具子集）。
4. 以 `[user(task)]` 为初始消息，跑一段独立的 `generateText` 循环到完成。
5. 提取末条 assistant 文本作为「摘要」；过短则追问一次扩写。
6. 摘要作为工具结果返回给父代理，父代理据此继续。

子代理是**自主、一次性、无 HITL** 的：其工具经 `SubagentHost.sandboxToolsForSubagent`
关闭 `needsApproval`，避免审批打断循环（对应 kimi-code 子代理的自主执行语义）。

## 新增 / 修改文件

新增（`app/src/main/java/me/rerere/rikkahub/data/ai/subagent/`）：
- `SubagentProfile.kt` — 配置档数据模型 + 内置 profile（explore / coder / reviewer）+ `mergeSubagentProfiles` + `SubagentResult`
- `SubagentHost.kt` — 子代理宿主（spawn / 摘要续写 / 工具沙箱化）
- `SubagentTools.kt` — `spawn_subagent` / `ask_btw` 工具构造 + `SUBAGENT_TOOL_NAMES`

修改：
- `data/model/Assistant.kt` — 新增 `enableSubagents` / `subagentMaxDepth` / `subagentProfiles` 字段（带默认值，向后兼容）
- `service/ChatService.kt` — 注入 `SubagentHost` + `Json`；`handleMessageComplete` 装配 subagent 工具；新增 `buildSubagentTools` / `buildSubagentBaseTools`（按深度递归、沙箱化）
- `di/AppModule.kt` — 注册 `SubagentHost`，向 `ChatService` 注入 `subagentHost` / `json`

## UI 界面（子代理设置）

按「方案 A」实现：作为 Assistant 详情页的新入口，与 MCP / LocalTool 平级。

入口：`AssistantDetailPage` CardGroup 新增「子代理」项（`HugeIcons.Connect` 图标）。

**列表页** `AssistantSubagentProfilePage` 同级目录下的 `AssistantSubagentPage.kt`：
- 总开关 `enableSubagents`（Switch）
- 最大嵌套深度 `subagentMaxDepth`（1–5 Slider）
- profile 列表（内置 explore/coder/reviewer + 自定义，`mergeSubagentProfiles` 合并展示），每项点击进入编辑页

**编辑页** `AssistantSubagentProfilePage.kt`（独立详情页，路由 `Screen.AssistantSubagentProfile(id, profileName)`）：
- 基本信息：name（只读标识符）/ displayName / description
- 系统提示词：`TextArea`（复用项目组件，支持全屏/导入）
- 模型：`ModelSelector`（留空继承父模型）
- 参数：temperature / topP / maxTokens / reasoningLevel（`ReasoningButton`）
- 行为：maxSteps / inheritTools / streamOutput / enableMemory / summaryMinLength

编辑内置 profile 时通过 `upsertSubagentProfile` 写入 `subagentProfiles`（同名覆盖内置），
符合「允许直接编辑内置 profile」的决策。

技术要点：
- systemPrompt 用 `rememberTextFieldState` + `snapshotFlow` + `rememberUpdatedState` 持久化，
  避免长效协程闭包捕获 stale assistant 导致并发编辑互相覆盖。
- 数值字段用稳定 key `remember(label)`，避免 `Float.toString()`（"1.0"）覆盖用户输入中间态。

路由注册：`RouteActivity.kt` 新增 `Screen.AssistantSubagent` / `Screen.AssistantSubagentProfile` + 两个 `entry`。
字符串：`values/strings.xml` + `values-zh/strings.xml` 新增 subagent_* 系列。

## 包名与固定签名（避免与原版 APK 冲突）

为让 fork 能与官方 RikkaHub **共存安装**，且所有 fork 构建使用**同一固定签名**：

### 包名
- `app/build.gradle.kts`: `applicationId` 由 `me.rerere.rikkahub` → `me.rerere.rikkahub.sub`
- **不改** Kotlin 源码 `package` 声明（369 个文件）。Android 官方支持 `applicationId != 包名`，
  manifest 的 `${applicationId}` 与运行时 `context.packageName` 会自动同步为新值，
  FileProvider authorities（`*.fileprovider`/`*.documents`/`*.androidx-startup`）随之一致，无需改源码。
- `versionName` → `2.3.1-sub` 以区分版本。
- 应用显示名 `app_name` → `RikkaHub Sub`（启动器里区分）。
- `google-services.json` 新增 `me.rerere.rikkahub.sub` 的 client 条目，让 google-services 插件构建时匹配（原 json 本就是占位 dummy key）。

### 固定签名
- 新增 keystore: `app/keystore/subagent-fork.keystore`（PKCS12，RSA 2048，有效期 100 年，alias `subagent-fork`）
  - SHA-256 证书指纹: `55:7C:4D:0B:C3:81:ED:C2:C7:CA:FD:F7:9A:84:64:6C:03:CF:20:A4:FA:15:6C:C4:9D:0B:8F:EA:71:DA:2C:11`
- `signingConfigs.release` 策略改为：
  1. 优先读 `local.properties` 的 `storeFile/storePassword/keyAlias/keyPassword`（便于有自己 key 的人覆盖）；
  2. 否则回退到仓库内置 fork keystore → **保证 fork 构建始终用同一固定签名**，所有 fork 版本可互相升级、签名身份一致。
- keystore **未加入 .gitignore**（随仓库分发），使任何环境构建的 fork 签名一致、可复现。
- 凭据（口令均为 `rikkahub-subagent-fork`）已硬编码在 build.gradle.kts 的回退分支中。

> 安全提示：这是 fork 专用的公开签名 key，仅用于 fork 自身的一致性，不应用于原版或生产签名。

## 递归与安全

- 嵌套深度由 `Assistant.subagentMaxDepth`（默认 2）控制：`depth + 1 < maxDepth` 时才向子代理注入 `spawn_subagent`，防止无限递归。
  实际可委派层数 = `maxDepth - 1`（`maxDepth=2` → 1 层子代理）。UI 滑块会显示允许的委派层数，并在 `maxDepth=1` 时提示「未启用委派」。
- 子代理不挂 MCP（`mcpServers = emptySet()`）、不启用 skills / 注入 / 记忆引用，保证一次性隔离。
- `ask_btw` 使用无工具、单轮的合成 profile（对应 kimi-code `DenyAllPermissionPolicy` + 工具禁用的 side-channel）。
- `SubagentProfile.excludedTools` 按 **工具名** 过滤子代理可用工具（例如 reviewer 排除 `workspace_write_file` /
  `workspace_shell`），在 `SubagentHost.spawn` 中应用。此前该字段定义了但未被使用，已修正。

## 运行可见性（完善项）

子代理此前是「黑盒」——父代理调用 `spawn_subagent` 后，用户在等待期间看不到任何反馈。
现已加入状态冒泡链路：

- `SubagentHost.spawn` 新增 `onStatus: ((String?) -> Unit)?` 回调，在每个 generation chunk 到达时
  推断一句简短状态（`↳ subagent [Explorer] calling workspace_shell` / `thinking` / `expanding summary`），
  多级嵌套时按深度缩进区分。
- `ChatService.buildSubagentTools` 把该回调接根对话的 `session.processingStatus`，子代理运行期间
  顶栏状态栏会显示「子代理正在做什么」，结束后回调 `null` 把状态还给父代理。

## 用量与步数统计（完善项）

- `SubagentResult` 新增 `usage: TokenUsage?`（跨轮次累计，含扩写追问）与 `steps: Int`（实际 generation 轮次）。
- `spawn_subagent` 工具结果 JSON 现在包含 `steps`；usage 通过 `Log.i` 记录（prompt/completion/cached/total），
  便于排查子代理 token 消耗。

## 深度语义修正（完善项）

`buildSubagentTools` 此前在把 `depth+1` 传给 `SubagentHost.spawn` 的同时，又在 `buildChildTools` 回调里
再 `+1` 构建子代理工具，导致深度双重递增、`maxDepth` 与真实嵌套层数错位。已修正：子代理工具以其自身
深度（`spawn` 已位于 `depth+1`）构建，不再额外 `+1`。

## 使用方式

在 Assistant 配置中开启 `enableSubagents`，可选自定义 `subagentProfiles`（同名覆盖内置）。
开启后模型即可在对话中调用 `spawn_subagent` 委派任务。

> 注：本次完善（excludedTools 应用 / 深度 off-by-one 修正 / 运行可见性 / 用量统计）已通过
> GitHub Actions（`build-fork.yml`）完整编译验证：`:app:compileDebugKotlin` +
> `assembleDebug`（BUILD SUCCESSFUL in 3m45s）+ `assembleRelease`（BUILD SUCCESSFUL in 9m43s）
> 均通过，Debug / Release APK 工件均产出。
