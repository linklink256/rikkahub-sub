package me.rerere.rikkahub.data.files

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Uses a two-step approach: YAML → YamlNode tree → JsonElement tree → SkillToolFile.
 *
 * kaml cannot directly deserialize [JsonElement] fields from YAML because
 * `JsonElement` is a sealed class and kaml's default tagged polymorphism expects
 * a type tag (e.g. `!<JsonObject>`) in the YAML, which `tools.yaml` does not
 * provide — this causes `DuplicateKeyException: Value is missing a type tag`.
 * By converting the YamlNode tree to a JsonElement tree first, we bypass the
 * polymorphism issue and let kotlinx-serialization-json handle `JsonElement`
 * fields natively.
 *
 * This is a pure function – no I/O, no Android dependencies – making it
 * directly testable on the JVM.
 *
 * @throws com.charleskorn.kaml.YamlException (or any parse exception)
 *         on malformed YAML.
 */
object SkillToolFileParser {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): SkillToolFile {
        val yamlNode = yaml.parseToYamlNode(text)
        val jsonElement = yamlNodeToJsonElement(yamlNode)
        return json.decodeFromJsonElement(SkillToolFile.serializer(), jsonElement)
    }

    /**
     * Recursively converts a kaml [YamlNode] tree into a kotlinx-serialization
     * [JsonElement] tree.
     *
     * Scalars are type-inferred: integers, longs, doubles, booleans and nulls
     * are converted to their typed [JsonPrimitive] equivalents; everything else
     * is kept as a string primitive.
     */
    private fun yamlNodeToJsonElement(node: YamlNode): JsonElement = when (node) {
        is YamlMap -> {
            val map = mutableMapOf<String, JsonElement>()
            for ((key, value) in node.entries) {
                val keyStr = (key as? YamlScalar)?.content ?: key.toString()
                map[keyStr] = yamlNodeToJsonElement(value)
            }
            JsonObject(map)
        }

        is YamlList -> JsonArray(node.items.map { yamlNodeToJsonElement(it) })

        is YamlScalar -> {
            val content = node.content
            when {
                content.equals("null", ignoreCase = true) -> JsonNull
                content.equals("true", ignoreCase = true) -> JsonPrimitive(true)
                content.equals("false", ignoreCase = true) -> JsonPrimitive(false)
                content.toIntOrNull() != null -> JsonPrimitive(content.toInt())
                content.toLongOrNull() != null -> JsonPrimitive(content.toLong())
                content.toDoubleOrNull() != null -> JsonPrimitive(content.toDouble())
                else -> JsonPrimitive(content)
            }
        }

        else -> JsonNull
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
