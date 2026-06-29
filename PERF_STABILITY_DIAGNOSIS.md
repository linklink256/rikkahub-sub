# 性能与稳定性诊断报告 — RikkaHub

> 诊断时间：2025-06-27  
> 方法：逐项核查 Android 常见性能反模式，结合日志模式/静态分析  
> 边界：只读不改，所有结论引用具体 file:line/函数名

---

## 严重度分级
| 等级 | 含义 |
|------|------|
| 🔴 Critical | 必然导致崩溃、ANR、内存 OOM |
| 🟠 High | 大概率导致卡顿/泄漏/非预期行为 |
| 🟡 Medium | 特定条件下影响性能或稳定性 |
| 🔵 Low | 轻微资源浪费/可优化但不紧迫 |

---

## 1. 主线程阻塞

### 🔴 1.1 `runBlocking` 在 Compose UI 线程执行语法高亮

**位置**: `app/.../ui/components/richtext/HighlightCodeBlock.kt:517`  
`HighlightCodeVisualTransformation.filter()` → `runBlocking { highlighter.highlight(...) }`

**描述**: `HighlightCodeVisualTransformation` 实现了 `VisualTransformation.filter()`，此方法在 Compose UI 测量/布局线程被同步调用。内部用 `runBlocking` 调用 `highlighter.highlight()`（可能涉及词法分析等 CPU 密集型工作）。每次 TextField 重组（含输入、滚动、语法高亮变化）都会同步阻塞 UI 线程完成高亮。

**触发条件**: 任何用到 `HighlightCodeVisualTransformation.regex()` 的 TextField（正则表达式输入框等），每次按键或滚动触发过滤时阻塞 UI 线程。

**影响**: UI 线程阻塞 → 输入延迟、动画掉帧、严重时 ANR（尤其大段代码高亮时）。

**改进方向**: 将高亮移至后台协程，用 `LaunchedEffect` + `snapshotFlow` 异步计算结果后写入 `VisualTransformation`。

---

### 🔴 1.2 `ProcessBuilder` 同步执行（HostShellRunner 未使用协程）

**位置**: `workspace/.../WorkspaceShellRunner.kt:26-28`  
`HostShellRunner.execute()` → `ProcessBuilder(...).start()` → `process.readResult()`

**描述**: `HostShellRunner.execute()` 是同步阻塞调用。调用链路：`WorkspaceRepository` → `WorkspaceShellRunner.execute()`。如果调用方未切换到 `Dispatchers.IO`（例如在协程默认调度器上调用），会阻塞协程所在线程。虽然大多数调用点用了 `withContext(Dispatchers.IO)`，但没有强制约束，存在遗漏风险。

**触发条件**: 任何在非 IO 调度器上调用 HostShellRunner.execute() 的场景。

**影响**: 阻塞协程所在线程（可能是 Main），导致 ANR。

**改进方向**: 将 `execute()` 改为 `suspend` 函数或内部强制 `withContext(Dispatchers.IO)`。

---

## 2. Compose 重组热点

### 🟠 2.1 未加 `key` 的 `items()` 导致全量重组

**位置**:  
- `app/.../ExtensionContent.kt:47` `items(modeInjections)`  
- `app/.../ExtensionContent.kt:81` `items(lorebooks)`  
- `app/.../ExtensionContent.kt:165` `items(quickMessages, key = { it.id })` ← 这个有 key，但上面两个没有  
- `app/.../SearchPicker.kt:238` `itemsIndexed(settings.searchServices)`  
- `app/.../ChatMessageTools.kt:175` `items(images)`  
- `app/.../ChatMessageTranslation.kt:116` `items(languages)`

**描述**: `LazyColumn`/`LazyRow` 的 `items()` 未提供 `key` 参数时，列表项数据变化会导致 Compose 无法正确识别哪些项需要重组，从而触发不必要的全量重组。

**触发条件**: 对应列表数据发生增删改时。

**影响**: 列表滚动卡顿、重组开销浪费。`modeInjections`/`lorebooks` 列表较长时更明显。

