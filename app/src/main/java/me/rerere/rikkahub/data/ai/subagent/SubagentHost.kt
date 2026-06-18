package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "SubagentHost"

/**
 * 当摘要过短时，追问子代理扩写的一段提示词。
 * 移植自 kimi-code 的 summary-continuation.md。
 */
private val SUMMARY_CONTINUATION_PROMPT = """
Your previous response was too brief for the parent agent to act on.
Please expand your summary: include the key findings, actions taken, outcomes, and any important
details the parent agent needs. Keep it focused and structured, but make it complete enough that
the parent agent does not need to re-run your work.
""".trimIndent()

/** 永远返回 false 的审批策略 —— 子代理自主运行不触发 HITL。 */
private val NO_APPROVAL: (JsonElement) -> Boolean = { false }

/**
 * Subagent 宿主 —— 移植自 kimi-code 的 SessionSubagentHost。
 *
 * 它负责把一个 [SubagentProfile] + 任务描述，编译成一个子 [Assistant]，
 * 复用 [GenerationHandler] 跑一段独立的 generation 循环，并把最终摘要返回。
 *
 * 与 kimi-code 的核心差异：
 * - kimi-code 通过 Session / Agent / Turn 体系管理子代理生命周期；
 *   RikkaHub 没有 Session 抽象，这里直接用一次完整的 [GenerationHandler.generateText]
 *   作为子代理"运行到完成"的等价物。
 * - 子代理的工具由调用方通过 [buildChildTools] 提供（通常在 ChatService 里复用父代理的
 *   工具构建逻辑，并按深度裁剪 / 沙箱化），从而避免本类与具体工具依赖耦合。
 *
 * @param generationHandler 复用的生成处理器
 */
