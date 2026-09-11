package com.codemesh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun getBySessionId(sessionId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<Message>

    @Insert
    suspend fun insert(message: Message)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