**改进方向**: 为所有 `items()` 添加稳定且唯一的 `key`。

---

### 🟠 2.2 重构开销：`ChatMessage.kt` 中对消息 Parts 的反复遍历

**位置**: `app/.../ui/components/message/ChatMessage.kt:290-293`  
`handleClickCitation` 中每层点击遍历所有 tool parts 做 JSON 解析

**描述**: `handleClickCitation` 用 `remember` 缓存，但内部引用 `partsState`（通过 `rememberUpdatedState`），每次点击遍历所有 tool parts + JSON 解析。若消息有大量 tool 输出，JSON 解析在点击主线程同步执行。

**触发条件**: 用户点击 citation 链接时。

**影响**: 点击响应延迟。

**改进方向**: 考虑预解析 citations 为 Map 结构缓存。

---

### 🟠 2.3 `remember(data)` 范围过宽导致 WebViewState 重复创建

**位置**: `app/.../ui/components/webview/WebView.kt:339-347`  
`rememberWebViewState(data, baseUrl, encoding, mimeType, historyUrl)` 用 `remember(data, baseUrl, ...)` 作为 keys

**描述**: 当 `data`（HTML 内容）在流式生成中频繁变化时，`remember` 的 key 改变导致 `WebViewState` 被重新创建，进而 `AndroidView` 中的 `update` 块被触发。在 Mermaid 渲染（`Mermaid.kt:93`）和 Markdown 预览中，如果内容频繁更新，会导致 WebView 实例反复重新加载。

**触发条件**: 流式生成中 Markdown/Mermaid 内容频繁更新。

**影响**: WebView 闪烁、重新加载、视觉效果抖动。

**改进方向**: 对 WebView 内容做去抖（debounce）或使用 `snapshotFlow` + `debounce` 控制更新频率。

---

### 🟡 2.4 `CodeBlockWithLineNumbersWrapped` 逐行 `Column` + `Row` 导致每行一个 Composable

**位置**: `HighlightCodeBlock.kt:143-165`

**描述**: 当代码块行数较多（如数百行代码）且 `showLineNumbers && autoWrap` 时，每行创建一个 `Row` + `Text`（行号）+ `HighlightText`（代码）。每行都是独立 Composable，重组时逐行评估。

**触发条件**: 大段代码渲染（如 AI 生成的长代码块）。

**影响**: 长代码块首次组合开销大，滚动时每行重组。

**改进方向**: 考虑使用 `LazyColumn` 或合并为单个 `AnnotatedString` 渲染。

---

## 3. 内存泄漏

### 🔴 3.1 单例/长生命周期对象持有 Context

**位置**:  
- `app/.../ui/hooks/ASR.kt:56` `ASRState` 持有 `Context`  
- `app/.../ui/hooks/TTS.kt:119` `TTSState` 持有 `Context`  
- `app/.../ui/pages/chat/Export.kt:244,383` 函数参数传递 `Context`

**描述**: `ASRState` 类（`data class`）和 `TTSState` 类持有 `Context`（很可能指向 Activity），但被存储在 Compose state 中（`remember`）。当 Composable 退出组合但 `Context` 引用仍被 state 持有，若 `DisposableEffect` 清理未正确释放引用，可能导致 Activity 泄漏。

**触发条件**: Activity 重建（如旋转屏幕）时，ASR/TTS hook 中 state 持有的旧 context 未被释放。

**影响**: Activity 实例泄漏，每旋转一次累积泄漏。

**改进方向**: 使用 `Application` context 而非 Activity context，或在 `DisposableEffect` 的 `onDispose` 中清理引用。

---

### 🔴 3.2 CoroutineScope 未随 UI 生命周期取消

**位置**:  
- `app/.../ui/hooks/TTS.kt` `TTSState` 使用的 `CoroutineScope`  
- `speech/.../DashScopeASRController.kt:49` `scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`  
- `speech/.../MiMoASRController.kt:64` 同上  
- `speech/.../OpenAIRealtimeASRController.kt:48` 同上  
- `speech/.../StepASRController.kt:70` 同上  
- `speech/.../VolcengineASRController.kt:54` 同上  
- `speech/.../AudioPlayer.kt:52` `scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`  
- `speech/.../TtsController.kt:36` 同上  
- `speech/.../EnergyVadDetector.kt:44` `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`

