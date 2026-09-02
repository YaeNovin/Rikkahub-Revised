package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    respectIntrinsicSize: Boolean = false,
) {
    var showImageViewer by remember(model) { mutableStateOf(false) }
    var intrinsicSize by remember(model) { mutableStateOf(Size.Unspecified) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val coilModel = remember(context, model, placeholder, export) {
        ImageRequest.Builder(context)
            .data(model)
            .placeholder(placeholder)
            .crossfade(false)
            .allowHardware(!export)
            .build()
    }
    var loading by remember(model) { mutableStateOf(false) }
    val displaySize = if (respectIntrinsicSize) inlineImageDisplaySizeDp(intrinsicSize) else Size.Unspecified
    val displaySizeModifier = if (displaySize.isUsableImageSize()) {
        Modifier.size(displaySize.width.dp, displaySize.height.dp)
    } else {
        Modifier
    }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .then(displaySizeModifier)
            .shimmer(isLoading = loading)
            .clickable {
                showImageViewer = true
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = { state ->
            loading = false
            intrinsicSize = state.painter.intrinsicSize
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(
            images = listOf(model ?: ""),
            initialSizes = listOf(intrinsicSize),
        ) {
            showImageViewer = false
        }
    }
}

internal fun inlineImageDisplaySizeDp(
    intrinsicSize: Size,
    maxWidthDp: Float = 360f,
    maxHeightDp: Float = 280f,
): Size {
    if (!intrinsicSize.isUsableImageSize()) return Size.Unspecified
    val scale = minOf(
        1f,
        maxWidthDp / intrinsicSize.width,
        maxHeightDp / intrinsicSize.height,
    )
    return Size(intrinsicSize.width * scale, intrinsicSize.height * scale)
}

private fun Size.isUsableImageSize(): Boolean =
    width.isFinite() && height.isFinite() && width > 0f && height > 0f
