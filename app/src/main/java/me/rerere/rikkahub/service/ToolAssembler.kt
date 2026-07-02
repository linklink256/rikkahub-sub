package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceReadOnlyTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.common.android.Logging
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ToolAssembler"

/**
 * 工具组装器 —— 从 ChatService 提取的工具构建职责。
 *
 * 负责为主代理和子代理构建基础工具集（搜索 / 本地 / workspace / skills / mcp），
 * 以及技能的自动清理与发现。通过将工具构建逻辑集中在此类，
 * ChatService 不再需要直接依赖 McpManager、LocalTools、SkillManager 等工具相关的依赖。
 *
 * MCP 名非法时的处理策略通过 [McpErrorStrategy] 区分：
 * - STRICT（主代理）：检测到非法名时返回 [CommonToolsResult.McpError]，由调用方上报错误
 * - SOFT（子代理）：静默过滤非法名，不抛错、不打断
 *
 * @param appScope             用于异步回写技能清理结果
 * @param settingsStore        用于读取/更新助手配置
 * @param mcpManager           MCP 服务器管理器
 * @param localTools           本地工具集
 * @param conversationRepo     对话仓库（用于创建对话引用工具）
 * @param skillManager         技能管理器
 * @param workspaceRepository  workspace 仓库
 */
