package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CheckSkillScriptExecutionTest {

    // ── Blocked: interpreter + skill script ──────────────────────────

    @Test
    fun `block bash executing skill script`() {
        val err = checkSkillScriptExecution("bash /skills/github-cli/tools/list_repos.sh")
        assertNotNull("bash + skill script should be blocked", err)
    }

    @Test
    fun `block sh executing skill script`() {
        val err = checkSkillScriptExecution("sh /skills/foo/run.sh")
        assertNotNull(err)
    }

    @Test
    fun `block python executing skill script`() {
        val err = checkSkillScriptExecution("python /skills/foo/script.py")
        assertNotNull(err)
    }

    @Test
    fun `block node executing skill script`() {
        val err = checkSkillScriptExecution("node /skills/foo/index.js")
        assertNotNull(err)
    }

    @Test
    fun `block deno executing skill script`() {
        val err = checkSkillScriptExecution("deno run /skills/foo/mod.ts")
        assertNotNull(err)
    }

    @Test
    fun `block ruby executing skill script`() {
        val err = checkSkillScriptExecution("ruby /skills/foo/tool.rb")
        assertNotNull(err)
    }

    // ── Blocked: direct execution (shebang style) ────────────────────

    @Test
    fun `block direct execution of skill sh script`() {
        val err = checkSkillScriptExecution("/skills/foo/tools/run.sh --arg")
        assertNotNull("Direct execution of .sh under /skills/ should be blocked", err)
    }

    @Test
    fun `block direct execution of skill py script`() {
        val err = checkSkillScriptExecution("/skills/foo/script.py")
        assertNotNull(err)
    }

    // ── Blocked: compound commands ───────────────────────────────────

    @Test
    fun `block skill script in compound AND command`() {
        val err = checkSkillScriptExecution("echo hi && bash /skills/foo/x.sh")
        assertNotNull(err)
    }

    @Test
    fun `block skill script in compound OR command`() {
        val err = checkSkillScriptExecution("false || bash /skills/foo/x.sh")
        assertNotNull(err)
    }

    @Test
    fun `block skill script in semicolon chain`() {
        val err = checkSkillScriptExecution("echo hi; python /skills/foo/x.py")
        assertNotNull(err)
    }

    @Test
    fun `block skill script after cd into skills dir`() {
        val err = checkSkillScriptExecution("cd /skills/foo && sh run.sh")
        assertNotNull(err)
    }

    // ── Allowed: reading/writing skill files ─────────────────────────

    @Test
    fun `allow cat of skill file`() {
        assertNull(checkSkillScriptExecution("cat /skills/foo/SKILL.md"))
    }

    @Test
    fun `allow ls of skills directory`() {
        assertNull(checkSkillScriptExecution("ls -la /skills/"))
    }

    @Test
    fun `allow grep in skills directory`() {
        assertNull(checkSkillScriptExecution("grep -r 'pattern' /skills/foo/"))
    }

    @Test
    fun `allow find in skills directory`() {
        assertNull(checkSkillScriptExecution("find /skills/ -name '*.sh'"))
    }

    @Test
    fun `allow head of skill file`() {
        assertNull(checkSkillScriptExecution("head -20 /skills/foo/SKILL.md"))
    }

    // ── Allowed: non-skill commands ──────────────────────────────────

    @Test
    fun `allow command without skills path`() {
        assertNull(checkSkillScriptExecution("bash /workspace/build.sh"))
    }

    @Test
    fun `allow gh repo list`() {
        assertNull(checkSkillScriptExecution("gh repo list"))
    }

    @Test
    fun `allow plain echo`() {
        assertNull(checkSkillScriptExecution("echo hello"))
    }

    @Test
    fun `allow empty command`() {
        assertNull(checkSkillScriptExecution(""))
    }

    // ── Allowed: interpreter without skill script ────────────────────

    @Test
    fun `allow bash with workspace script`() {
        assertNull(checkSkillScriptExecution("bash /workspace/test.sh"))
    }

    @Test
    fun `allow python inline code`() {
        assertNull(checkSkillScriptExecution("python -c 'print(1)'"))
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `allow bash mentioning skills in echo but not executing script`() {
        // "skills" in echo argument, but no interpreter executing a .sh file under /skills/
        assertNull(checkSkillScriptExecution("echo /skills/foo"))
    }

    @Test
    fun `block skill script with full path interpreter`() {
        val err = checkSkillScriptExecution("/usr/bin/bash /skills/foo/x.sh")
        assertNotNull(err)
    }

    @Test
    fun `allow mixed compound where only non-skill part has interpreter`() {
        // bash runs /workspace/test.sh (not under /skills/), ls reads /skills/
        assertNull(checkSkillScriptExecution("bash /workspace/test.sh && ls /skills/"))
    }

    @Test
    fun `block mixed compound where skill part has interpreter`() {
        val err = checkSkillScriptExecution("ls /workspace/ && bash /skills/foo/x.sh")
        assertNotNull(err)
    }
}
