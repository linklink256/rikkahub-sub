package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

// 匹配 <think>...</think> 或未闭合的 <think>...（流式中途标签尚未闭合）。
// 非贪婪 + DOT_MATCHES_ALL，支持单个 Text part 内出现多个 think 块。
private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CLOSING_TAG_REGEX = Regex("</think>")

// 部分供应商不会返回reasoning parts, 所以需要这个transformer。
//
// 支持单个 Text part 内包含多个 <think> 块（如角色扮演小说场景中，主代理在每幕正文前后
// 都用 think 写导演规划）。每个 think 块抽成一个独立的 Reasoning part，正文部分（去掉
// 所有 think 块后剩余的文本）保留为 Text part。若 think 块未闭合（流式生成中途），
// 同样抽离，finishedAt 置 null 表示进行中。
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) transformPart(part, message, finishedAt = null) else listOf(part)
                    }
                )
            } else {
                message
            }
        }
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val now = Clock.System.now()
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) transformPart(part, message, finishedAt = now) else listOf(part)
                    }
                )
            } else {
                message
            }
        }
    }

    /**
     * 把一个 Text part 中所有 <think> 块抽离为 Reasoning part。
     *
     * - 遍历 [THINKING_REGEX] 的所有匹配，每个生成一个 Reasoning part。
     * - 正文 = 原文去掉所有 think 块后的剩余文本。若剩余文本非空则保留为 Text part；
     *   为空则不产生 Text part（避免空正文）。
     * - 已闭合的 think 块 finishedAt 用传入值；未闭合的（流式中途）finishedAt = null。
     * - 若该 part 不含 think 块，原样返回。
     */
    private fun transformPart(
        part: UIMessagePart.Text,
        message: UIMessage,
        finishedAt: kotlin.time.Instant?,
    ): List<UIMessagePart> {
        if (!THINKING_REGEX.containsMatchIn(part.text)) return listOf(part)

        val createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault())
        val hasClosingTag = CLOSING_TAG_REGEX.containsMatchIn(part.text)
        val parts = mutableListOf<UIMessagePart>()

        THINKING_REGEX.findAll(part.text).forEach { match ->
            val reasoning = match.groupValues.getOrNull(1)?.trim() ?: ""
            parts.add(
                UIMessagePart.Reasoning(
                    reasoning = reasoning,
                    createdAt = createdAt,
                    // 未闭合（流式中途）→ null 表示进行中；否则用传入的 finishedAt
                    finishedAt = if (hasClosingTag) finishedAt else null,
                )
            )
        }

        // 正文：去掉所有 think 块（含标签）
        val stripped = part.text.replace(THINKING_REGEX, "")
        if (stripped.isNotBlank()) {
            parts.add(part.copy(text = stripped))
        }
        return parts
    }
}
