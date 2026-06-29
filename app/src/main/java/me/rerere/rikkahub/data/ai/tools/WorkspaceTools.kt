package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
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
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

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
 * 只放行公认只读的命令，默认拒绝其它。
 *
 * 与黑名单的区别：黑名单（禁 rm/sed -i/echo > 等）会被 `python -c "open(...,'w')..."`、
 * `node -e "fs.writeFileSync(...)"` 等执行代码的命令绕过；白名单默认拒绝一切不在列表的
 * 命令，因此 `python/node/perl/ruby/awk …` 被默认拒，是真正的结构性只读边界（非威慑）。
 *
 * 借鉴 Roo-Code mode `groups` / Claude Code explore 的工具集裁剪思路：权限靠工具可用性
 * 而非提示词散文约束。只读子代理（explore/reviewer）经 excludedTools 排除全功能
 * `workspace_shell`、只留 `workspace_read_shell`，从而**不可能**越权改码。
 */
private val READONLY_SHELL_ALLOWED_BASE_COMMANDS: Set<String> = setOf(
    // 查看与检索
    "cat", "head", "tail", "less", "more", "wc", "nl", "cut", "paste", "column",
    "grep", "egrep", "fgrep", "rg", "ack", "ag",
    "find", "locate", "which", "whereis", "file", "stat", "realpath", "readlink",
    "ls", "dir", "tree", "exa",
    // 排序/去重/转换（纯文本，无副作用）
    "sort", "uniq", "tr", "fold", "fmt", "expand", "unexpand", "rev",
    "awk", "sed",  // 受限：见下方 checkReadonlyCommand 进一步拒绝 sed -i / sed 写文件
    // 编码/哈希（只读运算）
    "md5sum", "sha1sum", "sha256sum", "sum", "cksum", "xxd", "od", "hexdump",
    "base32", "base64", "basename", "dirname",
    // 系统只读探查
    "echo",        // 受限：见 checkReadonlyCommand 拒 echo 重定向写
    "printf",      // 受限：同上
    "env", "printenv", "uname", "hostname", "id", "whoami", "date", "cal", "uptime",
    "pwd", "true", "false", "test", "[", "command", "type", "help",
    "man", "info",
    // git 只读子命令
    "git",
    // diff / cmp
    "diff", "cmp", "comm",
    // jq / yq / xsltproc 等只读解析
    "jq", "yq", "xsltproc", "xmllint",
    // 字数/统计
    "du", "df",
)

/// `echo` / `printf` 携带输出重定向（> >> >|）即写文件 → 拒绝。
/// `sed` 带 -i/-i '' / --in-place → 拒绝。`awk` 带 -i inplace → 拒绝。
/// `tee` 默认拒（写文件）；`cp`/`mv`/`rm`/`mkdir`/`rmdir`/`touch`/`chmod`/`chown` 等本身不在白名单。
internal fun checkReadonlyCommand(command: String): String? {
    if (command.isBlank()) return "read-only shell: empty command"

    // 拒绝任何重定向写（> >> >|）——这覆盖 echo > file / printf >> file / cat > file 等
    // 注意管道 | 读端合法、tee 写端由 tee 不在白名单拦截
    // 命令替换 $(...) 和反引号 `...` 可在"只读首命令"内部藏写命令（echo $(rm x)）→ 一律拒
    if (command.contains("\$(") || command.contains("`")) {
        return "read-only shell: command substitution ($()/backticks) is not allowed"
    }
    if (Regex("""[^|]>\s*[^|&]""").containsMatchIn(command) ||
        command.contains(">>") ||
        command.contains(">|")
    ) {
        return "read-only shell: output redirection (>/>>) is not allowed"
    }

    val subCommands = command.split(Regex("""&&|\|\||;|\|"""))
    for (sub in subCommands) {
        val tokens = sub.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) continue
        val base = tokens.first().substringAfterLast('/')

        // 整命令白名单校验
        if (base !in READONLY_SHELL_ALLOWED_BASE_COMMANDS) {
            return "read-only shell: command not in read-only allowlist: $base"
        }
        // sed 写文件
        if (base == "sed" && tokens.any { it == "-i" || it == "--in-place" || it.startsWith("-i") }) {
            return "read-only shell: sed in-place editing is not allowed"
        }
        // awk 写文件
        if (base == "awk" && tokens.any { it == "-i" || it == "--include" } &&
            tokens.any { it == "inplace" || it.contains("inplace") }) {
            return "read-only shell: awk in-place editing is not allowed"
        }
        // awk 的 print/printf 重定向到文件（awk '{... > "file"}'）属 awk 语法非 shell 重定向，
        // 上面 shell 重定向正则拦不到；对 awk 命令文本额外检 > 即拒（宁可误伤少数高级用法）。
        if (base == "awk" && sub.contains('>')) {
            return "read-only shell: awk output redirection is not allowed"
        }
        // git 子命令限定为只读集合
        // find 的 -exec/-execdir/-ok/-delete 会写/删文件 → 拒（find 本身只读，但这些动作破坏）
        if (base == "find" && tokens.any { it == "-delete" || it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir" }) {
            return "read-only shell: find -exec/-delete is not allowed"
        }
        if (base == "git") {
            val sub = tokens.drop(1).firstOrNull { !it.startsWith("-") }
            // 仅保留公认只读子命令；branch/tag/stash/config/remote 均可写故排除
            val readOnlyGitSub = setOf(
                "status", "log", "show", "diff", "blame", "ls-files",
                "ls-tree", "ls-remote", "rev-parse", "rev-list", "describe",
                "shortlog", "name-rev", "for-each-ref", "grep",
            )
            if (sub != null && sub !in readOnlyGitSub) {
                return "read-only shell: git subcommand not in read-only allowlist: git $sub"
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
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val reject = checkReadonlyCommand(command)
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
