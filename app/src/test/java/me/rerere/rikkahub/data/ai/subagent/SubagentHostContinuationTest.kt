package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 subagent 摘要追问（continuation）轮的工具集选择。
 *
 * 根因：此前追问轮传入了完整 childTools，导致 GenerationHandler.generateText
 * 的 step 循环不提前终止（模型可继续调工具），相当于又跑了一整轮最多 maxSteps
 * 步的 agent 循环（内置 profile maxSteps 高达 48/64），近倍增加墙钟时间。
 *
 * 修复：追问轮是纯文本扩写（"把摘要写长"），不应携带任何工具——传入空列表后，
 * 模型无法产出工具调用，循环在 1 步内终止。
 */
class SubagentHostContinuationTest {

    private fun dummyTool(name: String) = Tool(
        name = name,
        description = "test tool",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
        execute = { emptyList<UIMessagePart>() },
    )

    @Test
    fun `continuation round uses no tools even when child has tools`() {
        val childTools = listOf(
            dummyTool("search"),
            dummyTool("read_file"),
            dummyTool("write_file"),
        )

        val continuationTools = selectContinuationTools(childTools)

        assertTrue("continuation round must not carry any tools", continuationTools.isEmpty())
    }

    @Test
    fun `continuation round uses no tools when child has no tools`() {
        val continuationTools = selectContinuationTools(emptyList())
        assertEquals(emptyList<Tool>(), continuationTools)
    }

    @Test
    fun `subagent profile continuation defaults are disabled`() {
        // 回归守卫：continuation 默认关闭（0/0），避免简单任务多花一整轮 LLM 调用
        assertEquals(0, SubagentProfile.DEFAULT_SUMMARY_MIN_LENGTH)
        assertEquals(0, SubagentProfile.DEFAULT_SUMMARY_CONTINUATION_ATTEMPTS)
    }
}
