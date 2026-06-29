package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolAnnotations
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillConstants
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import android.content.Context
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/// 只读 shell 规则集缓存：assets/shell/readonly_rules.json 在构建后不变、Context 在进程内不变，
/// 用 lazy 一次性加载，避免每次 read_shell 调用都读 assets + 解析 JSON。
private val cachedReadonlyRules: ReadonlyShellRules by lazy {
    loadReadonlyShellRules(getKoin().get<Context>())
}

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to false,
    "workspace_read_shell" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
    fun isApprovalExplicitlyDisabled(name: String): Boolean = approvalOverrides[name] == false

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, ::isApprovalExplicitlyDisabled, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, ::isApprovalExplicitlyDisabled, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createReadShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

/** 只读 workspace 工具集（read_file / shell），用于纯决策模式下保留主代理的读取能力 */
suspend fun createWorkspaceReadOnlyTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        // 只读模式只给受限读 shell（白名单只读命令），不再给全功能 workspace_shell
        createReadShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    annotations = ToolAnnotations(readOnlyHint = true),
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    isApprovalExplicitlyDisabled: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") },
    annotations = ToolAnnotations(destructiveHint = true),
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    isApprovalExplicitlyDisabled: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") },
    annotations = ToolAnnotations(destructiveHint = true),
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

/**
 * 结构性只读 shell 工具：复用 [createShellTool] 的执行内核，但以**白名单**拦截命令——
 * 只放行公认只读的命令，默认拒绝其它。判定规则数据驱动，来自 [ReadonlyShellRules]
 * （assets/shell/readonly_rules.json），调整只读边界只改该 JSON、不动代码。
 *
 * 与黑名单的区别：黑名单（禁 rm/sed -i/echo > 等）会被 `python -c "open(...,'w')..."`、
 * `node -e "fs.writeFileSync(...)"` 等执行代码的命令绕过；白名单默认拒绝一切不在列表的
 * 命令，因此 `python/node/perl/ruby/awk …` 被默认拒，是真正的结构性只读边界（非威慑）。
 *
 * 借鉴 Roo-Code mode `groups` / Claude Code explore 的工具集裁剪思路：权限靠工具可用性
 * 而非提示词散文约束。只读子代理（explore/reviewer）经 excludedTools 排除全功能
 * `workspace_shell`、只留 `workspace_read_shell`，从而**不可能**越权改码。
 *
 * 判定顺序（与 [ReadonlyShellRules] 文档一致）：
 * 1. 空 → 拒。2. 含禁字面量 → 拒。3. 命中禁正则 → 拒。
 * 4. 分割子命令，每段 base 不在白名单 → 拒；再查 [PerCommandRule] 专属规则。
 *
 * @param rules  只读规则集（由调用方经 [loadReadonlyShellRules] 从 assets 加载）
 * @param command 待判定命令
 * @return 拒绝原因；null 表示放行
 */
internal fun checkReadonlyCommand(rules: ReadonlyShellRules, command: String): String? {
    if (command.isBlank()) return "read-only shell: empty command"

    // 2) 禁字面量（命令替换 $() / 反引号 / 追加重定向 >> / 强制覆盖 >|）
    for (literal in rules.rejectIfContainsLiteral) {
        if (command.contains(literal)) {
            return "read-only shell: blocked by literal rule: ${literal.take(20)}"
        }
    }
    // 3) 禁正则（如 shell 输出重定向 > file）
    for (pattern in rules.rejectIfRegex) {
        if (Regex(pattern).containsMatchIn(command)) {
            return "read-only shell: blocked by pattern rule"
        }
    }

    // 4) 按复合命令分隔符切子命令，逐段判定
    val subCommands = command.split(Regex("""&&|\|\||;|\|"""))
    for (sub in subCommands) {
        val tokens = sub.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) continue
        val base = tokens.first().substringAfterLast('/')

        // 4a) base 白名单（默认拒绝）
        if (base !in rules.allowedBaseCommands) {
            return "read-only shell: command not in read-only allowlist: $base"
        }
        // 4b) 该 base 的专属规则
        val rule = rules.perCommandRules[base] ?: continue
        if (tokens.any { t -> rule.rejectIfAnyFlagEquals.any { t == it } }) {
            return "read-only shell: $base blocked by flag-equals rule"
        }
        if (tokens.any { t -> rule.rejectIfAnyFlagStartsWith.any { t.startsWith(it) } }) {
            return "read-only shell: $base blocked by flag-startswith rule"
        }
        if (tokens.any { t -> rule.rejectIfAnyFlagContains.any { t.contains(it) } }) {
            return "read-only shell: $base blocked by flag-contains rule"
        }
        if (rule.rejectIfSubcommandContains.any { sub.contains(it) }) {
            return "read-only shell: $base blocked by subcommand-contains rule"
        }
        if (rule.allowedSubcommands.isNotEmpty()) {
            val subToken = tokens.drop(1).firstOrNull { !it.startsWith("-") }
            if (subToken != null && subToken !in rule.allowedSubcommands) {
                return "read-only shell: $base subcommand not in allowlist: $base $subToken"
            }
        }
    }
    return null
}

