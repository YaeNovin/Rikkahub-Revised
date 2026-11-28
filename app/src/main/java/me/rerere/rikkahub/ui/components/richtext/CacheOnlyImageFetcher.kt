package me.rerere.rikkahub.ui.components.richtext

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.io.IOException

/** Serve a remote image from disk after Coil's memory lookup, without entering any HTTP client. */
internal object CacheOnlyImageFetcher : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme !in setOf("http", "https")) return null
        return object : Fetcher {
            override suspend fun fetch(): SourceFetchResult {
                val cache = imageLoader.diskCache ?: throw IOException("Image is not cached")
                val key = options.diskCacheKey ?: data.toString()
                val snapshot = cache.openSnapshot(key) ?: throw IOException("Image is not cached")
                return try {
                    SourceFetchResult(
                        source = ImageSource(snapshot.data, cache.fileSystem, diskCacheKey = key, closeable = snapshot),
                        mimeType = null,
                        dataSource = DataSource.DISK,
                    )
                } catch (error: Throwable) { snapshot.close(); throw error }
            }
        }
    }
}
