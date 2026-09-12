package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "video_generation_output",
    primaryKeys = ["task_id", "output_index"],
    foreignKeys = [
        ForeignKey(
            entity = VideoGenerationTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("task_id")],
)
data class VideoGenerationOutputEntity(
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "output_index")
    val outputIndex: Int,
    @ColumnInfo(name = "remote_url")
    val remoteUrl: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,
    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,
    @ColumnInfo(name = "local_relative_path")
    val localRelativePath: String? = null,
    @ColumnInfo(name = "local_thumbnail_path")
    val localThumbnailPath: String? = null,
    @ColumnInfo(name = "width")
    val width: Int? = null,
    @ColumnInfo(name = "height")
    val height: Int? = null,
    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long? = null,
    @ColumnInfo(name = "url_expires_at")
    val urlExpiresAt: Long? = null,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long? = null,
)
