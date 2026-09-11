package com.codemesh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY created_at DESC")
    fun getAllSessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): Session?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: Session)

    @Query("UPDATE sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: String)
}
