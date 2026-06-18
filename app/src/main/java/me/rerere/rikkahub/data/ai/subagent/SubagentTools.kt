package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 创建 subagent 相关工具（移植自 kimi-code 的 collaboration/agent.ts + agent-swarm.ts）。
 *
 * 工具集与具体业务解耦：实际"派生子代理"的逻辑由 [spawn] 回调提供（由 ChatService 实现，
 * 内部调用 [SubagentHost.spawn] 并负责按深度递归构建子代理工具）。本函数只负责把工具
 * schema / 描述 / 参数解析 / 结果序列化做好。
 *
 * 关键设计（让子代理容易被调用）：
 * - 工具描述包含明确的"何时该用"触发信号（参考 kimi-code agent.md / system.md）
 * - systemPrompt 注入强引导语，给出具体场景阈值
 * - 负面引导克制（不说"不要用于简单任务"，而是说"简单任务可直接做"）
 *
 * @param profiles 父代理可用的 subagent 配置档列表（用于枚举 profile_name）
 * @param json     用于序列化结果的 Json
 * @param spawn    派生子代理的回调：(profileName, task, description) -> [SubagentResult]
 * @param askBtw   轻量"顺便问一句"回调（无工具、纯文本，对应 kimi-code startBtw）
 */
fun createSubagentTools(
    profiles: List<SubagentProfile>,
    json: Json,
    spawn: suspend (profileName: String, task: String, description: String) -> SubagentResult,
    askBtw: suspend (question: String) -> String,
): List<Tool> {
    if (profiles.isEmpty()) return emptyList()

    val profileNames = profiles.map { it.name }
    val profileListText = profiles.joinToString("\n") { "  - ${it.name}: ${it.description}" }

    val spawnTool = Tool(
        name = "spawn_subagent",
        description = """
            Launch a subagent to handle a task autonomously. The subagent runs its own tool loop with a fresh context and reports back a summary.

            Writing the task prompt:
            - The subagent starts with ZERO context — it has not seen this conversation. Brief it like a colleague who just walked into the room: state the goal, list what you already know, hand over the specifics.
            - Lookups (read this file, search for X): put the exact path or query in the prompt. The subagent should not have to search for things you already know.
            - Investigations (figure out X, find why Y): give the question, not prescribed steps — fixed steps become dead weight when the premise is wrong.

            When to USE this tool (reach for it proactively):
            - Research or exploration that will clearly need MORE than 2-3 search queries or file reads.
            - Multi-step tasks with a clear, self-contained goal (write a script, review a document, analyze data).
            - When you want to parallelize: spawn multiple subagents for independent sub-tasks instead of doing them sequentially.
            - When the task would bloat your own context with intermediate details you don't need to keep.
            - Code review, critique, or second opinion on a substantial artifact.

            When you can SKIP this tool (do it directly):
            - Reading a file whose path you already know.
            - A single quick search or calculation.
            - Answering from knowledge you already have.

            Available subagent profiles:
$profileListText

            The subagent's result is only visible to you, not to the user. When the user needs to see what a subagent produced, summarize the relevant parts in your own reply.
        """.trimIndent(),
        systemPrompt = { _, _ ->
            buildString {
                appendLine()
                appendLine("**Subagents — Delegation Guidance**")
                appendLine("You have the `spawn_subagent` tool to delegate work to specialized subagents. Each subagent runs autonomously with its own tool loop and fresh context, then returns a summary.")
                appendLine()
                appendLine("**When to delegate (be proactive):**")
                appendLine("- Use `spawn_subagent` for research or exploration that needs more than 2-3 searches/reads — delegate instead of cluttering your own context with intermediate steps.")
                appendLine("- Use it for multi-step, self-contained tasks: writing code, reviewing documents, analyzing data, investigating a question across multiple sources.")
                appendLine("- Use it to parallelize: if a task has independent parts, spawn multiple subagents rather than doing them one by one.")
                appendLine("- Use it when a task would consume a lot of context with details you don't need to retain.")
                appendLine()
                appendLine("**When NOT to delegate:**")
                appendLine("- Single file reads, one quick search, or simple calculations — just do them directly.")
                appendLine("- Anything you can answer from context you already have.")
                appendLine()
                appendLine("**How to delegate well:**")
                appendLine("- The subagent sees NONE of your conversation. Include the goal, relevant context, and specifics (file paths, search terms, known facts) in the task prompt.")
                appendLine("- Give the question, not step-by-step instructions — let the subagent figure out the approach.")
                appendLine()
                appendLine("<available_subagent_profiles>")
                profiles.forEach { p ->
                    appendLine("  <profile>")
                    appendLine("    <name>${p.name}</name>")
                    appendLine("    <description>${p.description}</description>")
                    appendLine("  </profile>")
                }
                append("</available_subagent_profiles>")
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("profile_name", buildJsonObject {
                        put("type", "string")
                        put("description", "The subagent profile to spawn. Pick the one whose specialty best matches the task.")
                        put(
                            "enum",
                            kotlinx.serialization.json.buildJsonArray {
                                profileNames.forEach { add(it) }
                            }
                        )
                    })
                    put("task", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Full, self-contained task prompt for the subagent. The subagent has NOT seen this conversation — include the goal, known facts, file paths, and any specifics needed."
                        )
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Short 3-5 word description of this delegation for display (optional)")
                    })
                },
                required = listOf("profile_name", "task"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val profileName = params["profile_name"]?.jsonPrimitive?.contentOrNull
                ?: error("profile_name is required")
            val task = params["task"]?.jsonPrimitive?.contentOrNull
                ?: error("task is required")
            val description = params["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val result = spawn(profileName, task, description)
            val payload = buildJsonObject {
                put("profile_name", JsonPrimitive(result.profileName))
                put("succeeded", JsonPrimitive(result.succeeded))
                if (!result.error.isNullOrBlank()) {
                    put("error", JsonPrimitive(result.error))
                }
                put("summary", JsonPrimitive(result.summary))
                put("depth", JsonPrimitive(result.depth))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val btwTool = Tool(
        name = "ask_btw",
        description = """
            Ask a lightweight, tool-less side question to a fresh agent instance.
            Use this to get a quick second opinion or sanity check without spinning up a full
            subagent. The side agent has NO tools and answers from its own knowledge only.
            It does not see your conversation history; provide all necessary context in the question.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("question", buildJsonObject {
                        put("type", "string")
                        put("description", "The self-contained side question to ask (include all needed context)")
                    })
                },
                required = listOf("question"),
            )
        },
        execute = { args ->
            val question = args.jsonObject["question"]?.jsonPrimitive?.contentOrNull
                ?: error("question is required")
            val answer = askBtw(question)
            val payload = buildJsonObject {
                put("answer", JsonPrimitive(answer))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    return listOf(spawnTool, btwTool)
}

/** subagent 工具名集合，便于在构建子代理工具时排除 / 判断递归。 */
val SUBAGENT_TOOL_NAMES: Set<String> = setOf("spawn_subagent", "ask_btw")
