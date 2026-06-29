package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * workspace_read_shell 的只读判定规则集 —— 数据驱动配置。
 *
 * 规则从 `assets/shell/readonly_rules.json` 加载（[loadReadonlyShellRules]），
 * 调整只读边界只改该 JSON 文件、不动代码。规则由 [checkReadonlyCommand] 解释执行。
 *
 * 规则语义（按 [checkReadonlyCommand] 的判定顺序）：
 * 1. 命令为空 → 拒。
 * 2. [rejectIfContainsLiteral]：命令文本含任一字面量 → 拒（堵 `$(、反引号、>>、>|`）。
 * 3. [rejectIfRegex]：命令文本命中任一正则 → 拒（堵 shell 输出重定向 `> file`）。
 * 4. 按 `&&/||/;|` 分割子命令，每段取首 token 的 base 名：
 *    a. base 不在 [allowedBaseCommands] → 拒（白名单默认拒绝）。
 *    b. [perCommandRules] 里该 base 的专属规则（见 [PerCommandRule]）。
 *
 * @param allowedBaseCommands 允许的命令 base 名集合（不含路径前缀）。
 * @param rejectIfContainsLiteral 命令文本含任一字面量即拒。
 * @param rejectIfRegex 命令文本命中任一正则即拒。
 * @param perCommandRules 按命令 base 名索引的专属规则。
 */
@Serializable
data class ReadonlyShellRules(
    val allowedBaseCommands: Set<String> = emptySet(),
    val rejectIfContainsLiteral: List<String> = emptyList(),
    val rejectIfRegex: List<String> = emptyList(),
    val perCommandRules: Map<String, PerCommandRule> = emptyMap(),
)

/**
 * 某个命令 base 的专属只读规则。所有字段可选，未设即不检查该项。
 *
 * @param rejectIfAnyFlagEquals 任一 token 等于这些值之一即拒（如 sed `--in-place`、find `-delete`）。
 * @param rejectIfAnyFlagStartsWith 任一 token 以这些前缀开头即拒（如 sed `-i`/`-i''`）。
 * @param rejectIfAnyFlagContains 任一 token 含这些子串即拒（如 awk `inplace`）。
 * @param rejectIfSubcommandContains 子命令文本含这些字面量即拒（如 awk 输出 `>`）。
 * @param allowedSubcommands 只允许的子命令集合（取第一个非 `-` 开头的 token 为子命令；
 *   为空表示不限定子命令）。如 git 只读子命令白名单。
 */
@Serializable
data class PerCommandRule(
    val rejectIfAnyFlagEquals: List<String> = emptyList(),
    val rejectIfAnyFlagStartsWith: List<String> = emptyList(),
    val rejectIfAnyFlagContains: List<String> = emptyList(),
    val rejectIfSubcommandContains: List<String> = emptyList(),
    val allowedSubcommands: Set<String> = emptySet(),
)

/// 解析规则集用的 Json（容错：忽略未知字段，便于向前兼容增量字段）。
private val rulesJson by lazy {
    Json { ignoreUnknownKeys = true }
}

/// 从 `assets/shell/readonly_rules.json` 加载规则集。文件缺失时返回空规则（fail-closed：
/// 白名单为空 → 一切命令被拒，安全方向失败）。
fun loadReadonlyShellRules(context: Context): ReadonlyShellRules =
    runCatching {
        context.assets.open("shell/readonly_rules.json").use { input ->
            rulesJson.decodeFromString<ReadonlyShellRules>(input.bufferedReader().readText())
        }
    }.getOrElse { ReadonlyShellRules() }
