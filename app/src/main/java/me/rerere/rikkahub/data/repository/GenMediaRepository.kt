package me.rerere.rikkahub.data.repository

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.GenMediaFolderEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    fun searchMedia(query: String, folderId: String?): PagingSource<Int, GenMediaEntity> =
        dao.search(query, folderId)

    fun getFolders(): Flow<List<GenMediaFolderEntity>> = dao.getFolders()

    suspend fun getMediaBefore(cutoffMillis: Long): List<GenMediaEntity> =
        dao.getMediaBefore(cutoffMillis)

    suspend fun insertMedia(media: GenMediaEntity): Long = dao.insert(media)

    suspend fun insertMedia(media: List<GenMediaEntity>): List<Long> = dao.insert(media)

    suspend fun createFolder(folder: GenMediaFolderEntity) = dao.insertFolder(folder)

    suspend fun moveMediaToFolder(mediaId: Int, folderId: String?) =
        dao.moveToFolder(mediaId, folderId)

    suspend fun moveMediaToFolder(mediaIds: List<Int>, folderId: String?) =
        dao.moveManyToFolder(mediaIds, folderId)

    suspend fun getMediaInFolder(folderId: String): List<GenMediaEntity> =
        dao.getMediaInFolder(folderId)

    suspend fun dissolveFolder(folderId: String) = dao.dissolveFolder(folderId)

    suspend fun deleteFolderWithContents(folderId: String) =
        dao.deleteFolderWithContents(folderId)

    suspend fun deleteMedia(id: Int) = dao.delete(id)

    suspend fun deleteMedia(ids: List<Int>) = dao.deleteMany(ids)
}
