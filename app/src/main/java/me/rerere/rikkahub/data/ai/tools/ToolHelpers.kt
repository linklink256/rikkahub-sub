package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/** Wrap a JsonObject result into a single-element UI message list. */
fun JsonObject.asToolResult(): List<UIMessagePart> = listOf(UIMessagePart.Text(this.toString()))

/** Wrap a String result into a single-element UI message list. */
fun String.asToolResult(): List<UIMessagePart> = listOf(UIMessagePart.Text(this))

/** Get an optional string parameter by name. */
fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/** Get a required string parameter by name, throwing if missing or null. */
fun JsonObject.strReq(name: String): String = str(name) ?: error("$name is required")

/** Get an optional integer parameter with a default value. */
fun JsonObject.intOr(name: String, default: Int): Int = this[name]?.jsonPrimitive?.intOrNull ?: default

/** Get an optional boolean parameter with a default value. */
fun JsonObject.boolOr(name: String, default: Boolean): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: default
