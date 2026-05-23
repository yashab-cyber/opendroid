package com.opendroid.ai.data.db.dao

import androidx.room.*
import com.opendroid.ai.data.db.entities.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("""SELECT * FROM memories 
              WHERE ttlHours = -1 
              OR (timestamp + (ttlHours * 3600000)) > :now""")
    suspend fun getValidMemories(now: Long = System.currentTimeMillis()): List<MemoryEntity>

    @Query("""SELECT * FROM memories 
              WHERE type = :type 
              AND (ttlHours = -1 OR (timestamp + (ttlHours * 3600000)) > :now)""")
    suspend fun getValidMemoriesByType(type: String, now: Long = System.currentTimeMillis()): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE type = :type")
    suspend fun getMemoriesByType(type: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE `key` = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE `key` = :key")
    suspend fun deleteMemory(key: String)

    @Query("DELETE FROM memories WHERE type = :type")
    suspend fun clearMemoryByType(type: String)

    @Query("""DELETE FROM memories 
              WHERE ttlHours != -1 
              AND (timestamp + (ttlHours * 3600000)) <= :now""")
    suspend fun deleteExpiredMemories(now: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteMemoryEntity(memory: MemoryEntity)
}
