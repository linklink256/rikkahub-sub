package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Semantic annotations describing a tool's behavior characteristics.
 * Inspired by MCP ToolAnnotations. Used for logging, UI hints, and future
 * approval-policy decisions. Does NOT change current approval behavior.
 */
@Serializable
data class ToolAnnotations(
    /** If true, the tool only reads state and has no side effects. */
    val readOnlyHint: Boolean = false,
    /** If true, the tool may destructively modify state (files, data, settings). */
    val destructiveHint: Boolean = false,
    /** If true, repeated calls with same args produce same result (safe to retry). */
    val idempotentHint: Boolean = false,
    /** If true, the tool interacts with external/unpredictable entities (network, user, sensors). */
    val openWorldHint: Boolean = false,
)

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val annotations: ToolAnnotations? = null,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
