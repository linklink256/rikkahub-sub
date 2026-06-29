package me.rerere.rikkahub.data.ai.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 subagent profile 的权限契约 —— 工具级强制只读，而非散文约束。
 *
 * 根因：explore 此前未设 [SubagentProfile.excludedTools]，与 coder 工具权限完全相同，
 * "只读"仅存在于 system prompt 散文里，子代理可越权改码（实测发生过：一个 explore
 * 子代理本应只查，却直接 edit 了源文件）。业界共识（Anthropic Claude Code / OpenAI
 * Agents SDK / LangGraph）均以工具白名单/黑名单做权限隔离。
 */
class SubagentProfileBuiltinTest {

    private fun profile(name: String) =
        SubagentProfile.BUILTIN.first { it.name == name }

    @Test
    fun explore_is_file_readonly_by_excluding_write_tools() {
        val explore = profile("explore")
        assertTrue(
            "explore 应排除文件写工具 ${SubagentProfile.FILE_MUTATING_TOOLS}，实际=${explore.excludedTools}",
            SubagentProfile.FILE_MUTATING_TOOLS.all { it in explore.excludedTools }
        )
    }

    @Test
    fun reviewer_is_fully_readonly() {
        val reviewer = profile("reviewer")
        assertEquals(
            SubagentProfile.FULLY_READONLY_EXCLUDED_TOOLS,
            reviewer.excludedTools
        )
    }

    @Test
    fun coder_can_write_and_shell() {
        // coder 是执行类，须保留全部工具
        val coder = profile("coder")
        assertTrue("coder 不应排除任何工具，排除集应为空", coder.excludedTools.isEmpty())
    }

    @Test
    fun mutating_tool_constants_are_stable() {
        assertEquals(
            setOf("workspace_write_file", "workspace_edit_file"),
            SubagentProfile.FILE_MUTATING_TOOLS
        )
        assertEquals(
            setOf("workspace_write_file", "workspace_edit_file", "workspace_shell"),
            SubagentProfile.FULLY_READONLY_EXCLUDED_TOOLS
        )
    }
}
