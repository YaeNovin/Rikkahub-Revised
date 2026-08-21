package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.GenMediaFolderEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun getAll(): PagingSource<Int, GenMediaEntity>

    @Query(
        """
        SELECT * FROM genmediaentity
        WHERE (:folderId IS NULL OR folder_id = :folderId)
            AND (
                TRIM(:query) = ''
                OR prompt LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
                OR model_id LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
                OR provider_name LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
                OR format LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
                OR CAST(seed AS TEXT) LIKE '%' || TRIM(:query) || '%'
                OR (CAST(width AS TEXT) || 'x' || CAST(height AS TEXT))
                    LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
            )
        ORDER BY create_at DESC
        """
    )
    fun search(query: String, folderId: String?): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM gen_media_folder ORDER BY create_at ASC")
    fun getFolders(): Flow<List<GenMediaFolderEntity>>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    @Query("SELECT * FROM genmediaentity WHERE create_at < :cutoffMillis ORDER BY create_at")
    suspend fun getMediaBefore(cutoffMillis: Long): List<GenMediaEntity>

    @Insert
    suspend fun insert(media: GenMediaEntity): Long

    @Insert
    suspend fun insert(media: List<GenMediaEntity>): List<Long>

    @Insert
    suspend fun insertFolder(folder: GenMediaFolderEntity)

    @Query("UPDATE genmediaentity SET folder_id = :folderId WHERE id = :mediaId")
    suspend fun moveToFolder(mediaId: Int, folderId: String?)

    @Query("UPDATE genmediaentity SET folder_id = :folderId WHERE id IN (:mediaIds)")
    suspend fun moveChunkToFolder(mediaIds: List<Int>, folderId: String?)

    @Transaction
    suspend fun moveManyToFolder(mediaIds: List<Int>, folderId: String?) {
        mediaIds.chunked(SQLITE_BATCH_SIZE).forEach { moveChunkToFolder(it, folderId) }
    }

    @Query("UPDATE genmediaentity SET folder_id = NULL WHERE folder_id = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT * FROM genmediaentity WHERE folder_id = :folderId")
    suspend fun getMediaInFolder(folderId: String): List<GenMediaEntity>

    @Query("DELETE FROM genmediaentity WHERE folder_id = :folderId")
    suspend fun deleteMediaInFolder(folderId: String)

    @Query("DELETE FROM gen_media_folder WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Transaction
    suspend fun dissolveFolder(folderId: String) {
        clearFolder(folderId)
        deleteFolder(folderId)
    }

    @Transaction
    suspend fun deleteFolderWithContents(folderId: String) {
        deleteMediaInFolder(folderId)
        deleteFolder(folderId)
    }

    @Query("DELETE FROM genmediaentity WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM genmediaentity WHERE id IN (:ids)")
    suspend fun deleteChunk(ids: List<Int>)

    @Transaction
    suspend fun deleteMany(ids: List<Int>) {
        ids.chunked(SQLITE_BATCH_SIZE).forEach { deleteChunk(it) }
    }

    companion object {
        private const val SQLITE_BATCH_SIZE = 900
    }
}
