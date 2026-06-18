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

    val spawnTool = Tool(
        name = "spawn_subagent",
        description = """
            Spawn a subagent to autonomously complete a delegated task and return a summary.
            Use this to delegate well-scoped, self-contained work (research, coding, review, etc.)
            so you can parallelize or isolate context. The subagent runs its own tool loop and
            reports back a textual summary; you should act on that summary.

            Available subagent profiles:
${profiles.joinToString("\n") { "  - ${it.name}: ${it.description}" }}

            Rules:
            - Pick the profile whose specialty best matches the task.
            - Give a clear, self-contained task description; the subagent will NOT see your
              conversation history.
            - Do not use this for trivial questions you can answer directly.
            - The subagent is autonomous: it will not ask the user questions.
        """.trimIndent(),
        systemPrompt = { _, _ ->
            buildString {
                appendLine("**Subagents**")
                appendLine("You can delegate work to specialized subagents via the `spawn_subagent` tool.")
                appendLine("<available_subagent_profiles>")
                profiles.forEach { p ->
                    appendLine("  <profile>")
                    appendLine("    <name>${p.name}</name>")
                    appendLine("    <description>${p.description}</description>")
                    appendLine("  </profile>")
                }
                appendLine("</available_subagent_profiles>")
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("profile_name", buildJsonObject {
                        put("type", "string")
                        put("description", "The subagent profile to spawn")
                        put(
                            "enum",
                            kotlinx.serialization.json.buildJsonArray {
                                profileNames.forEach { add(it) }
                            }
                        )
                    })
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "A clear, self-contained task for the subagent to complete")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Short human-readable description of this delegation (optional)")
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
                        put("description", "The self-contained side question to ask")
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
