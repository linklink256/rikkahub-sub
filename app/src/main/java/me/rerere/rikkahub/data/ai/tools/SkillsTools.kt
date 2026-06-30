package me.rerere.rikkahub.data.ai.tools

import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillToolDeclaration
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager

private const val TAG = "SkillsTools"
private const val SKILL_TOOL_PREFIX = "skill__"

/**
 * Regex for safe skill / tool names: letters, digits, hyphens, underscores, dots.
 * Names containing other characters (spaces, semicolons, backticks, `$`, etc.)
 * are rejected to prevent shell injection in [buildSkillToolCommand].
 */
private val SAFE_NAME_REGEX = Regex("[a-zA-Z0-9._-]+")

/** Returns true if [name] is non-blank and contains only shell-safe characters. */
fun isSafeName(name: String): Boolean =
    name.isNotBlank() && SAFE_NAME_REGEX.matches(name)

/**
 * Converts a JSON Schema object (as [JsonElement]) to an [InputSchema.Obj].
 *
 * The input is expected to be a JSON object with:
 * - `"type": "object"` (required for successful conversion)
 * - `"properties": { ... }` (optional map of property definitions, each being a JSON object
 *   with keys like `type`, `description`, etc.)
 * - `"required": [ ... ]` (optional JSON array of required property names, represented as
 *   a JSON object with numeric keys for simplicity, e.g. `{"0": "name", "1": "count"}`)
 *
 * Returns `null` if the input is null, not a JSON Object, or the `"type"` field is not
 * `"object"`.
 */
fun jsonSchemaToInputSchema(json: JsonElement?): InputSchema? {
    if (json == null || json is JsonNull) return null
    // Use safe cast instead of jsonObject (which throws for non-JsonObject).
    // jsonObject is non-nullable in kotlinx-serialization-json 1.11.0, so
    // the previous `?: return null` was dead code that would let exceptions
    // propagate instead of returning null.
    val obj = json as? JsonObject ?: return null
    val typeValue = obj["type"] as? JsonPrimitive
    if (typeValue?.contentOrNull != "object") return null

    val properties: JsonObject = obj["properties"] as? JsonObject ?: buildJsonObject { }
    val required: List<String>? = parseRequired(obj["required"])

    return InputSchema.Obj(
        properties = properties,
        required = required,
    )
}

/**
 * Extracts a list of required property names from a JSON Schema `required` field.
 *
 * Supports two formats:
 * - JSON array: `["name", "count"]` (standard JSON Schema format)
 * - JSON object with numeric keys: `{"0": "name", "1": "count"}` (fallback)
 *
 * Returns `null` if the value is null, not a valid array/object, or empty.
 */
private fun parseRequired(element: JsonElement?): List<String>? {
    if (element == null || element is JsonNull) return null
    return when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .ifEmpty { null }
        is JsonObject -> element.mapNotNull { (_, value) -> (value as? JsonPrimitive)?.contentOrNull }
            .ifEmpty { null }
        else -> null
    }
}

/**
 * Formats a [WorkspaceCommandResult] into a human-readable result string
 * for the AI tool response.
 *
 * This is a pure function, making it directly testable on the JVM.
 *
 * Behaviour:
 * - If [result.timedOut] is true, returns a timeout message regardless of exit code.
 * - If [result.exitCode] != 0, returns a failure message including stderr.
 * - If [result.exitCode] == 0, returns stdout, optionally followed by a [stderr] section
 *   if stderr is non-empty.
 */
fun formatToolResult(result: WorkspaceCommandResult, timeoutMillis: Long): String {
    if (result.timedOut) {
        return "Tool execution timed out after ${timeoutMillis}ms."
    }
    return if (result.exitCode != 0) {
        val stderrSection = if (result.stderr.isNotBlank()) {
            "\n[stderr]\n${result.stderr}"
        } else {
            ""
        }
        val stdoutSection = if (result.stdout.isNotBlank()) {
            "\n[stdout]\n${result.stdout}"
        } else {
            ""
        }
        "Tool failed (exit ${result.exitCode}).${stdoutSection}${stderrSection}"
    } else {
        if (result.stderr.isNotBlank()) {
            "${result.stdout}\n[stderr]\n${result.stderr}"
        } else {
            result.stdout
        }
    }
}

