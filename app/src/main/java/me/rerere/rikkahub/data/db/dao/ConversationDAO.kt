package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationWithNodes
import me.rerere.rikkahub.data.repository.LightConversationEntity

@Dao
interface ConversationDAO {
    /** @Deprecated 全表查询无分页（OOM 风险），请改用 [getAllPaging] */
    @Deprecated(
        "全表查询无分页，存在 OOM 风险；请使用 getAllPaging() 替代",
        ReplaceWith("getAllPaging()")
    )
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    /** @Deprecated 全表查询无分页（OOM 风险），请改用 [getConversationsOfAssistantPaging] */
    @Deprecated(
        "全表查询无分页，存在 OOM 风险；请使用 getConversationsOfAssistantPaging() 替代",
        ReplaceWith("getConversationsOfAssistantPaging(assistantId)")
    )
    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    /**
     * 批量加载最近 N 条 conversations + 对应的 message nodes（一次事务，消除 N+1）。
     * 内部由 Room @Relation 机制在事务内执行：
     *   1. SELECT * FROM conversationentity WHERE assistant_id=? ORDER BY … LIMIT ?
     *   2. SELECT * FROM message_node WHERE conversation_id IN (?, ?, …)
     */
    @Transaction
    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsWithNodes(assistantId: String, limit: Int): List<ConversationWithNodes>

    /**
     * 加载单条 conversation + 对应 message nodes（@Relation 一次事务联表）。
     */
    @Transaction
    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationWithNodesById(id: String): ConversationWithNodes?

    /**
     * 批量加载多条 conversations + 对应 message nodes（@Relation 一次事务联表）。
     * 用于需要同时加载多个完整 conversation 的场景（如 rebuildAllIndexes）。
     */
    @Transaction
    @Query("SELECT * FROM conversationentity WHERE id IN (:ids)")
    suspend fun getConversationsWithNodesByIds(ids: List<String>): List<ConversationWithNodes>

    /** @Deprecated 全表搜索无分页（OOM 风险），请改用 [searchConversationsPaging] */
    @Deprecated(
        "全表搜索无分页，存在 OOM 风险；请使用 searchConversationsPaging() 替代",
        ReplaceWith("searchConversationsPaging(searchText)")
    )
    @Query("SELECT * FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    /** @Deprecated 全表搜索无分页（OOM 风险），请改用 [searchConversationsOfAssistantPaging] */
    @Deprecated(
        "全表搜索无分页，存在 OOM 风险；请使用 searchConversationsOfAssistantPaging() 替代",
        ReplaceWith("searchConversationsOfAssistantPaging(assistantId, searchText)")
    )
    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    /** @Deprecated 全表查询 pinned conversations 无分页。通常 pinned 数量有限，暂无风险但后续可加 LIMIT 变体。 */
    @Deprecated(
        "全表查询无分页；若数据量增长请考虑添加带 LIMIT 的变体",
        ReplaceWith("保留使用，暂无直接替换")
    )
    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun countAll(): Int

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)
