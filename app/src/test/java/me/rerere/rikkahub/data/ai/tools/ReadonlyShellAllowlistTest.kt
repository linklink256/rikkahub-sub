package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 workspace_read_shell 的白名单只读边界：只读命令放行、写命令/重定向/
 * 执行代码一律拒。这是只读子代理（explore/reviewer）结构性只读的安全根基。
 */
class ReadonlyShellAllowlistTest {

    // 测试用规则集：复刻 assets/shell/readonly_rules.json 的内容，保持纯 JVM（不依赖 Android assets）。
    private val rules = ReadonlyShellRules(
        allowedBaseCommands = setOf(
            "cat", "head", "tail", "less", "more", "wc", "nl", "cut", "paste", "column",
            "grep", "egrep", "fgrep", "rg", "ack", "ag",
            "find", "locate", "which", "whereis", "file", "stat", "realpath", "readlink",
            "ls", "dir", "tree", "exa",
            "sort", "uniq", "tr", "fold", "fmt", "expand", "unexpand", "rev",
            "awk", "sed",
            "md5sum", "sha1sum", "sha256sum", "sum", "cksum", "xxd", "od", "hexdump",
            "base32", "base64", "basename", "dirname",
            "echo", "printf",
            "env", "printenv", "uname", "hostname", "id", "whoami", "date", "cal", "uptime",
            "pwd", "true", "false", "test", "[", "command", "type", "help",
            "man", "info",
            "git",
            "diff", "cmp", "comm",
            "jq", "yq", "xsltproc", "xmllint",
            "du", "df",
        ),
        rejectIfContainsLiteral = listOf("$(", "`", ">>", ">|"),
        rejectIfRegex = listOf("""[^|]>\s*[^|&]"""),
        perCommandRules = mapOf(
            "sed" to PerCommandRule(
                rejectIfAnyFlagStartsWith = listOf("-i"),
                rejectIfAnyFlagEquals = listOf("--in-place"),
            ),
            "awk" to PerCommandRule(
                rejectIfAnyFlagEquals = listOf("-i", "--include"),
                rejectIfAnyFlagContains = listOf("inplace"),
                rejectIfSubcommandContains = listOf(">"),
            ),
            "find" to PerCommandRule(
                rejectIfAnyFlagEquals = listOf("-delete", "-exec", "-execdir", "-ok", "-okdir"),
            ),
            "git" to PerCommandRule(
                allowedSubcommands = setOf(
                    "status", "log", "show", "diff", "blame", "ls-files",
                    "ls-tree", "ls-remote", "rev-parse", "rev-list", "describe",
                    "shortlog", "name-rev", "for-each-ref", "grep",
                ),
            ),
        ),
    )

    private fun reject(cmd: String): String? = checkReadonlyCommand(rules, cmd)

    @Test
    fun readCommands_allowed() {
        listOf(
            "cat /workspace/a.txt",
            "ls -la /workspace",
            "grep -rn foo /workspace",
            "find /workspace -name '*.kt'",
            "head -100 file",
            "tail -50 file",
            "wc -l file",
            "git status",
            "git log --oneline -10",
            "git diff",
            "git show HEAD",
            "git blame file",
            "git ls-files",
            "git rev-parse HEAD",
            "sed -n '1,10p' file",
            "awk '{print \$1}' file",
            "echo hello",            // 无重定向 → 只读输出到 stdout
            "printf '%s' x",
            "stat /workspace",
            "diff a b",
            "jq '.' file.json",
            "du -sh /workspace",
            "command -v grep",
        ).forEach { cmd ->
            assertNull("应放行只读命令: $cmd", reject(cmd))
        }
    }

    @Test
    fun writeCommands_rejected() {
        listOf(
            "rm /workspace/a.txt",
            "mv a b",
            "cp a b",
            "mkdir /workspace/x",
            "rmdir /workspace/x",
            "touch /workspace/x",
            "chmod +x file",
            "chown user file",
            "tee file",              // tee 不在白名单
            "python3 -c \"open('x','w').write('y')\"",
            "node -e \"require('fs').writeFileSync('x','y')\"",
            "perl -e \"...\"",
            "ruby -e \"...\"",
            "bash -c \"rm x\"",
            "sh script.sh",
        ).forEach { cmd ->
            val r = reject(cmd)
            assertTrue("应拒绝写/执行命令: $cmd (实际=${r ?: "放行了!"})", r != null)
        }
    }

