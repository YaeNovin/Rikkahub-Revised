package me.rerere.rikkahub.data.memory

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.MemoryRepository

data class MemoryDataCounts(val memories: Int, val chunks: Int)

class MemoryMaintenanceService(private val database: AppDatabase, private val repository: MemoryRepository) {
    suspend fun counts(): MemoryDataCounts = withContext(Dispatchers.IO) {
        MemoryDataCounts(database.memoryDao().countAllMemories(), database.conversationMemoryDao().countAllChunks())
    }

    suspend fun clearAll(): MemoryDataCounts = withContext(Dispatchers.IO) {
        var removed = MemoryDataCounts(0, 0)
        repository.withBulkDeletion {
            database.withTransaction {
                removed = counts()
                database.conversationMemoryDao().clearAllForUser()
                database.memoryDao().deleteAllMemories()
            }
        }
        removed
    }
}
