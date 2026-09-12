package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "github_repository_cache", indices = [Index("lastAccessAt")])
data class GitHubRepositoryCacheEntity(
    @PrimaryKey val fullName: String,
    val payload: String?,
    val etag: String?,
    val fetchedAt: Long,
    val refreshAfter: Long,
    val lastAccessAt: Long,
    val error: String?,
)

/** Survives app restart; no tokens, prompts or private repository content are stored. */
@Entity(tableName = "github_api_cache_state")
data class GitHubApiCacheStateEntity(
    @PrimaryKey val id: String = "public",
    val blockedUntil: Long = 0,
    val windowStartedAt: Long = 0,
    val requests: Int = 0,
)
