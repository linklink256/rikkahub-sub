package me.rerere.rikkahub.data.files

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * YAML declaration of a single executable tool inside a skill.
 *
 * Maps to one entry in the `tools` list of `tools.yaml`.
 */
@Serializable
data class SkillToolDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonElement? = null,
    val execute: SkillToolExecute,
)

/**
 * Execution options for a [SkillToolDeclaration].
 */
@Serializable
data class SkillToolExecute(
    val command: String,
    val timeoutMillis: Long? = null,
    val needsApproval: Boolean = false,
)

/**
 * Root structure of `tools.yaml`.
 */
@Serializable
data class SkillToolFile(
    val version: Int = 1,
    val tools: List<SkillToolDeclaration> = emptyList(),
)

/**
 * Parses a `tools.yaml` text into a [SkillToolFile].
 *
 * This is a pure function – no I/O, no Android dependencies – making it
 * directly testable on the JVM.
 *
 * @throws com.charleskorn.kaml.YamlException (or any parse exception)
 *         on malformed YAML.
 */
object SkillToolFileParser {
    // Use a non-strict Yaml instance: tools.yaml may contain keys (like YAML
    // comments, extension fields, or future schema additions) that are not
    // mapped to SkillToolFile properties. With Yaml.default (strictMode=true),
    // any unknown key throws UnknownPropertyException and the entire file is
    // rejected — silently, because listToolDeclarations catches the exception.
    // Using strictMode=false makes the parser ignore unknown keys gracefully.
    private val yaml = com.charleskorn.kaml.Yaml(
        configuration = com.charleskorn.kaml.YamlConfiguration(strictMode = false)
    )

    fun parse(text: String): SkillToolFile {
        return yaml.decodeFromString(SkillToolFile.serializer(), text)
    }
}

// Known script file extensions used by resolveCommandPath()
private val KNOWN_SCRIPT_EXTENSIONS = setOf(".sh", ".js", ".py", ".ts", ".rb")

/**
 * Extracts a script file path from a command string and resolves it
 * relative to [skillDir] with path-traversal protection.
 *
 * **Extraction rules** (first matching token wins):
 * 1. Token contains "/"
 * 2. Token starts with "."
 * 3. Token ends with a known extension (.sh, .js, .py, .ts, .rb)
 *
 * If no token matches, or the resolved path escapes [skillDir] (detected
 * by [SkillPaths.resolveSkillFile]), `null` is returned.
 *
 * Note: this function does **not** check whether the resolved file actually
 * exists – it only validates that the path is syntactically valid and stays
 * inside the skill directory. The caller (e.g. command execution) is responsible
 * for existence checking.
 *
 * @param skillDir the canonical skill directory (must exist).
 * @param command  raw command string, e.g. `"bash tools/list_repos.sh"`.
 * @return the resolved [File] inside [skillDir], or `null` if extraction
 *         failed or the path escapes the skill directory.
 */
internal fun resolveCommandPath(skillDir: File, command: String): File? {
    if (command.isBlank()) return null

    val tokens = command.split(Regex("\\s+"))
    val pathToken = tokens.firstOrNull { token ->
        token.contains("/") ||
            token.startsWith(".") ||
            KNOWN_SCRIPT_EXTENSIONS.any { token.endsWith(it) }
    } ?: return null

    return SkillPaths.resolveSkillFile(skillDir, pathToken)
}
