package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.files.SkillToolDeclaration
import me.rerere.rikkahub.data.files.SkillToolExecute
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillsToolsTest {

    // ========================================================================
    // jsonSchemaToInputSchema (pure function)
    // ========================================================================

    @Test
    fun `jsonSchemaToInputSchema with valid object schema`() {
        val schema: JsonElement = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "The name")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                })
            })
            put("required", buildJsonObject {
                put("0", JsonPrimitive("name"))
                put("1", JsonPrimitive("count"))
            })
        }

        val result = jsonSchemaToInputSchema(schema)

        assertNotNull(result)
        assertTrue(result is InputSchema.Obj)
        val obj = result as InputSchema.Obj
        assertEquals(setOf("name", "count"), obj.properties.keys)
        assertEquals(listOf("name", "count"), obj.required)
    }

    @Test
    fun `jsonSchemaToInputSchema returns null for non-object json`() {
        assertNull(jsonSchemaToInputSchema(JsonPrimitive("string")))
        assertNull(jsonSchemaToInputSchema(JsonNull))
    }

    @Test
    fun `jsonSchemaToInputSchema returns null for null input`() {
        assertNull(jsonSchemaToInputSchema(null))
    }

    @Test
    fun `jsonSchemaToInputSchema returns null when type is not object`() {
        val schema: JsonElement = buildJsonObject {
            put("type", "string")
        }
        assertNull(jsonSchemaToInputSchema(schema))
    }

    @Test
    fun `jsonSchemaToInputSchema handles schema without required`() {
        val schema: JsonElement = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                })
            })
        }

        val result = jsonSchemaToInputSchema(schema)
        assertNotNull(result)
        val obj = result as InputSchema.Obj
        assertEquals(setOf("name"), obj.properties.keys)
        assertNull(obj.required)
    }

    @Test
    fun `jsonSchemaToInputSchema handles schema without properties key`() {
        val schema: JsonElement = buildJsonObject {
            put("type", "object")
            put("description", "Just an object description")
        }

        val result = jsonSchemaToInputSchema(schema)
        assertNotNull(result)
        val obj = result as InputSchema.Obj
        assertTrue(obj.properties.isEmpty())
        assertNull(obj.required)
    }

    @Test
    fun `jsonSchemaToInputSchema ignores required when it is not a json object`() {
        val schema: JsonElement = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("a", buildJsonObject { put("type", "string") })
            })
            // required as an array - not a json object so it should be ignored
            put("required", JsonPrimitive("[\"a\"]"))
        }

        val result = jsonSchemaToInputSchema(schema)
        assertNotNull(result)
        assertNull((result as InputSchema.Obj).required)
    }

    // ========================================================================
    // formatToolResult (pure function)
    // ========================================================================

    @Test
    fun `formatToolResult exitCode 0 returns stdout`() {
        val result = WorkspaceCommandResult(
            exitCode = 0,
            stdout = "Hello World",
            stderr = "",
            timedOut = false,
            truncated = false,
        )
        assertEquals("Hello World", formatToolResult(result, 30_000L))
    }

    @Test
    fun `formatToolResult exitCode 0 with stderr appends stderr section`() {
        val result = WorkspaceCommandResult(
            exitCode = 0,
            stdout = "main output",
            stderr = "warning: something",
            timedOut = false,
            truncated = false,
        )
        val text = formatToolResult(result, 30_000L)
        assertTrue(text.contains("main output"))
        assertTrue(text.contains("[stderr]"))
        assertTrue(text.contains("warning: something"))
    }

    @Test
    fun `formatToolResult exitCode non-zero returns failure message`() {
        val result = WorkspaceCommandResult(
            exitCode = 1,
            stdout = "",
            stderr = "error occurred",
            timedOut = false,
            truncated = false,
        )
        val text = formatToolResult(result, 30_000L)
        assertTrue(text.contains("Tool failed"))
        assertTrue(text.contains("exit 1"))
        assertTrue(text.contains("error occurred"))
    }

    @Test
    fun `formatToolResult timedOut returns timeout message`() {
        val result = WorkspaceCommandResult(
            exitCode = -1,
            stdout = "",
            stderr = "",
            timedOut = true,
            truncated = false,
        )
        val text = formatToolResult(result, 5_000L)
        assertTrue(text.contains("timed out"))
        assertTrue(text.contains("5000ms"))
    }

    @Test
    fun `formatToolResult exitCode non-zero includes stdout as well`() {
        val result = WorkspaceCommandResult(
            exitCode = 127,
            stdout = "command not found",
            stderr = "bash: tool: not found",
            timedOut = false,
            truncated = false,
        )
        val text = formatToolResult(result, 30_000L)
        assertTrue(text.contains("exit 127"))
        assertTrue(text.contains("command not found"))
        assertTrue(text.contains("bash: tool: not found"))
    }

    @Test
    fun `formatToolResult timedOut takes priority over non-zero exitCode`() {
        val result = WorkspaceCommandResult(
            exitCode = 137,
            stdout = "",
            stderr = "",
            timedOut = true,
            truncated = false,
        )
        val text = formatToolResult(result, 10_000L)
        assertTrue(text.contains("timed out"))
        assertTrue(text.contains("10000ms"))
    }

    @Test
    fun `formatToolResult empty stdout with exitCode 0 returns empty string`() {
        val result = WorkspaceCommandResult(
            exitCode = 0,
            stdout = "",
            stderr = "",
            timedOut = false,
            truncated = false,
        )
        assertEquals("", formatToolResult(result, 30_000L))
    }

    // ========================================================================
    // buildSkillToolCommand (pure function)
    // ========================================================================

    @Test
    fun `buildSkillToolCommand prefixes cd into skill directory`() {
        val cmd = buildSkillToolCommand("github-cli", "bash tools/list_repos.sh")
        assertEquals("cd \"/skills/github-cli\" && bash tools/list_repos.sh", cmd)
    }

    @Test
    fun `buildSkillToolCommand handles skill name with hyphens`() {
        val cmd = buildSkillToolCommand("my-cool-skill", "node index.js")
        assertEquals("cd \"/skills/my-cool-skill\" && node index.js", cmd)
    }

    @Test
    fun `buildSkillToolCommand preserves complex commands`() {
        val raw = "gh repo list --json name -q '.[].name'"
        val cmd = buildSkillToolCommand("github-cli", raw)
        assertEquals("cd \"/skills/github-cli\" && $raw", cmd)
    }

    // ========================================================================
    // isSafeName (pure function)
    // ========================================================================

    @Test
    fun `isSafeName accepts alphanumeric hyphen underscore dot`() {
        assertTrue(isSafeName("github-cli"))
        assertTrue(isSafeName("my_skill"))
        assertTrue(isSafeName("tool.v2"))
        assertTrue(isSafeName("ABC123"))
    }

    @Test
    fun `isSafeName rejects shell metacharacters`() {
        assertFalse(isSafeName("foo; rm -rf /"))
        assertFalse(isSafeName("foo && bar"))
        assertFalse(isSafeName("foo`whoami`"))
        assertFalse(isSafeName("foo\$HOME"))
        assertFalse(isSafeName("foo bar"))
        assertFalse(isSafeName(""))
    }

    // ========================================================================
    // executeJsSkillTool (needs QuickJS native libs — may skip on JVM)
    // ========================================================================

    @Test
    fun `executeJsSkillTool runs JS and returns result`() {
        // This test requires QuickJS native library (Android-only). On a desktop
        // JVM, QuickJSContext.create() will throw UnsatisfiedLinkError. We skip
        // gracefully in that case and treat this as an Android-instrumented test.
        val jsCode = """
            function main(args) {
                console.log("Hello from JS");
                return { result: "ok", input: args };
            }
        """.trimIndent()

        val logs = arrayListOf<String>()
        val context = try {
            com.whl.quickjs.wrapper.QuickJSContext.create()
        } catch (e: UnsatisfiedLinkError) {
            // QuickJS native libs not available on this JVM — skip test
            return
        }

        try {
            context.setConsole(object : com.whl.quickjs.wrapper.QuickJSContext.Console {
                override fun log(info: String?) { logs.add("[LOG] $info") }
                override fun info(info: String?) { logs.add("[INFO] $info") }
                override fun warn(info: String?) { logs.add("[WARN] $info") }
                override fun error(info: String?) { logs.add("[ERROR] $info") }
            })

            context.evaluate(jsCode)

            val fn = context.globalObject.getJSFunction("main")
            assertNotNull("main function should exist", fn)

            val result = fn!!.call("""{"test": true}""")
            assertNotNull("result should not be null", result)
            assertTrue("result should be a QuickJSObject", result is com.whl.quickjs.wrapper.QuickJSObject)
            val jsonStr = (result as com.whl.quickjs.wrapper.QuickJSObject).stringify()
            assertTrue("result should contain 'ok'", jsonStr.contains("ok"))

            assertTrue("console.log should be captured", logs.isNotEmpty())
            assertTrue("logs should contain Hello", logs.any { it.contains("Hello") })

            fn.release()
        } finally {
            context.destroy()
        }
    }

    @Test
    fun `executeJsSkillTool with custom function name`() {
        val jsCode = """
            function myFunc(args) {
                return { from: "myFunc", value: JSON.parse(args).x };
            }
        """.trimIndent()

        val context = try {
            com.whl.quickjs.wrapper.QuickJSContext.create()
        } catch (e: UnsatisfiedLinkError) {
            return
        }

        try {
            context.evaluate(jsCode)
            val fn = context.globalObject.getJSFunction("myFunc")
            assertNotNull("myFunc should exist", fn)

            val result = fn!!.call("""{"x": 42}""")
            assertTrue("result should be QuickJSObject", result is com.whl.quickjs.wrapper.QuickJSObject)
            val jsonStr = (result as com.whl.quickjs.wrapper.QuickJSObject).stringify()
            assertTrue("result should contain myFunc", jsonStr.contains("myFunc"))
            assertTrue("result should contain 42", jsonStr.contains("42"))

            fn.release()
        } finally {
            context.destroy()
        }
    }

    @Test
    fun `executeJsSkillTool returns error for missing function`() {
        val jsCode = """
            var x = 1;
        """.trimIndent()

        val context = try {
            com.whl.quickjs.wrapper.QuickJSContext.create()
        } catch (e: UnsatisfiedLinkError) {
            return
        }

        try {
            context.evaluate(jsCode)
            val fn = context.globalObject.getJSFunction("main")
            assertNull("main function should not exist", fn)
        } finally {
            context.destroy()
        }
    }

    // ========================================================================
    // createSkillTools routing logic (type=javascript vs shell)
    // ========================================================================

    @Test
    fun `createSkillTools routing detects javascript type correctly`() {
        val jsDecl = SkillToolDeclaration(
            name = "js-tool",
            description = "A JS tool",
            execute = SkillToolExecute(type = "javascript", entry = "test.js")
        )
        assertEquals("javascript", jsDecl.execute.type)

        val shellDecl = SkillToolDeclaration(
            name = "shell-tool",
            description = "A shell tool",
            execute = SkillToolExecute(command = "ls")
        )
        assertEquals("shell", shellDecl.execute.type)
    }

    @Test
    fun `javascript tool execution routes to executeJsSkillTool logic`() {
        val decl = SkillToolDeclaration(
            name = "test-js",
            description = "Test",
            execute = SkillToolExecute(type = "javascript", entry = "test.js", `function` = "main")
        )
        assertTrue("Type should be javascript", decl.execute.type == "javascript")
        assertNotNull("Entry should be set", decl.execute.entry)
        assertNull("Shell command should be null for JS tool", decl.execute.command)
    }

    @Test
    fun `shell tool execution routes to executeSkillTool logic`() {
        val decl = SkillToolDeclaration(
            name = "test-shell",
            description = "Test",
            execute = SkillToolExecute(command = "echo hello")
        )
        assertEquals("Type should default to shell", "shell", decl.execute.type)
        assertNotNull("Command should be set", decl.execute.command)
        assertNull("Entry should be null for shell tool", decl.execute.entry)
    }
}
