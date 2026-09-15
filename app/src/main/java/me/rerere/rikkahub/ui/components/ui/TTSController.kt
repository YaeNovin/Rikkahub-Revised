package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus

@Composable
fun TTSController() {
    val context = LocalContext.current
    val ttsState = LocalTTSState.current

    val isSpeaking by ttsState.isSpeaking.collectAsState()
    val error by ttsState.error.collectAsState()
    var isVisible by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val currentPlayback by ttsState.playbackState.collectAsState()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(error) { if (error != null) { isVisible = true; showDetails = true } }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("语音详情") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.onSurface) }
            currentPlayback.metadata["spoken_text"]?.let { Text("实际朗读文本：\n$it") }
            currentPlayback.metadata["subtitle"]?.let { Text("字幕：\n$it") }
            currentPlayback.metadata["subtitle_url"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                TextButton(colors = appearanceTextButtonColors(), onClick = { uriHandler.openUri(it) }) { Text("查看供应商字幕文件") }
            }
            currentPlayback.metadata["request_id"]?.let { Text("请求 ID：$it") }
            if (error == null && currentPlayback.metadata.keys.none { it in listOf("spoken_text", "subtitle", "subtitle_url") })
                Text("此段音频没有附加字幕或改写文本。")
        } },
        confirmButton = { TextButton(colors = appearanceTextButtonColors(), onClick = { showDetails = false }) { Text("关闭") } },
    )

    LaunchedEffect(isSpeaking) {
        if (isSpeaking) {
            // 如果开启，显示悬浮窗
            isVisible = true
        }
    }

    FloatingWindow(
        tag = "tts_controller",
        visibility = isVisible
    ) {
        val playbackState by ttsState.playbackState.collectAsState()
        var expand by remember { mutableStateOf(false) }
        IsolatedOverlaySurface(
            shape = CircleShape,
            modifier = Modifier.padding(8.dp).shadow(4.dp, CircleShape),
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayPauseButton(playbackState = playbackState, ttsState = ttsState)

                IconButton(
                    onClick = {
                        ttsState.stop()
                        isVisible = false
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = null,
                    )
                }

                AnimatedVisibility(expand) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpeedButton(playbackState, ttsState)

                        if (playbackState.metadata["streaming"] != "true") FastForwardButton(ttsState = ttsState)
                        TextButton(colors = appearanceTextButtonColors(), onClick = { showDetails = true }) { Text("详情") }
                    }
                }

                IconButton(
                    onClick = {
                        expand = !expand
                    }
                ) {
                    Icon(
                        imageVector = if (expand) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun FastForwardButton(ttsState: CustomTtsState) {
    IconButton(
        onClick = {
            ttsState.fastForward(5000)
        }
    ) {
        Icon(
            imageVector = HugeIcons.Forward02,
            contentDescription = null,
        )
    }
}

@Composable
private fun PlayPauseButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState
) {
    FilledTonalIconButton(
        onClick = {
            when (playbackState.status) {
                PlaybackStatus.Playing, PlaybackStatus.Buffering -> {
                    ttsState.pause()
                }

                else -> {
                    ttsState.resume()
                }
            }
        },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Icon(
            imageVector = if (playbackState.status == PlaybackStatus.Playing || playbackState.status == PlaybackStatus.Buffering) HugeIcons.Pause else HugeIcons.Play,
            contentDescription = null,
        )
        if (playbackState.status == PlaybackStatus.Playing || playbackState.status == PlaybackStatus.Buffering || playbackState.status == PlaybackStatus.Paused) {
            CircularProgressIndicator(
                progress = {
                    if (playbackState.status == PlaybackStatus.Playing) {
                        (playbackState.positionMs.toFloat() / playbackState.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                color = MaterialTheme.colorScheme.tertiary,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
            CircularProgressIndicator(
                progress = {
                    if (playbackState.status == PlaybackStatus.Playing) {
                        (playbackState.currentChunkIndex.toFloat() / playbackState.totalChunks.coerceAtLeast(1)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(2.dp),
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
        }
    }
}

@Composable
private fun SpeedButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState
) {
    TextButton(
        colors = appearanceTextButtonColors(),
        onClick = {
            when (playbackState.speed) {
                0.8f -> {
                    ttsState.setSpeed(1.0f)
                }

                1.0f -> {
                    ttsState.setSpeed(1.2f)
                }

                1.2f -> {
                    ttsState.setSpeed(1.5f)
                }

                1.5f -> {
                    ttsState.setSpeed(0.8f)
                }

                else -> {
                    ttsState.setSpeed(1.0f)
                }
            }
        }
    ) {
        Text(text = "x${"%.1f".format(playbackState.speed)}")
    }
}
