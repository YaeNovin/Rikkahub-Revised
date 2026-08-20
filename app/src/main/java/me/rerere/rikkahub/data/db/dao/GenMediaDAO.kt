package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.GenMediaEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun getAll(): PagingSource<Int, GenMediaEntity>

    @Query(
        """
        SELECT * FROM genmediaentity
        WHERE TRIM(:query) = ''
            OR prompt LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
            OR model_id LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
            OR provider_name LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
            OR format LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
            OR CAST(seed AS TEXT) LIKE '%' || TRIM(:query) || '%'
            OR (CAST(width AS TEXT) || 'x' || CAST(height AS TEXT))
                LIKE '%' || TRIM(:query) || '%' COLLATE NOCASE
        ORDER BY create_at DESC
        """
    )
    fun search(query: String): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    @Query("SELECT * FROM genmediaentity WHERE create_at < :cutoffMillis ORDER BY create_at")
    suspend fun getMediaBefore(cutoffMillis: Long): List<GenMediaEntity>

    @Insert
    suspend fun insert(media: GenMediaEntity): Long

    @Query("DELETE FROM genmediaentity WHERE id = :id")
    suspend fun delete(id: Int)
}