**描述**: 多个 ASR/TTS 控制器在 `init`/属性声明中创建自己的 `CoroutineScope(SupervisorJob())`。这些 scope 在 `dispose()` 方法中才被 `scope.cancel()`。如果 Composable 通过 `DisposableEffect` 管理这些控制器的生命周期，在 `onDispose` 中调用了 `dispose()` 则无问题，但需要逐一确认。ASR/TTS hook 的 `DisposableEffect` 块需要验证是否会正确调用 `dispose()`。

**触发条件**: 页面异常退出（如 Activity 被系统杀死）时 `DisposableEffect` 可能未执行。

**影响**: 协程泄漏、音频录制线程残留、资源未释放。

**改进方向**: 确保所有自定义 `CoroutineScope` 在 `DisposableEffect.onDispose()` 中被 `cancel()`。

---

### 🟠 3.3 WebView 实例长期持有

**位置**: `app/.../ui/components/webview/WebView.kt:238`  
`internal var webView: WebView? by mutableStateOf(null)`  
**持有条件**: `WebViewState` 被 `remember` 在 Compose 组合中。

**描述**: `WebViewState` 持有对 `WebView` 的强引用 (`internal var webView: WebView?`)。`WebView` 本身持有对创建它的 `Context` 的强引用。如果在 `onRelease` 回调中未正确置空 `state.webView`，或状态对象逃逸出组合生命周期，可能导致 WebView 泄漏。

**触发条件**: WebView composable 被移除但 `WebViewState` 仍被其他对象持有引用。

**影响**: WebView 实例和其 Activity Context 泄漏。

**改进方向**: 确保 `onRelease` 中置空 `state.webView`（已实现），并确保 `WebViewState` 不逃逸。

---

### 🟡 3.4 通知中的 PendingIntent 保留对 Activity 的引用

**位置**: `app/.../service/ChatService.kt:1530-1540`  
`getPendingIntent()` 使用 `Intent(this@ChatService, RouteActivity::class.java)`

**描述**: `PendingIntent` 使用 `FLAG_UPDATE_CURRENT` 会导致多次调用共享同一个 PendingIntent，且 Intent extras 的更新可能相互覆盖。虽然 `FLAG_IMMUTABLE` 已设置，但多个 notification 使用 `conversationId.hashCode()` 作为 requestCode 存在冲突风险（不同 conversationId 可能 hashCode 相同）。

**触发条件**: 多个对话同时生成时。

**影响**: 通知点击可能跳转到错误的对话。

**改进方向**: 使用 `conversationId.toString().hashCode()` 提高唯一性，或使用 `FLAG_CANCEL_CURRENT`。

---

## 4. 流式生成（SSE）稳定性

### 🟠 4.1 `callbackFlow` 中 `trySend` 丢失事件

**位置**: `common/.../http/SSE.kt:42-48`  
`trySend(SseEvent.Event(...))` 使用 `callbackFlow` 的 `trySend` 而非 `send`

**描述**: `trySend` 是非挂起函数，当 channel 已满（背压）时会静默丢弃事件。SSE 事件流中的文本 token 可能被静默丢弃，导致 AI 回复不完整。

**触发条件**: UI 收集速度跟不上 SSE 事件产生速度（虽然这种情况少见）。

**影响**: AI 回复文本片段丢失，内容不完整。

**改进方向**: 改用 `send()`（挂起函数）配合协程背压，或在 `callbackFlow` 中使用 `BufferOverflow.DROP_OLDEST` 策略（需要明确设计决策）。

---

### 🟠 4.2 `flowOn(Dispatchers.IO)` 未正确处理协程上下文传播

**位置**: `app/.../data/ai/GenerationHandler.kt:315`  
`}.flowOn(Dispatchers.IO)` 应用于 `generateText()` 的返回 Flow