private fun createReadShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_read_shell",
    description = buildString {
        append("Run a READ-ONLY shell command in the assistant's bound workspace Rootfs. ")
        append("Only read-only commands are allowed (cat, ls, grep, find, head, tail, wc, git status/log/diff, etc.); ")
        append("writing (rm, sed -i, echo >, cp, mv, mkdir, tee, python -c, node -e, ...) is blocked. ")
        append("The workspace files area is mounted at /workspace. Use cwd for a path relative to it. ")
        append("Use this for investigation without the risk of modifying the workspace. ")
        if (!defaultCwd.isNullOrBlank()) append("Defaults to '$defaultCwd'. ")
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Read-only shell command to run (writes are blocked)")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS.")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_read_shell") },
    annotations = ToolAnnotations(readOnlyHint = true),
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        // 规则集从 assets 加载（数据驱动）；getKoin 拿 androidContext 注册的 Context。
        val reject = checkReadonlyCommand(cachedReadonlyRules, command)
        if (reject != null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exitCode", -1)
                        put("stdout", "")
                        put("stderr", reject)
                        put("timedOut", false)
                    }.toString()
                )
            )
        }
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        append("Do NOT execute scripts under /skills/ directly — use the skill's registered tools (skill__ prefix) instead. Reading/writing skill files (cat, ls, grep) is allowed. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    annotations = ToolAnnotations(destructiveHint = true, openWorldHint = true),
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        // Block execution of scripts under /skills/ from shell.
        // Skill tools must be invoked via their registered function-calling
        // tools (skill__ prefix), not by manually running skill scripts.
        // Reading/writing skill files (cat, ls, grep, etc.) is still allowed.
        val skillExecError = checkSkillScriptExecution(command)
        if (skillExecError != null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exitCode", -1)
                        put("stdout", "")
                        put("stderr", skillExecError)
                        put("timedOut", false)
                    }.toString()
                )
            )
        }
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val size = fileSize(workspaceId, area, relativePath)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    exportFile(workspaceId, area, relativePath, buffer)
    return buffer.toString(Charsets.UTF_8.name())
}

private fun rootfsPathToAreaAndRelative(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val buffer = ByteArrayOutputStream()
    exportFile(workspaceId, area, relativePath, buffer)
    val bytes = buffer.toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun kotlinx.serialization.json.JsonElement.pathOutsideWorkspace(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWorkspace()
    }.getOrDefault(true)

private fun String.isOutsideWorkspace(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return normalized != "/workspace" && !normalized.startsWith("/workspace/")
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}

/**
 * Known script file extensions used by skill tools.
 */
private val SKILL_SCRIPT_EXTENSIONS = SkillConstants.SCRIPT_EXTENSIONS

/**
 * Interpreters that can execute script files.
 */
private val SCRIPT_INTERPRETERS = SkillConstants.SCRIPT_INTERPRETERS

/**
 * Checks if a shell command attempts to execute a script file under /skills/.
 *
 * Returns an error message string if the command is blocked, or null if allowed.
 *
 * **Blocked**: running a script file located under /skills/ via an interpreter
 * (e.g. `bash /skills/github-cli/tools/repo_list.sh`, `cd /skills/foo && sh run.sh`).
 *
 * **Allowed**: reading/writing skill files (e.g. `cat /skills/foo/SKILL.md`,
 * `ls /skills/`, `grep -r pattern /skills/`).
 */
internal fun checkSkillScriptExecution(command: String): String? {
    // Quick check: if /skills is not mentioned at all, nothing to block.
    if (!command.contains("/skills")) return null

    // Split on shell operators to handle compound commands (&&, ||, ;, |)
    val subCommands = command.split(Regex("&&|\\|\\||;|\\|"))
    for (sub in subCommands) {
        val tokens = sub.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) continue

        // Check if any token is an interpreter (bash, sh, python, etc.)
        val hasInterpreter = tokens.any { token ->
            val base = token.substringAfterLast('/')
            base in SCRIPT_INTERPRETERS
        }

        // Check if any token references a script file under /skills/
        val hasSkillScript = tokens.any { token ->
            token.contains("/skills/") &&
                SKILL_SCRIPT_EXTENSIONS.any { token.endsWith(it) }
        }

        if (hasInterpreter && hasSkillScript) {
            return "Execution of skill scripts via shell is blocked. " +
                "Use the skill's registered function-calling tools (skill__ prefix) instead of " +
                "running skill scripts directly. Reading/writing skill files (cat, ls, grep) is allowed."
        }

        // Also block direct execution of a script file under /skills/ via shebang
        // (e.g. `/skills/github-cli/tools/repo_list.sh --arg`)
        val firstToken = tokens.first()
        if (firstToken.contains("/skills/") &&
            SKILL_SCRIPT_EXTENSIONS.any { firstToken.endsWith(it) }
        ) {
            return "Direct execution of skill scripts via shell is blocked. " +
                "Use the skill's registered function-calling tools (skill__ prefix) instead."
        }
    }
    return null
}
