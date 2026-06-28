package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationWithNodes
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    /**
     * 加载最近 N 条会话（含完整消息节点）。
     *
     * 使用 @Relation + @Transaction 在单次事务内批量加载所有消息节点，
     * 消除了原先 foreach 逐条查 [loadMessageNodes] 的 N+1 问题。
     * 同时批量加载所有 relevant 收藏状态。
     */
    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        val relations = conversationDAO.getRecentConversationsWithNodes(
            assistantId = assistantId.toString(),
            limit = limit
        )
        if (relations.isEmpty()) return emptyList()

        // 批量加载这些 conversation 的收藏状态（一次查询，消除 favorite N+1）
        val favoriteMap = loadFavoriteMapForConversations(relations.map { it.conversation.id })

        return relations.map { relation ->
            conversationWithNodesToConversation(relation, favoriteMap)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> =
        conversationPagingFlow { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult =
        loadConversationPage(
            conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()),
            offset,
            limit
        )

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult =
        loadConversationPage(
            conversationDAO.searchConversationsOfAssistantPaging(
                assistantId = assistantId.toString(),
                searchText = titleKeyword
            ),
            offset,
            limit
        )

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> =
        conversationPagingFlow { conversationDAO.searchConversationsPaging(titleKeyword) }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        conversationPagingFlow {
            conversationDAO.searchConversationsOfAssistantPaging(
                assistantId.toString(),
                titleKeyword
            )
        }

    /**
     * 加载完整会话（含消息节点）。
     * 使用 @Relation + @Transaction 一次联表查询，消除独立的 message_node 查询。
     */
    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val relation = conversationDAO.getConversationWithNodesById(uuid.toString())
        return if (relation != null) {
            val favoriteMap = loadFavoriteMapForConversations(listOf(relation.conversation.id))
            conversationWithNodesToConversation(relation, favoriteMap)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(conversation.id.toString())
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
        )
    }

    /**
     * 将 @Relation 批量查询结果 [ConversationWithNodes] 转换为领域模型 [Conversation]。
     *
     * @param favoriteMap  conversationId → 该会话下被收藏的 nodeId 集合（由 [loadFavoriteMapForConversations] 预加载）
     */
    private fun conversationWithNodesToConversation(
        relation: ConversationWithNodes,
        favoriteMap: Map<String, Set<Uuid>>
    ): Conversation {
        val favoriteNodeIds = favoriteMap[relation.conversation.id] ?: emptySet()
        val nodes = relation.nodes
            .sortedBy { it.nodeIndex }
            .map { entity ->
            MessageNode(
                id = Uuid.parse(entity.id),
                messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages),
                selectIndex = entity.selectIndex,
                isFavorite = favoriteNodeIds.contains(Uuid.parse(entity.id))
            )
        }.filter { it.messages.isNotEmpty() }

        return conversationEntityToConversation(relation.conversation, nodes)
    }

    /**
     * 批量加载多个 conversation 下所有消息节点的收藏状态。
     * 使用 [FavoriteDAO.getAllNodeFavoriteRefs] 一次查询全部 node 收藏，
     * 按 conversationId 分组返回，避免对每个 conversation 单独查询 favorites 表（消除 N+1）。
     *
     * ref_key 格式: "node:{conversationId}:{nodeId}"
     */
    private suspend fun loadFavoriteMapForConversations(
        conversationIds: List<String>
    ): Map<String, Set<Uuid>> {
        val allRefs = favoriteDAO.getAllNodeFavoriteRefs()
        val result = mutableMapOf<String, MutableSet<Uuid>>()
        for (ref in allRefs) {
            // ref = "node:{conversationId}:{nodeId}"
            val parts = ref.removePrefix("node:").split(":", limit = 2)
            if (parts.size == 2) {
                val convId = parts[0]
                // 只关心传入的 conversationIds，过滤掉不相干的
                if (convId in conversationIds) {
                    val nodeId = runCatching { Uuid.parse(parts[1]) }.getOrNull()
                    if (nodeId != null) {
                        result.getOrPut(convId) { mutableSetOf() }.add(nodeId)
                    }
                }
            }
        }
        return result
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    // ponytail: shared paging helpers — dedup'd from 2 manual load + 3 Pager blocks
    private fun conversationPagingFlow(
        pagingSourceFactory: () -> PagingSource<Int, LightConversationEntity>
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = pagingSourceFactory
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int
    ): ConversationPageResult = try {
        when (
            val result = pagingSource.load(
                PagingSource.LoadParams.Refresh(
                    key = if (offset == 0) null else offset,
                    loadSize = limit,
                    placeholdersEnabled = false
                )
            )
        ) {
            is PagingSource.LoadResult.Page -> ConversationPageResult(
                items = result.data.map { entity ->
                    conversationSummaryToConversation(entity)
                },
                nextOffset = result.nextKey
            )

            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
        }
    } finally {
        pagingSource.invalidate()
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