class SubagentHost(
    private val generationHandler: GenerationHandler,
) {
    /**
     * 派生一个子代理并运行到完成。
     *
     * @param profile         子代理配置档
     * @param task            交给子代理的任务描述（作为第一条 user 消息）
     * @param settings        当前设置（用于解析模型 / provider）
     * @param parentAssistant 父代理 Assistant（用于继承未显式覆盖的配置）
     * @param parentModel     父代理当前模型
     * @param buildChildTools 给定 (childAssistant, depth) 返回子代理可用工具列表
     * @param depth           当前递归深度（0 = 直接子代理）
     * @param maxDepth        允许的最大子代理嵌套深度
     * @param onProgress      可选的进度回调（每收到一批消息时触发，便于 UI 展示子代理流）
     * @return 子代理运行结果（含摘要 / 是否成功 / 错误信息 / token 用量 / 步数 / transcript）
     */
    suspend fun spawn(
        profile: SubagentProfile,
        task: String,
        settings: Settings,
        parentAssistant: Assistant,
        parentModel: Model,
        buildChildTools: suspend (childAssistant: Assistant, depth: Int) -> List<Tool>,
        depth: Int = 0,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        onProgress: ((List<UIMessage>) -> Unit)? = null,
    ): SubagentResult {
        if (depth >= maxDepth) {
            return SubagentResult(
                profileName = profile.name,
                summary = "",
                succeeded = false,
                error = "Subagent recursion depth limit reached ($maxDepth)",
                depth = depth,
            )
        }

        // 解析子代理模型：profile 指定 > 父代理模型
        val childModel = profile.chatModelId
            ?.let { settings.findModelById(it) }
            ?: parentModel

        val childAssistant = buildChildAssistant(profile, parentAssistant)
        val rawChildTools = runCatching {
            buildChildTools(childAssistant, depth)
        }.getOrElse {
            Log.w(TAG, "spawn: buildChildTools failed: ${it.message}")
            emptyList()
        }
        // 应用 profile.excludedTools：按工具名剔除（例如 reviewer 配置为只读，
        // 排除 workspace_write_file / workspace_shell 等）。此前该字段未被应用，是一处遗漏。
        val childTools = if (profile.excludedTools.isEmpty()) {
            rawChildTools
        } else {
            rawChildTools.filter { it.name !in profile.excludedTools }
        }

        var totalUsage: TokenUsage? = null
        var steps = 0

        return runCatching {
            Log.i(TAG, "spawn: subagent '${profile.name}' (depth=$depth) started")

            // 第 1 轮：直接以任务作为 user 消息启动
            var messages = listOf(UIMessage.user(task))
            var run = runToCompletion(
                profile = profile,
                settings = settings,
                model = childModel,
                assistant = childAssistant,
                tools = childTools,
                initialMessages = messages,
                onProgress = onProgress,
            )
            steps += 1
            totalUsage = mergeUsage(totalUsage, run.usage)
            messages = run.messages

            // 摘要过短 → 追问扩写（移植自 kimi-code waitForChildCompletion 的 continuation 逻辑）
            var summary = run.summary
            var remainingContinuations = profile.summaryContinuationAttempts
            while (remainingContinuations > 0 && summary.length < profile.summaryMinLength) {
                remainingContinuations -= 1
                val continuationMessages = messages + UIMessage.user(SUMMARY_CONTINUATION_PROMPT)
                run = runToCompletion(
                    profile = profile,
                    settings = settings,
                    model = childModel,
                    assistant = childAssistant,
                    tools = childTools,
                    initialMessages = continuationMessages,
                    onProgress = onProgress,
                )
                steps += 1
                totalUsage = mergeUsage(totalUsage, run.usage)
                messages = run.messages
                summary = run.summary
            }

            // 从完整消息列表构建 transcript，用于 UI 嵌套可视化
            val transcript = buildTranscript(messages)

            val result = SubagentResult(
                profileName = profile.name,
                summary = summary.ifBlank { "(subagent produced no textual summary)" },
                succeeded = true,
                depth = depth,
                usage = totalUsage,
                steps = steps,
                transcript = transcript,
            )
            logResult(result)
            result
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(TAG, "spawn: subagent '${profile.name}' failed: ${it.message}", it)
        }.getOrElse {
            SubagentResult(
                profileName = profile.name,
                summary = "",
                succeeded = false,
                error = it.message ?: it.javaClass.name,
                depth = depth,
                usage = totalUsage,
                steps = steps,
            )
        }
    }

    /**
     * 跑一次完整的 generation 循环到结束，返回最终消息列表 + 末条 assistant 文本 + 累计 usage。
     *
     * 注意：[GenerationHandler.generateText] 没有独立的 stream 开关，流式与否由
     * [Assistant.streamOutput] 控制；子代理默认 streamOutput=false，结果一次性产出。
     */
    private suspend fun runToCompletion(
        profile: SubagentProfile,
        settings: Settings,
        model: Model,
        assistant: Assistant,
        tools: List<Tool>,
        initialMessages: List<UIMessage>,
        onProgress: ((List<UIMessage>) -> Unit)?,
    ): RunCompletion {
        val finalMessages = generationHandler.generateText(
            settings = settings,
            model = model,
            messages = initialMessages,
            assistant = assistant,
            tools = tools,
            maxSteps = profile.maxSteps.coerceIn(1, 256),
            memories = emptyList(),
        ).onEach { chunk ->
            if (chunk is GenerationChunk.Messages) {
                onProgress?.invoke(chunk.messages)
            }
        }.fold(initialMessages) { _, chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> chunk.messages
            }
        }

        return RunCompletion(
            messages = finalMessages,
            summary = lastAssistantText(finalMessages),
            usage = accumulateUsage(finalMessages),
        )
    }

    /**
     * 根据 profile + 父代理，构造子代理 Assistant。
     * 未在 profile 显式设置的项，回退到父代理的值（温度 / thinking 等），
     * 这与 kimi-code configureChild 的"继承父代理模型"语义一致。
     */
    private fun buildChildAssistant(
        profile: SubagentProfile,
        parent: Assistant,
    ): Assistant {
        // 子代理默认关闭交互式 / 持久化功能，保证它是一次性的、自主的、不污染父对话。
        val localTools = buildList {
            if (profile.inheritTools) {
                addAll(parent.localTools)
            }
            addAll(profile.extraLocalTools)
            // ask_user 在子代理中无意义（不应打断用户），默认排除
            removeAll { it == LocalToolOption.AskUser }
        }.distinct()

        return parent.copy(
            id = Uuid.random(), // 子代理是独立实例，不复用父代理 id（避免记忆串扰）
            name = profile.displayName,
            systemPrompt = profile.systemPrompt,
            temperature = profile.temperature ?: parent.temperature,
            topP = profile.topP ?: parent.topP,
            maxTokens = profile.maxTokens ?: parent.maxTokens,
            reasoningLevel = profile.reasoningLevel,
            contextMessageSize = 0, // 子代理从空上下文开始
            streamOutput = profile.streamOutput || parent.streamOutput,
            enableMemory = profile.enableMemory,
            useGlobalMemory = false,
            enableRecentChatsReference = false,
            allowConversationSystemPrompt = false,
            allowConversationPromptInjection = false,
            enableTimeReminder = false,
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            // 子代理的工具以 generateText(tools=...) 为准；这里保留 localTools 仅供
            // GenerationHandler 内部 memory 工具判断，真正工具集由 buildChildTools 注入。
            localTools = localTools,
            mcpServers = emptySet(), // 子代理不直接挂 MCP（避免重复 / 权限问题）
            enabledSkills = emptySet(), // 子代理不自动启用 skills（如需可由 buildChildTools 注入）
            presetMessages = emptyList(),
            quickMessageIds = emptySet(),
            regexes = emptyList(),
            customHeaders = parent.customHeaders,
            customBodies = parent.customBodies,
        )
    }

    private fun lastAssistantText(messages: List<UIMessage>): String {
        for (message in messages.asReversed()) {
            if (message.role != MessageRole.ASSISTANT) continue
            val text = message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
            if (text.isNotBlank()) return text.trim()
        }
        return ""
    }

    private fun accumulateUsage(messages: List<UIMessage>): TokenUsage? {
        var acc: TokenUsage? = null
        for (message in messages) {
            val u = message.usage ?: continue
            acc = acc.merge(u)
        }
        return acc
    }

    private fun mergeUsage(acc: TokenUsage?, other: TokenUsage?): TokenUsage? {
        if (acc == null) return other
        if (other == null) return acc
        return acc.merge(other)
    }

    private fun logResult(result: SubagentResult) {
        val u = result.usage
        if (u != null) {
            Log.i(
                TAG,
                "spawn: subagent '${result.profileName}' (depth=${result.depth}) " +
                    "finished in ${result.steps} step(s); " +
                    "tokens: prompt=${u.promptTokens}, completion=${u.completionTokens}, " +
                    "cached=${u.cachedTokens}, total=${u.totalTokens}"
            )
        } else {
            Log.i(
                TAG,
                "spawn: subagent '${result.profileName}' (depth=${result.depth}) " +
                    "finished in ${result.steps} step(s); tokens: n/a"
            )
        }
    }

    private data class RunCompletion(
        val messages: List<UIMessage>,
        val summary: String,
        val usage: TokenUsage?,
    )

    companion object {
        private const val DEFAULT_MAX_DEPTH = 2

        /**
         * 从子代理的完整消息列表构建 UI 可视化用的 transcript。
         * 公开供 ChatService 在 onProgress 回调中构建实时部分 transcript。
         */
        fun buildTranscript(messages: List<UIMessage>): List<SubagentTranscriptStep> {
            val steps = mutableListOf<SubagentTranscriptStep>()
            for (message in messages) {
                if (message.role != MessageRole.ASSISTANT) continue
                for (part in message.parts) {
                    when (part) {
                        is UIMessagePart.Reasoning -> {
                            if (part.reasoning.isNotBlank()) {
                                steps.add(
                                    SubagentTranscriptStep.Reasoning(
                                        text = part.reasoning,
                                        createdAt = part.createdAt.toEpochMilliseconds(),
                                    )
                                )
                            }
                        }

                        is UIMessagePart.Tool -> {
                            val outputText = part.output
                                .filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text }
                            steps.add(
                                SubagentTranscriptStep.ToolCall(
                                    name = part.toolName,
                                    input = part.input,
                                    output = outputText,
                                    executed = part.isExecuted,
                                )
                            )
                        }

                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                steps.add(SubagentTranscriptStep.Text(part.text.trim()))
                            }
                        }

                        else -> {}
                    }
                }
            }
            return steps
        }

        /**
         * 给定一批工具，把它们"沙箱化"为子代理可用版本：
         * 关闭 needsApproval（子代理自主运行，不触发 HITL 审批打断循环）。
         * 对应 kimi-code 中子代理自主工具执行（非 btw 场景）的语义。
         */
        fun sandboxToolsForSubagent(tools: List<Tool>): List<Tool> = tools.map { tool ->
            tool.copy(needsApproval = NO_APPROVAL)
        }
    }
}
