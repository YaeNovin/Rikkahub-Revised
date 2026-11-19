package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.modifier.shimmer

/** Shared by manual image generation and the chat tool; preserves intrinsic aspect ratio. */
@Composable
fun GeneratedChatImage(url: String) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(0.82f).widthIn(max = 320.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZoomableAsyncImage(
            model = url, contentDescription = null,
            modifier = Modifier.widthIn(max = maxWidth).heightIn(max = 320.dp).clip(MaterialTheme.shapes.medium),
            respectIntrinsicSize = true, maxDisplayWidthDp = maxWidth.value, maxDisplayHeightDp = 320f,
        )
    }
}

@Composable
fun ImageGenerationSkeleton() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
        Text(stringResource(R.string.chat_image_generating), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth(0.82f).widthIn(max = 320.dp).aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .shimmer(isLoading = true, shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                backgroundColor = Color.Transparent, durationMillis = 1_100))
    }
}
