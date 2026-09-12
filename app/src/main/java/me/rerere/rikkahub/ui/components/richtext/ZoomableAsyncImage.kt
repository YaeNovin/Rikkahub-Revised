package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import me.rerere.rikkahub.utils.openUrl
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
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
    maxDisplayWidthDp: Float = 360f,
    maxDisplayHeightDp: Float = 280f,
    linkUrl: String? = null,
    requestedWidthDp: Float? = null,
    requestedHeightDp: Float? = null,
) {
    var showImageViewer by remember(model) { mutableStateOf(false) }
    var intrinsicSize by remember(model) { mutableStateOf(Size.Unspecified) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val badge = isCompactBadgeImage(model, intrinsicSize)
    val targetLink = safeImageLink(linkUrl)
    var retry by remember(model) { mutableIntStateOf(0) }
    var loading by remember(model, retry) { mutableStateOf(true) }
    var failure by remember(model, retry) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val coilModel = remember(context, model, placeholder, export, respectIntrinsicSize, badge, retry) {
        ImageRequest.Builder(context)
            .data(model)
            .apply {
                // A gateway can return an HTML error page with HTTP 200 and a long
                // cache lifetime. A manual retry must fetch again, not decode it forever.
                if (retry > 0) {
                    memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                }
                if (!badge) placeholder(placeholder)
                // Preserve CSS pixel dimensions; drawing vectors at the final
                // canvas scale also keeps high-density badge text crisp.
                if (respectIntrinsicSize || badge) {
                    decoderFactory(IntrinsicSvgDecoderFactory)
                    memoryCacheKey("inline-css-image:v3:$model")
                }
                if (badge) size(coil3.size.Size.ORIGINAL)
            }
            .crossfade(false)
            .allowHardware(!export)
            .build()
    }
    me.rerere.rikkahub.ui.components.ui.AwaitExportRender(loading)
    val displaySize = if (respectIntrinsicSize || badge) {
        requestedInlineImageSize(intrinsicSize, requestedWidthDp, requestedHeightDp, maxDisplayWidthDp, maxDisplayHeightDp)
    } else {
        Size.Unspecified
    }
    val displaySizeModifier = if (displaySize.isUsableImageSize()) {
        Modifier.size(displaySize.width.dp, displaySize.height.dp)
    } else {
        Modifier
    }
    val imageModifier = modifier.then(displaySizeModifier).clip(inlineImageShape(model, intrinsicSize))
        .then(if (badge && !displaySize.isUsableImageSize()) Modifier.widthIn(min = 48.dp, max = maxDisplayWidthDp.dp).heightIn(min = 20.dp, max = 28.dp) else Modifier)
        .combinedClickable(
            onClick = { if (failure != null) retry++ else if (targetLink != null) context.openUrl(targetLink) else showImageViewer = true },
            onLongClick = { showImageViewer = true },
            onClickLabel = if (failure != null) "重新加载图片" else if (targetLink != null) "打开图片链接" else "预览图片",
            onLongClickLabel = "放大预览图片",
        )
    if (failure != null) {
        Text(
            text = listOfNotNull(contentDescription?.takeIf { it.isNotBlank() }, failure, "点击重试").joinToString(" · "),
            // Do not squeeze an error + retry control into a 20dp badge or reuse its last successful size.
            modifier = modifier.widthIn(min = 48.dp, max = maxDisplayWidthDp.dp)
                .combinedClickable(onClick = { retry++ }, onLongClick = { showImageViewer = true }, onClickLabel = "重新加载图片")
                .padding(4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    else AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = imageModifier.semantics { stateDescription = if (loading) "图片加载中" else "图片已加载" }
            .shimmer(isLoading = loading && !export),
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
            failure = null
        },
        onSuccess = { state ->
            loading = false
            intrinsicSize = state.painter.intrinsicSize
        },
        onError = { state ->
            loading = false
            failure = inlineImageFailureReason(state.result.throwable)
            val details = inlineImageFailureDetails(model, state.result.throwable)
            scope.launch(Dispatchers.IO) {
                Logging.logError(name = "聊天图片加载失败", summary = inlineImageFailureReason(state.result.throwable),
                    details = details, tag = "INLINE_IMAGE")
            }
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(
            images = listOf(model ?: ""),
            initialSizes = listOf(intrinsicSize),
            badgePreview = badge,
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