/**
 * Builds the shell command for executing a skill tool.
 *
 * The skill directory is bind-mounted at `/skills/<skillName>` inside the
 * proot sandbox (via [WorkspaceBindMount] in RepositoryModule). We cannot use
 * the `cwd` parameter of `WorkspaceRepository.executeCommand` because that is
 * resolved on the **host** filesystem (`{workspaceDir}/files/<cwd>`) where the
 * skills directory does not exist. Instead we prefix a `cd` into the skill
 * directory so the command runs with the correct working directory inside the
 * sandbox.
 *
 * This is a pure function, making it directly testable on the JVM.
 */
fun buildSkillToolCommand(skillName: String, rawCommand: String): String {
    // skillName is validated by isSafeName() before this function is called,
    // but we quote the path as defence-in-depth against injection.
    return "cd \"/skills/$skillName\" && $rawCommand"
}

/**
 * Executes a skill tool declaration in the workspace sandbox.
 *
 * The command defined by [decl] is executed in the workspace identified by [workspaceId].
 * The working directory is set to the skill's directory (`/skills/<skillName>`) by
 * prefixing a `cd` command — see [buildSkillToolCommand] for why `cwd` is not used.
 * The input [args] (JSON element) is passed as stdin to the command.
 *
 * @throws Exception if the workspace command execution fails unexpectedly.
 */
suspend fun executeSkillTool(
    workspaceId: String,
    skillName: String,
    decl: SkillToolDeclaration,
    skillManager: SkillManager,
    workspaceRepository: WorkspaceRepository,
    args: JsonElement,
): List<UIMessagePart> {
    val rawCommand = decl.execute.command
    if (rawCommand == null) {
        return "Tool failed: command is null for shell-type tool".asToolResult()
    }

    // Security audit: resolve the script path to verify it doesn't escape the skill directory.
    // Pure commands (e.g. "gh repo list" with no script path) return null from resolveSkillScript,
    // which is allowed — the command will still execute.
    val resolvedScript = skillManager.resolveSkillScript(skillName, rawCommand)
    if (resolvedScript != null && !resolvedScript.exists()) {
        // The path is syntactically valid and stays inside the skill directory,
        // but the file doesn't exist. We log a warning and let the execution
        // proceed — the shell will report the error naturally.
        android.util.Log.w(TAG, "executeSkillTool: script not found: ${resolvedScript.absolutePath}")
    }

    val command = buildSkillToolCommand(skillName, rawCommand)
    val stdin = args.toString().toByteArray()
    val timeoutMillis = (decl.execute.timeoutMillis ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS)
        .coerceAtMost(600_000L)

    val result = workspaceRepository.executeCommand(
        id = workspaceId,
        command = command,
        cwd = "",   // workspace root; working dir is set via `cd` in the command
        timeoutMillis = timeoutMillis,
        stdin = stdin,
    )

    val text = formatToolResult(result, timeoutMillis)
    return listOf(UIMessagePart.Text(text))
}

/**
 * Executes a JavaScript skill tool using QuickJS.
 *
 * Reads the JS file at [decl.execute.entry] (relative to the skill directory),
 * evaluates it, calls the exported function (default `"main"`), and returns
 * the result along with any console output.
 *
 * This function does **not** require a workspace — JS tools run in-process
 * inside the QuickJS sandbox.
 *
 * @param skillName the skill name (for file resolution).
 * @param decl the tool declaration (must have type == "javascript").
 * @param skillManager used to resolve the JS entry file path.
 * @param args the tool's JSON input arguments (passed as JSON string to the JS function).
 * @return a single-element list containing the result JSON as [UIMessagePart.Text].
 */
