package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GenMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo(name = "type", defaultValue = TYPE_IMAGE_GENERATION)
    val type: String = TYPE_IMAGE_GENERATION,
    @ColumnInfo("source_paths")
    val sourcePaths: String? = null,
    @ColumnInfo("provider_name")
    val providerName: String? = null,
    @ColumnInfo("width")
    val width: Int? = null,
    @ColumnInfo("height")
    val height: Int? = null,
    @ColumnInfo("format")
    val format: String? = null,
    @ColumnInfo("seed")
    val seed: Long? = null,
) {
    companion object {
        const val TYPE_IMAGE_GENERATION = "image_generation"
        const val TYPE_IMAGE_EDIT = "image_edit"
    }
}
