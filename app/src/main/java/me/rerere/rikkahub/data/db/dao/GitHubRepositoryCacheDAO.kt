package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.GitHubRepositoryCacheEntity
import me.rerere.rikkahub.data.db.entity.GitHubApiCacheStateEntity

@Dao
interface GitHubRepositoryCacheDAO {
    @Query("SELECT * FROM github_repository_cache WHERE fullName = :fullName LIMIT 1")
    suspend fun get(fullName: String): GitHubRepositoryCacheEntity?
    @Query("SELECT * FROM github_repository_cache WHERE fullName = :fullName LIMIT 1")
    fun observe(fullName: String): kotlinx.coroutines.flow.Flow<GitHubRepositoryCacheEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: GitHubRepositoryCacheEntity)

    @Query("UPDATE github_repository_cache SET lastAccessAt = :now WHERE fullName = :fullName")
    suspend fun touch(fullName: String, now: Long)

    @Query("SELECT * FROM github_api_cache_state WHERE id = 'public'")
    suspend fun apiState(): GitHubApiCacheStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putApiState(state: GitHubApiCacheStateEntity)

    @Query("DELETE FROM github_repository_cache WHERE fullName NOT IN (SELECT fullName FROM github_repository_cache ORDER BY lastAccessAt DESC, fullName LIMIT 500)")
    suspend fun trim()
}