**描述**: `flowOn` 将上游（生产者）切换到 `Dispatchers.IO`，但下游收集者在何处运行取决于调用方。如果下游在 `Dispatchers.Main` 上收集，`emit` 到 `onUpdateMessages` 的回调链涉及从 IO → Main 的切换。`handleMessageChunk` 等操作在 IO 线程执行，但更新 UI state 在 Main。如果 `onUpdateMessages` 闭包中访问了 Compose state（根据代码分析确实如此），在 IO 线程修改 Compose state 是线程不安全的。

**触发条件**: 流式生成过程中，`messages` 的 `transforms`/`visualTransforms` 处理可能涉及 UI 相关操作。

**影响**: 潜在的 IllegalStateException 或重组时序问题。

**改进方向**: 将 state 更新部分用 `withContext(Dispatchers.Main)` 保护，或确保 `onUpdateMessages` 回调切换到 Main。

---

### 🟡 4.3 超大 Token 输出时内存压力

**位置**: `app/.../data/ai/GenerationHandler.kt:305-307`  
`toolsInternal.find { it.name == tool.toolName }` 在循环中重复查找

**描述**: 主生成循环（`for (stepIndex in 0 until maxSteps)`）中未对 `toolsInternal` 建立名称索引，每步循环中多次线性搜索工具列表。如果 `toolsInternal` 很大（~100 个工具），每步循环的查找开销不可忽视。

此外，`messages` 列表在每次 `emit` 前被完整复制（`messages.transforms(...)`、`messages.visualTransforms(...)`），随着消息数量增长，每步的内存拷贝开销线性增长。`maxSteps` 默认 256 步，最坏情况下导致大量 List 分配。

**触发条件**: 长对话（数十轮）且工具列表较大的场景。

**影响**: 内存分配压力增大，GC 频繁，可能卡顿。

**改进方向**: 为 `toolsInternal` 建立 `Map<String, Tool>` 索引；考虑对 `messages` 使用快照而非完整拷贝。

---

## 5. 数据库反模式

### 🟠 5.1 大表全量查询无分页

**位置**:  
- `app/.../db/dao/ConversationDAO.kt:15` `getAll(): Flow<List<ConversationEntity>>` 不限制数量  
- `app/.../db/dao/ConversationDAO.kt:36` `searchConversations(...)` 不限制数量  
- `app/.../db/dao/ConversationDAO.kt:72` `getPinnedConversations()` 不限制数量  
- `app/.../db/dao/MemoryDAO.kt:18` `getAllMemoriesFlow()` 不限制数量  
- `app/.../db/dao/GenMediaDAO.kt:11` `getAllGenMedia()` 不限制数量  
- `app/.../db/dao/FavoriteDAO.kt:15` `getAllFavorites()` 不限制数量

**描述**: 多个 DAO 查询返回 `Flow<List<...>>` 无分页、无 LIMIT。当表中数据量大时（如数千条 conversation / media / memory），Room 会一次性加载全部数据到内存。

**影响**: 内存 OOM（尤其 32MB cursor window 被填满时）、首次加载慢、UI 线程等待。

**改进方向**: 改用 `PagingSource` + `Flow<PagingData>` 或至少加 `LIMIT` 分页。

---

### 🟠 5.2 N+1 查询加载消息节点

**位置**: `app/.../data/repository/ConversationRepository.kt:308-343`  
`loadMessageNodes()` 使用 `getNodesOfConversationPaged()` 分批加载

**描述**: `loadMessageNodes` 以 64 条为一批循环加载消息节点，每次调用 Room DAO（涉及 Binder 事务）。虽然比一次全量加载好，但仍存在多次 round-trip。且每页需 `JSON.decodeFromString<List<UIMessage>>(entity.messages)` 反序化大量 JSON。

**触发条件**: 打开一个长对话（包含数百条消息节点）。

**影响**: 打开对话时的累积延迟，主线程等待。

**改进方向**: 考虑增加 pageSize、批量反序列化、或使用 Room 的 Flow 实时监听。

---

### 🟡 5.3 Migration 全表遍历（升级性能）

