package me.rerere.rikkahub.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Room @Relation 数据类：一次事务内联表查询 Conversation + MessageNode，
 * 消除 N+1 逐条查询 message_node 表的问题。
 *
 * parentColumn = ConversationEntity.id
 * entityColumn  = MessageNodeEntity.conversationId
 * orderBy      = 保持与原始 [MessageNodeDAO.getNodesOfConversation] 一致的排序语义
 *
 * 配合 @Transaction 注解使用，Room 会在同一事务内先查 conversation 列表，
 * 再用 WHERE conversation_id IN (...) 批量查出所有消息节点。
 */
data class ConversationWithNodes(
    @Embedded
    val conversation: ConversationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "conversation_id",
        orderBy = "node_index ASC"
    )
    val nodes: List<MessageNodeEntity>
)