    @Test
    fun outputRedirection_rejected() {
        listOf(
            "echo hi > /workspace/x",
            "echo hi >> /workspace/x",
            "printf 'x' > /workspace/x",
            "cat a > b",
            "grep foo file > out",
        ).forEach { cmd ->
            val r = reject(cmd)
            assertTrue("应拒绝重定向写: $cmd (实际=${r ?: "放行了!"})", r != null)
        }
    }

    @Test
    fun sedInPlace_andAwkInPlace_rejected() {
        listOf(
            "sed -i 's/a/b/g' file",
            "sed --in-place 's/a/b/' file",
            "awk -i inplace '{print}' file",
        ).forEach { cmd ->
            val r = reject(cmd)
            assertTrue("应拒绝就地改写: $cmd (实际=${r ?: "放行了!"})", r != null)
        }
    }

    @Test
    fun gitWriteSubcommands_rejected() {
        listOf(
            "git add .",
            "git commit -m x",
            "git push",
            "git checkout -b new",
            "git reset --hard",
            "git clean -fd",
            "git branch -D feature",
            "git tag -d v1.0",
            "git stash drop",
            "git config user.name x",
            "git remote add origin url",
        ).forEach { cmd ->
            val r = reject(cmd)
            assertTrue("应拒绝 git 写子命令: $cmd (实际=${r ?: "放行了!"})", r != null)
        }
    }

    @Test
    fun compoundCommands_rejectedIfAnySubcommandWrites() {
        // && 链中只要有一段写命令 → 整条拒（保护子段隔离）
        val r = reject("cat a && rm b")
        assertTrue("复合命令含写操作应拒: cat a && rm b (实际=${r ?: "放行了!"})", r != null)
    }

    @Test
    fun commandSubstitution_rejected() {
        listOf(
            "echo \$(rm /workspace/x)",
            "echo `rm /workspace/x`",
            "echo \$(cat file)",
            "cat \$(find . -name x)",
        ).forEach { cmd ->
            val r = reject(cmd)
            assertTrue("应拒绝命令替换: $cmd (实际=${r ?: "放行了!"})", r != null)
        }
    }

    @Test
    fun findWriteActions_rejected() {
        listOf(
            "find . -type f -delete",
            "find /workspace -name '*.tmp' -exec rm {} \\;",
            "find . -execdir chmod +x {} +",
            "find . -ok rm {} \\;",
        ).forEach { cmd ->
            val r = reject(cmd)
            assertTrue("应拒绝 find 写动作: $cmd (实际=${r ?: "放行了!"})", r != null)
        }
    }
}

    @Test
    fun emptyRules_failClosed_rejectsEverything() {
        // loadReadonlyShellRules 在 assets 缺失/解析失败时返回空规则集（fail-closed）。
        // 空白名单 → 即便是最无害的只读命令也应被拒，确保安全方向失败。
        val empty = ReadonlyShellRules()
        val r = checkReadonlyCommand(empty, "cat /workspace/a.txt")
        assertTrue(
            "空规则集应 fail-closed 拒绝一切命令（含 cat），实际=${r ?: "放行了!"}",
            r != null,
        )
    }

    @Test
    fun rulesJson_roundtrips_preservingFields() {
        // 验证 ReadonlyShellRules 的 kotlinx 序列化往返稳定——确保 assets JSON 的字段名
        // 与数据模型一致（拼错字段名会导致解析后集合为空、fail-closed 误拒所有命令）。
        val original = rules
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(ReadonlyShellRules.serializer(), original)
        val decoded = json.decodeFromString(ReadonlyShellRules.serializer(), encoded)
        assertEquals(original.allowedBaseCommands, decoded.allowedBaseCommands)
        assertEquals(original.rejectIfContainsLiteral, decoded.rejectIfContainsLiteral)
        assertEquals(original.perCommandRules.keys, decoded.perCommandRules.keys)
        // 关键：git 的 allowedSubcommands 必须完整往返（非空）
        assertEquals(
            original.perCommandRules["git"]?.allowedSubcommands,
            decoded.perCommandRules["git"]?.allowedSubcommands,
        )
    }
}