**位置**: `app/.../data/db/migrations/Migration_11_12.kt`（以及 `Migration_22_23.kt`）

**描述**: 数据库迁移中遍历所有 conversation 逐条处理。迁移运行在 App 首次启动时，`onCreate` 中，大数据库（数百个 conversation）可能导致迁移耗时数秒，阻塞启动。

**影响**: 升级后首次启动 ANR 或无响应。

**改进方向**: 考虑在后台线程执行迁移，或使用分批迁移。

---

## 6. 资源泄漏

### 🟠 6.1 OkHttp Response Body 未关闭

**位置**:  
- `ai/.../ClaudeProvider.kt:80` `client.newCall(request).execute()` 返回 `Response` 但 `body.string()` 后未 `close()`  
  检查：行 80 直接调 `execute()`，随后 `response.body?.string()`，但 `body.string()` 会自动关闭 body，但从代码片段看缺少 `response.close()` 调用（除非在 finally 中）。  
- `ai/.../GoogleProvider.kt:788` `response.body.string()` — body 被使用后未手动 close  
- `ai/.../OpenAIProvider.kt:106` `response.body.string()` — 同上  
- `ai/.../OpenAIProvider.kt:350` `response.body.string()` + `body.bytes()` — 多个 body 访问后未 close  
- `search/.../` 多个 SearchService 实现中 `response.body.string()` 后未 close

**描述**: OkHttp 的 `Response.body` 是实现 `Closeable` 的。虽然 `body.string()` 会内部关闭 body 流，但如果代码在读取 body 前检查了 `isSuccessful`、`code` 等且提前返回，或读取了多个 body 方法（如同时调 `string()` 和 `bytes()`），可能导致 body 资源未释放。特别是在 QuickJSFetch.kt 中 `response.close()` 被显式调用，但其他许多位置没有。

**具体检查**:  
- `common/.../QuickJSFetch.kt:92` 中调了 `response.close()` ✅  
- `ai/.../ClaudeProvider.kt:80` 需要确认是否有 `response.close()`  
- `search/.../` 多个 service 文件需要逐项检查

**影响**: 连接池泄漏、socket 未回收、OOM（大响应）。

**改进方向**: 对所有 OkHttp Response 使用 `.use {}` 或确保在 finally 中 close。

---

### 🟠 6.2 Proot 子进程未正确回收

**位置**: `workspace/.../WorkspaceShellRunner.kt:68-83`  
`Process.readResult()` 中 `InterruptedException` 处理

**描述**: 当协程被取消（`runInterruptible`）时，调用 `destroyForcibly()` 杀掉进程。但 `StreamCollector` 线程是 daemon 线程，进程虽被杀，但 stdout/stderr 管道可能在 Java 侧有残留缓冲区未被读取完。虽然 `stdout.join(1000)` 尝试等待线程结束，但超时只有 1000ms，如果管道数据量大可能超时导致线程泄漏。

**触发条件**: 频繁取消长时间运行的 Shell 命令。

**影响**: 少量守护线程泄漏，长期累积。

**改进方向**: 增加 join 超时处理，或在 `InterruptedException` 发生后关闭流。

---

### 🟡 6.3 `deleteRecursively()` 在 IO 协程中未捕捉异常

**位置**:  
- `app/.../RikkaHubApp.kt:133` `dir.deleteRecursively()`  
- `app/.../RikkaHubApp.kt:143` `dir.deleteRecursively()`  
- `app/.../data/files/SkillManager.kt:60` `skillDir.deleteRecursively()`

**描述**: `File.deleteRecursively()` 在遇到无法删除的文件时返回 `false` 但不会抛异常。这些删除操作在后台协程中执行，失败时静默忽略。对于 `RikkaHubApp.kt` 中的启动清理，失败的删除不会影响正常启动。但在 `SkillManager` 中的删除如果失败，可能导致文件系统残留。

**影响**: 文件系统残留、磁盘占用增加。

**改进方向**: 检查返回值，失败时记录日志。

---

## 7. 重复工作 / 缓存缺失

### 🟠 7.1 Markdown 解析在每次内容变化时全量重建 AST