suspend fun executeJsSkillTool(
    skillName: String,
    decl: SkillToolDeclaration,
    skillManager: SkillManager,
    args: JsonElement,
): List<UIMessagePart> {
    val entryPath = decl.execute.entry
    if (entryPath.isNullOrBlank()) {
        return listOf(UIMessagePart.Text("""{"error": "entry is required for javascript tools"}"""))
    }

    val jsFile = skillManager.resolveSkillFile(skillName, entryPath)
    if (jsFile == null) {
        return listOf(UIMessagePart.Text("""{"error": "Entry file '$entryPath' not found or path escapes skill directory"}"""))
    }
    if (!jsFile.exists()) {
        return listOf(UIMessagePart.Text("""{"error": "Entry file '$entryPath' not found"}"""))
    }

    val jsCode = try {
        jsFile.readText()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "executeJsSkillTool: Failed to read $entryPath", e)
        return listOf(UIMessagePart.Text("""{"error": "Failed to read entry file: ${e.message}"}"""))
    }

    val logs = arrayListOf<String>()
    val context = QuickJSContext.create()
    var fn: JSFunction? = null

    try {
        context.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) {
                logs.add("[LOG] $info")
            }

            override fun info(info: String?) {
                logs.add("[INFO] $info")
            }

            override fun warn(info: String?) {
                logs.add("[WARN] $info")
            }

            override fun error(info: String?) {
                logs.add("[ERROR] $info")
            }
        })

        context.evaluate(jsCode)

        val functionName = decl.execute.`function` ?: "main"
        fn = context.globalObject.getJSFunction(functionName)
        if (fn == null) {
            return listOf(UIMessagePart.Text("""{"error": "Function '$functionName' not found in '$entryPath'"}"""))
        }

        val result = fn.call(args.toString())
        val payload = buildJsonObject {
            if (logs.isNotEmpty()) {
                put("logs", JsonPrimitive(logs.joinToString("\n")))
            }
            put(
                key = "result",
                element = when (result) {
                    null -> JsonNull
                    is QuickJSObject -> JsonPrimitive(result.stringify())
                    else -> JsonPrimitive(result.toString())
                }
            )
        }
        return listOf(UIMessagePart.Text(payload.toString()))
    } finally {
        fn?.release()
        context.destroy()
    }
}

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
    workspaceRepository: WorkspaceRepository? = null,
    workspaceId: String? = null,
): List<Tool> {
    val available = allSkills
    me.rerere.common.android.Logging.log(
        "SkillTools",
        "createSkillTools: enabledSkills=${enabledSkills.size} (${enabledSkills.joinToString()}), " +
            "allSkills=${allSkills.size} (${allSkills.joinToString { it.name }}), " +
            "available=${available.size}, workspaceId=$workspaceId"
    )
    if (available.isEmpty()) return emptyList()

    val tools = mutableListOf<Tool>()

    // 1) Always register the use_skill tool (read SKILL.md instructions)
    tools.add(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        appendLine("    <name>${skill.name}</name>")
                        appendLine("    <description>${skill.description}</description>")
                        if (!skill.compatibility.isNullOrBlank()) {
                            appendLine("    <compatibility>${skill.compatibility}</compatibility>")
                        }
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    skillManager.readSkillBody(name)
                        ?: error("Skill '$name' not found")
                } else {
                    val target = skillManager.resolveSkillFile(name, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )

    // 2) Register executable tools from skills' tools.yaml.
    //    JS tools (type=javascript) don't need a workspace — they run in-process
    //    with QuickJS. Shell tools still require workspaceId + workspaceRepository.
    val workspaceAvailable = workspaceId != null && workspaceRepository != null
    for (skill in available) {
        if (!isSafeName(skill.name)) {
            android.util.Log.w(TAG, "createSkillTools: skipping skill '${skill.name}' — name contains unsafe characters")
            continue
        }
        val declarations = skillManager.listToolDeclarations(skill.name)
        android.util.Log.i(TAG, "createSkillTools: skill '${skill.name}' declared ${declarations.size} tool(s)")
        me.rerere.common.android.Logging.log(
            "SkillTools",
            "createSkillTools: skill '${skill.name}' → ${declarations.size} tool declaration(s)"
        )
        for (decl in declarations) {
            if (!isSafeName(decl.name)) {
                android.util.Log.w(TAG, "createSkillTools: skipping tool '${decl.name}' in skill '${skill.name}' — name contains unsafe characters")
                continue
            }

            val isJsTool = decl.execute.type == "javascript"

            // Shell tools require workspace; JS tools do not
            if (!isJsTool && !workspaceAvailable) continue

            val toolName = "${SKILL_TOOL_PREFIX}${skill.name}-${decl.name}"
            val toolDescription = decl.description
            val toolParameters = decl.parameters
            val needsApprovalFlag = decl.execute.needsApproval

            tools.add(
                Tool(
                    name = toolName,
                    description = toolDescription,
                    parameters = { jsonSchemaToInputSchema(toolParameters) },
                    needsApproval = { needsApprovalFlag },
                    execute = { args ->
                        if (isJsTool) {
                            executeJsSkillTool(
                                skillName = skill.name,
                                decl = decl,
                                skillManager = skillManager,
                                args = args,
                            )
                        } else {
                            executeSkillTool(
                                workspaceId = workspaceId!!,
                                skillName = skill.name,
                                decl = decl,
                                skillManager = skillManager,
                                workspaceRepository = workspaceRepository!!,
                                args = args,
                            )
                        }
                    },
                )
            )
        }
    }

    return tools
}

// Extension needed for JsonPrimitive.contentOrNull used in jsonSchemaToInputSchema
private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null
