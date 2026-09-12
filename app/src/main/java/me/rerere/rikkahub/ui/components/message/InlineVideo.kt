package me.rerere.rikkahub.ui.components.message

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@Composable
fun InlineVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playing by remember(url) { mutableStateOf(false) }
    var fullscreen by remember(url) { mutableStateOf(false) }
    val thumbnail by produceState<File?>(null, url) {
        value = withContext(Dispatchers.IO) {
            // Only decode local content; remote signed URLs are not opened just to show a card.
            if (!url.startsWith("file:") && !url.startsWith("content:")) return@withContext null
            runCatching {
                val directory = File(context.cacheDir, "video-thumbnails").apply { mkdirs() }
                val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val target = File(directory, "$key.jpg")
                synchronized(VideoThumbnailLock) {
                    if (!target.isFile) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, android.net.Uri.parse(url))
                            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 480
                            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 270
                            val scale = 480f / maxOf(width, height, 1)
                            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                            } else null
                            if (frame != null) {
                                try { target.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
                                finally { frame.recycle() }
                            }
                        } finally { retriever.release() }
                    }
                    var total = directory.listFiles().orEmpty().sumOf { it.length() }
                    directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
                        if (total > 32L * 1024 * 1024 && it != target) {
                            val size = it.length()
                            if (it.delete()) total -= size
                        }
                    }
                    target.takeIf { it.isFile && it.length() > 0 }
                }
            }.getOrNull()
        }
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (playing && !fullscreen) VideoPlayerSurface(url, Modifier.fillMaxSize())
            else AsyncImage(model = thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Row {
            TextButton(onClick = { playing = !playing }) { Text(if (playing) "停止播放" else "播放") }
            TextButton(onClick = { fullscreen = true }) { Text("全屏") }
        }
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                TextButton(onClick = { fullscreen = false }) { Text("关闭预览") }
                VideoPlayerSurface(url, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun VideoPlayerSurface(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(url)); prepare()
    } }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier,
        onRelease = { it.player = null })
}

private object VideoThumbnailLock