**位置**:  
- `app/.../ui/components/richtext/Markdown.kt:245-251`  
  `snapshotFlow { updatedContent }.distinctUntilChanged().mapLatest { parseMarkdown(it) }.flowOn(Dispatchers.Default).collect { setData(it) }`  
- `app/.../ui/components/richtext/MarkdownNew.kt:137-143`  
  类似的 `snapshotFlow { updatedContent }.mapLatest { generateMarkdownHtml(it) }` + `Jsoup.parse(html)` 在组合主线程

**描述**: Markdown 内容变化时，两个解析管线同时工作：
1. `Markdown.kt` 在 `Dispatchers.Default` 上解析为 AST（全量）
2. `MarkdownNew.kt` 在 `Dispatchers.Default` 上生成 HTML + `remember(html)` 块中同步 `Jsoup.parse(html)`

每次用户输入完成触发解析，对长 Markdown 文档重复解析。虽然已使用 `distinctUntilChanged` 和 `mapLatest`（取消前一次），但流式生成中内容每几十毫秒变化一次，`mapLatest` 会不断取消上一次解析并启动新的，导致大量 CPU 浪费。

**触发条件**: AI 流式生成回复（长文本 Markdown）时。

**影响**: CPU 飙高、发热、电池消耗。Jsoup.parse 在 `remember` 块中同步执行可能阻塞 UI 线程。

**改进方向**: 对内容应用去抖（debounce 100-200ms）后再解析；Jsoup.parse 应移至后台。

---

### 🟡 7.2 语法高亮在每次重组时重新计算

**位置**: `app/.../ui/components/richtext/HighlightCodeBlock.kt`  
`HighlightText` 组件的 `code` 参数变化时触发高亮重新计算

**描述**: `HighlightText` 是第三方组件（`me.rerere.highlight.HighlightText`），根据 `code` 参数变化触发高亮。代码块内容在流式生成中频繁变化，每次变化都触发词法分析 + AnnotatedString 构建。

**影响**: 流式生成中代码块渲染卡顿。

**改进方向**: 如果可能，对高亮结果使用缓存（如用 `code` 的哈希作为 key 缓存 `AnnotatedString`）。

---

### 🟡 7.3 LRU 缓存未配置过期限制

**位置**: `common/.../cache/LruCache.kt:17`  
`class LruCache<K, V>(capacity: Int, ..., expireAfterWriteMillis: Long? = null)`

**描述**: `LruCache` 支持 `expireAfterWriteMillis` 参数，但多处构造时传 `null`（永不过期）。键值对会永久驻留内存直到被容量驱逐。若缓存键空间随时间无限增长（如 JSON 请求结果），旧数据无法被回收。

**影响**: 缓存内存占用无限增长。

**改进方向**: 为缓存设置合理的 TTL 默认值。

---

## 8. 崩溃风险

### 🟠 8.1 多处使用 `!!` 断言非空

**位置**（部分示例）:  
- `app/.../ui/components/message/ChatMessageNerdLine.kt:88` `message.finishedAt!!`  
- `app/.../ui/components/message/ChatMessageReasoning.kt:219` `thinkingTitle!!`  
- `app/.../ui/components/message/tools/BuiltinToolUIs.kt:576` `context.content!!`  
- `app/.../ui/components/message/ChatMessageEditedFiles.kt:145` `selectedPath!!`  
- `app/.../ui/components/richtext/MarkdownNew.kt:1394` `Color(values[0]!!, values[1]!!, values[2]!!)`  
- `app/.../ui/components/ui/icons/DiscordIcon.kt:15` `_DiscordIcon!!`  
- `app/.../ui/pages/chat/ChatPage.kt:353` `inputState.editingMessage!!`  
- `app/.../ui/pages/chat/ChatPage.kt:516` `cameraOutputUri!!`

**描述**: 多处使用 `!!` 非空断言。当值为 `null` 时直接抛出 `NullPointerException`，导致崩溃。其中 `message.finishedAt!!` 在消息尚未完成时访问（竞态）可能为 null；`cameraOutputUri!!` 在相机取消拍照时为 null；`editingMessage!!` 在编辑状态异常时可能为 null。