class ToolAssembler(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val localTools: LocalTools,
    private val conversationRepo: ConversationRepository,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    /**
     * MCP 名非法时的处理策略。
     */
    enum class McpErrorStrategy {
        /** 主代理：检测到非法名时返回 [CommonToolsResult.McpError]，由调用方上报错误 */
        STRICT,
        /** 子代理：静默过滤非法名，不抛错、不打断，并按 [Assistant.mcpServers] 过滤 */
        SOFT,
    }

    /**
     * [buildCommonTools] 的返回类型。
     */
    sealed class CommonToolsResult {
        data class Ok(val tools: List<Tool>) : CommonToolsResult()
        data class McpError(val invalidNames: List<String>) : CommonToolsResult()
    }

    /**
     * 构建主代理/子代理共享的基础工具集（搜索 / 本地 / workspace / skills / mcp）。
     *
     * 提取自根代理 buildList 的非 delegateOnly 分支和 buildSubagentBaseTools，
     * 消除两处几乎完全相同的工具装配代码。差异仅在 MCP 非法名称的处理方式：
     * - [McpErrorStrategy.STRICT]：检测到非法名时返回 [CommonToolsResult.McpError]，
     *   由调用方决定如何上报错误。
     * - [McpErrorStrategy.SOFT]：静默过滤非法名，并额外按 [Assistant.mcpServers]
     *   过滤（子代理场景）。
     */
    suspend fun buildCommonTools(
        assistant: Assistant,
        settings: Settings,
        workspaceCwd: String?,
        mcpStrategy: McpErrorStrategy,
    ): CommonToolsResult {
        val allMcpTools = mcpManager.getAllAvailableTools()

        if (mcpStrategy == McpErrorStrategy.STRICT) {
            val invalidNames = allMcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                return CommonToolsResult.McpError(invalidNames)
            }
        }

        val tools = buildList {
            if (settings.enableWebSearch) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepo, assistant.id))
            }
            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))

            val allInstalledSkills = skillManager.listSkills()
            if (allInstalledSkills.isNotEmpty()) {
                val installedSkillNames = allInstalledSkills.map { it.name }.toSet()
                val cleanedSkills = cleanStaleEnabledSkills(assistant, installedSkillNames)
                autoEnableNewSkills(assistant, cleanedSkills, installedSkillNames)
                addAll(
                    createSkillTools(
                        enabledSkills = installedSkillNames,
                        allSkills = allInstalledSkills,
                        skillManager = skillManager,
                        workspaceRepository = workspaceRepository,
                        workspaceId = assistant.workspaceId?.toString(),
                    )
                )
            }

            val filteredMcpTools = if (mcpStrategy == McpErrorStrategy.SOFT) {
                allMcpTools
                    .filter { (_, serverName, _) ->
                        serverName.isNotEmpty() && serverName.all {
                            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9'
                        }
                    }
                    .filter { (serverId, _, _) ->
                        // 按子代理配置的 MCP 服务器过滤；空集 = 不限制（继承父代理的场景）
                        assistant.mcpServers.isEmpty() || serverId in assistant.mcpServers
                    }
            } else {
                allMcpTools
            }

            filteredMcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }
        }

        return CommonToolsResult.Ok(tools)
    }

    /**
     * 构建 delegateOnly 模式下的受限工具集（只读能力）。
     *
     * 纯决策模式：主代理保留「只读」能力以便快速查看上下文，
     * 但不持有写入/编辑能力——实际执行交由子代理。
     */
    suspend fun buildDelegateOnlyTools(
        assistant: Assistant,
        settings: Settings,
        workspaceCwd: String?,
    ): List<Tool> = buildList {
        if (settings.enableWebSearch) {
            addAll(createSearchTools(settings))
        }
        addAll(localTools.getTools(assistant.localTools.filter {
            it == LocalToolOption.AskUser ||
                it == LocalToolOption.TimeInfo ||
                it == LocalToolOption.Clipboard ||
                it == LocalToolOption.Logs
        }))
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepo, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(
            assistant.workspaceId?.toString(),
            workspaceCwd,
            readOnly = true,
        ))
    }

    /**
     * 清理 [assistant] 的 [Assistant.enabledSkills] 中已不存在的技能名（幽灵技能）。
     *
     * 与 quickMessageIds / modeInjectionIds / lorebookIds / mcpServers 不同，
     * enabledSkills 在迁移阶段没有被过滤（因为 SkillManager 在 app 层，
     * PreferencesStore 无法访问磁盘上的技能列表）。此方法在每次构建工具前
     * 检查并异步回写清理后的值，确保持久化数据与磁盘一致。
     *
     * @return 清理后的 enabledSkills（如果无变化则返回原值）
     */
    private fun cleanStaleEnabledSkills(
        assistant: Assistant,
        installedSkillNames: Set<String>,
    ): Set<String> {
        if (assistant.enabledSkills.isEmpty()) return assistant.enabledSkills
        val cleaned = assistant.enabledSkills.filter { it in installedSkillNames }.toSet()
        if (cleaned.size == assistant.enabledSkills.size) return assistant.enabledSkills
        // 异步回写清理后的值
        appScope.launch {
            settingsStore.updateAssistantSkills(assistant.id) { cleaned }
        }
        Logging.log(TAG, "cleanStaleEnabledSkills: removed ${assistant.enabledSkills.size - cleaned.size} ghost skill(s) from assistant ${assistant.id}: ${assistant.enabledSkills - cleaned}")
        return cleaned
    }

    /**
     * 自动将磁盘上新安装的技能加入 [Assistant.enabledSkills]。
     *
     * 与 [cleanStaleEnabledSkills] 对称：后者清理已删除的幽灵技能，
     * 本方法发现已安装但未启用的技能并异步回写，使新技能在下一轮消息即可用，
     * 无需用户手动到 UI 中开启。这解决了通过 workspace_shell 直接写入
     * /skills/ 目录的技能无法被发现的问题——autoEnableSkill() 仅在
     * UI 层导入时触发，文件系统直写不走那条路径。
     *
     * @param enabledSkills 当前已启用的技能名集合（已清理幽灵）
     * @param installedSkillNames 磁盘上实际安装的技能名集合
     */
    private fun autoEnableNewSkills(
        assistant: Assistant,
        enabledSkills: Set<String>,
        installedSkillNames: Set<String>,
    ) {
        val newSkills = installedSkillNames - enabledSkills
        if (newSkills.isEmpty()) return
        appScope.launch {
            settingsStore.updateAssistantSkills(assistant.id) { it + newSkills }
        }
        Logging.log(TAG, "autoEnableNewSkills: enabled ${newSkills.size} new skill(s) for assistant ${assistant.id}: $newSkills")
    }

    /**
     * 如果 workspace 已就绪，构建 workspace 工具集；否则返回空列表。
     */
    suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String? = null,
        readOnly: Boolean = false,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return if (readOnly) {
            createWorkspaceReadOnlyTools(workspaceId, workspaceRepository, cwd)
        } else {
            createWorkspaceTools(workspaceId, workspaceRepository, cwd)
        }
    }
}
