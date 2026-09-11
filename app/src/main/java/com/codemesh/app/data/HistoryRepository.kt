package com.codemesh.app.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class HistoryRepository(private val db: AppDatabase) {
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()

    // Session operations
    fun getAllSessions(): Flow<List<Session>> = sessionDao.getAllSessions()
    
    suspend fun createSession(title: String): Session {
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = title.take(40),
            createdAt = System.currentTimeMillis()
        )
        sessionDao.insert(session)
        return session
    }
    
    suspend fun renameSession(sessionId: String, title: String) {
        sessionDao.updateTitle(sessionId, title.take(40))
    }
    
    suspend fun deleteSession(sessionId: String) {
        sessionDao.delete(sessionId)  // CASCADE deletes messages
    }

    // Message operations
    fun getMessages(sessionId: String): Flow<List<Message>> = 
        messageDao.getBySessionId(sessionId)

    suspend fun addMessage(sessionId: String, role: String, content: String): Message {
        val msg = Message(
            sessionId = sessionId,
            role = role,
            content = content,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insert(msg)
        return msg
    }

    /**
     * Build the messages[] array for the bridge API.
     * Prepends system prompt, then includes last [maxTurns] messages.
     * Caps total serialized JSON size to approximately 8KB.
     */
    suspend fun buildMessagesPayload(
        sessionId: String,
        systemPrompt: String,
        maxTurns: Int = 20
    ): List<Map<String, String>> {
        val recentMessages = messageDao.getRecentMessages(sessionId, maxTurns)
            .reversed()  // getRecentMessages returns DESC, we need ASC
        
        val result = mutableListOf<Map<String, String>>()
        result.add(mapOf("role" to "system", "content" to systemPrompt))
        
        var totalSize = systemPrompt.length + 50  // rough overhead for JSON structure
        val maxSize = 8000  // ~8KB cap
        
        for (msg in recentMessages) {
            val msgSize = msg.content.length + msg.role.length + 30
            if (totalSize + msgSize > maxSize) break
            result.add(mapOf("role" to msg.role, "content" to msg.content))
            totalSize += msgSize
        }
        
        return result
    }
}