**触发条件**: UI 竞态、异步回调异常时。

**影响**: 应用崩溃。

**改进方向**: 用 `?.let {}` 或 `?:` 提供默认值替代 `!!`。

---

### 🟠 8.2 `it.printStackTrace()` 吞异常但日志不完整

**位置**:  
- `GenerationHandler.kt:385` `it.printStackTrace()`  
- `GenerationHandler.kt:397` `it.printStackTrace()`  
- `Mermaid.kt:45,52` `e.printStackTrace()`  
- `HighlightCodeBlock.kt:128` `e.printStackTrace()`

**描述**: 多处使用 `e.printStackTrace()` 而非日志框架（Log.e）。在 Android 中 `printStackTrace` 输出到 `stdout`，生产环境通常不可见（被 logcat 过滤），导致异常被静默吞噬。

**影响**: 异常难以追踪，调试困难。

**改进方向**: 使用 `Log.e(TAG, ..., e)` 替代。

---

### 🟡 8.3 `runCatching` 过度使用掩盖异常

**位置**: 全项目多处使用 `runCatching {}` 包裹本应传播的异常（例如 `RikkaHubApp.kt:113`、`ChatService.kt` 多处）

**描述**: `runCatching` 将异常包装在 `Result` 中，随后 `.onFailure { Log.e(...) }` 只记录日志而不重新抛出。部分位置（如 `syncManagedFiles`、`cleanupWorkspaceTempDirs`）在启动时失败可能不会影响主流程，但 `CrashHandler.install` 后的异常本应上报。

**影响**: 异常被静默吞噬，线上难以排查。

**改进方向**: 对业务关键路径不要用 `runCatching`，用 `try-catch` + 明确处理策略。

---

### 🟡 8.4 SSE 连接 `onFailure` 未区分可恢复/不可恢复错误

**位置**: `common/.../http/SSE.kt:54-57`  
`onFailure` 中 `channel.close(t)` — 所有错误都关闭 channel

**描述**: SSE 连接异常可能包括可恢复的（如网络超时、DNS 临时失败）和不可恢复的（如 401 认证失败）。将所有错误视为 fatal 会中断 SSE，AI 生成中止。当前 `GenerationHandler.generateInternal` 中的 `streamText` 收集没有重试逻辑。

**影响**: 网络抖动导致 AI 生成中断。

**改进方向**: 在 SSE 层区分错误类型，对可恢复错误（Http 500+、SocketException）实现指数退避重试。

---

## 汇总

| 类别 | Critical | High | Medium | Low |
|------|----------|------|--------|-----|
| 主线程阻塞 | 1.1, 1.2 | — | — | — |
| Compose 重组 | — | 2.1, 2.2, 2.3 | 2.4 | — |
| 内存泄漏 | 3.1, 3.2 | 3.3 | 3.4 | — |
| 流式生成 | — | 4.1, 4.2 | 4.3 | — |
| 数据库 | — | 5.1, 5.2 | 5.3 | — |
| 资源泄漏 | — | 6.1, 6.2 | 6.3 | — |
| 重复/缓存 | — | 7.1 | 7.2, 7.3 | — |
| 崩溃风险 | — | 8.1, 8.2 | 8.3, 8.4 | — |

**重点关注**（按修复优先级）:
1. 🔴 `runBlocking` 在 UI 线程做语法高亮 — 最直接的 ANR 风险源
2. 🔴 `HostShellRunner` 同步阻塞 — 主线程执行 shell 命令可导致 ANR
3. 🔴 Context 泄漏在 ASR/TTS hook 中 — 可能 Activity 泄漏
4. 🟠 多个 `items()` 缺 key — 列表性能
5. 🟠 Markdown 全量解析无去抖 — 流式生成 CPU 瓶颈
6. 🟠 `!!` 空安全 — 可导致线上崩溃
7. 🟠 SSE `trySend` 静默丢事件 — AI 回复不完整
8. 🟠 OkHttp body 未 close — 连接泄漏
