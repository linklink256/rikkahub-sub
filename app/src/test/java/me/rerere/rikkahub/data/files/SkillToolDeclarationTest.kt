package me.rerere.rikkahub.data.files

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillToolDeclarationTest {

    // ── SkillToolFileParser.parse() tests ──────────────────────────────

    @Test
    fun `parse valid tools yaml returns correct declarations`() {
        val yaml = """
            |version: 1
            |tools:
            |  - name: list_repos
            |    description: List public repositories for a GitHub user
            |    parameters:
            |      type: object
            |      properties:
            |        username:
            |          type: string
            |          description: GitHub login
            |      required:
            |        - username
            |    execute:
            |      command: bash tools/list_repos.sh
            |      timeoutMillis: 15000
            |      needsApproval: false
            |  - name: create_issue
            |    description: Create an issue in a GitHub repo
            |    parameters:
            |      type: object
            |      properties:
            |        repo:
            |          type: string
            |          description: owner/repo
            |        title:
            |          type: string
            |      required:
            |        - repo
            |        - title
            |    execute:
            |      command: bash tools/create_issue.sh
            |      needsApproval: true
        """.trimMargin()

        val file = SkillToolFileParser.parse(yaml)

        assertEquals(1, file.version)
        assertEquals(2, file.tools.size)

        val first = file.tools[0]
        assertEquals("list_repos", first.name)
        assertEquals("List public repositories for a GitHub user", first.description)
        assertEquals("bash tools/list_repos.sh", first.execute.command)
        assertEquals(15000L, first.execute.timeoutMillis)
        assertFalse(first.execute.needsApproval)

        val second = file.tools[1]
        assertEquals("create_issue", second.name)
        assertEquals("Create an issue in a GitHub repo", second.description)
        assertEquals("bash tools/create_issue.sh", second.execute.command)
        assertNull(second.execute.timeoutMillis)
        assertTrue(second.execute.needsApproval)
    }

    @Test
    fun `parse parameters as JsonElement supports nested access`() {
        val yaml = """
            |version: 1
            |tools:
            |  - name: list_repos
            |    description: List repos
            |    parameters:
            |      type: object
            |      properties:
            |        username:
            |          type: string
            |          description: GitHub login
            |      required:
            |        - username
            |    execute:
            |      command: bash tools/list_repos.sh
        """.trimMargin()

        val file = SkillToolFileParser.parse(yaml)
        val params = file.tools[0].parameters
        assertNotNull("parameters should not be null", params)

        val obj = params as? JsonObject
        assertNotNull("parameters should be a JsonObject", obj)
        assertEquals("object", obj?.get("type")?.jsonPrimitive?.content)

        val properties = obj?.get("properties")?.jsonObject
        assertNotNull("properties should exist", properties)
        val username = properties?.get("username")?.jsonObject
        assertNotNull("username property should exist", username)
        assertEquals("string", username?.get("type")?.jsonPrimitive?.content)

        val required = obj?.get("required")
        assertNotNull("required array should exist", required)
    }

    @Test
    fun `parse empty tools list`() {
        val yaml = """
            |version: 1
            |tools: []
        """.trimMargin()

        val file = SkillToolFileParser.parse(yaml)
        assertEquals(1, file.version)
        assertTrue(file.tools.isEmpty())
    }

    @Test
    fun `parse only version defaults to empty tools`() {
        val yaml = """
            |version: 1
        """.trimMargin()

        val file = SkillToolFileParser.parse(yaml)
        assertEquals(1, file.version)
        assertTrue(file.tools.isEmpty())
    }

    @Test(expected = Exception::class)
    fun `parse bad yaml throws exception`() {
        val yaml = """
            |version: 1
            |tools:
            |  invalid yaml structure: [unclosed
        """.trimMargin()

        SkillToolFileParser.parse(yaml)
    }

    // ── resolveCommandPath() tests ─────────────────────────────────────

    @Test
    fun `extract script path from bash command`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            val scriptFile = File(skillDir, "tools/list_repos.sh")
            scriptFile.parentFile.mkdirs()
            scriptFile.createNewFile()

            val result = resolveCommandPath(skillDir, "bash tools/list_repos.sh")
            assertNotNull(result)
            assertEquals(scriptFile.canonicalPath, result!!.canonicalPath)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `extract script path from node command`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            val scriptFile = File(skillDir, "tools/x.js")
            scriptFile.parentFile.mkdirs()
            scriptFile.createNewFile()

            val result = resolveCommandPath(skillDir, "node tools/x.js")
            assertNotNull(result)
            assertEquals(scriptFile.canonicalPath, result!!.canonicalPath)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `extract script path from python command`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            val scriptFile = File(skillDir, "tools/x.py")
            scriptFile.parentFile.mkdirs()
            scriptFile.createNewFile()

            val result = resolveCommandPath(skillDir, "python tools/x.py")
            assertNotNull(result)
            assertEquals(scriptFile.canonicalPath, result!!.canonicalPath)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `extract script path from dot slash command`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            val scriptFile = File(skillDir, "tools/x.sh")
            scriptFile.parentFile.mkdirs()
            scriptFile.createNewFile()

            val result = resolveCommandPath(skillDir, "./tools/x.sh")
            assertNotNull(result)
            assertEquals(scriptFile.canonicalPath, result!!.canonicalPath)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `extract script path from deno command`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            val scriptFile = File(skillDir, "script.ts")
            scriptFile.createNewFile()

            val result = resolveCommandPath(skillDir, "deno run --allow-read script.ts")
            assertNotNull(result)
            assertEquals(scriptFile.canonicalPath, result!!.canonicalPath)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `reject path traversal in command`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            val result = resolveCommandPath(skillDir, "bash ../../etc/passwd")
            assertNull("Path traversal should be rejected", result)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `return null for command without script path`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            val result = resolveCommandPath(skillDir, "gh repo list")
            assertNull("Pure command without file path should return null", result)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `return null for empty or blank command`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            assertNull(resolveCommandPath(skillDir, ""))
            assertNull(resolveCommandPath(skillDir, "   "))
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `return null when script file does not exist`() {
        val skillDir = Files.createTempDirectory("skill-test").toFile()
        try {
            // Path is syntactically valid but file does not exist — resolveCommandPath
            // does NOT check existence, it only resolves & validates the path.
            // The file doesn't need to exist for path resolution.
            val result = resolveCommandPath(skillDir, "bash tools/missing.sh")
            assertNotNull("Path should be resolved even if file does not exist", result)
            val expected = File(skillDir, "tools/missing.sh").canonicalFile
            assertEquals(expected.canonicalPath, result!!.canonicalPath)
        } finally {
            skillDir.deleteRecursively()
        }
    }

    @Test
    fun `reject sibling escape`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()
        try {
            val skillDir = File(skillsRoot, "foo").apply { mkdirs() }
            File(skillsRoot, "foobar").apply { mkdirs() }

            // ../foobar attempts to escape "foo" into sibling "foobar"
            val result = resolveCommandPath(skillDir, "bash ../foobar/secret.sh")
            assertNull("Sibling escape should be rejected", result)
        } finally {
            skillsRoot.deleteRecursively()
        }
    }
}
