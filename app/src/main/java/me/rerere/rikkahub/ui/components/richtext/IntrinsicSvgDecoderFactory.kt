package me.rerere.rikkahub.ui.components.richtext

import coil3.ImageLoader
import coil3.Image
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import coil3.svg.SvgDecoder
import coil3.svg.Svg
import coil3.svg.SvgImage
import coil3.toBitmap
import com.caverock.androidsvg.RenderOptions
import okio.BufferedSource
import okio.Buffer

/** Preserve SVG's own coordinate size even when AsyncImage supplies large layout constraints.
 * The SVG factory still checks the response type; raster formats use Coil's normal decoders. */
internal object IntrinsicSvgDecoderFactory : Decoder.Factory {
    private val delegate = SvgDecoder.Factory(
        parser = CssSizeSvgParser,
        useViewBoundsAsIntrinsicSize = false,
        renderToBitmap = false,
    )

    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
        delegate.create(result, options.copy(size = Size.ORIGINAL), imageLoader)
}

private object CssSizeSvgParser : Svg.Parser {
    override fun parse(source: BufferedSource): Svg = parseNormalized(expandImages(source) ?: source)

    private fun expandImages(source: BufferedSource): BufferedSource? = source.peek().use { peek ->
        if (peek.request(MAX_INLINE_SVG_BYTES.toLong() + 1)) return@use null
        val original = peek.readUtf8()
        val expanded = expandEmbeddedSvgImages(original)
        if (expanded == original) null else Buffer().writeUtf8(expanded)
    }

    private fun parseNormalized(source: BufferedSource): Svg {
        val dimensions = explicitDimensions(source)
        val svg = Svg.Parser.DEFAULT.parse(source)
        fun Float.positive() = takeIf { it.isFinite() && it > 0f }
        val bounds = svg.viewBox
        val boxWidth = bounds?.let { (it.right - it.left).positive() }
        val boxHeight = bounds?.let { (it.bottom - it.top).positive() }
        val ratio = if (boxWidth != null && boxHeight != null) boxWidth / boxHeight else null
        // AndroidSVG itself fills a missing dimension from viewBox, which can hide
        // the fact that only one CSS dimension was specified. Inspect the root first.
        val width = if (dimensions != null) svgCssLength(dimensions.first) else svg.width.positive()
        val height = if (dimensions != null) svgCssLength(dimensions.second) else svg.height.positive()
        // Badgen uses width=88.6 / height=20 with viewBox=0 0 886 200.
        // Keep its CSS size; use the viewBox only when CSS dimensions are missing.
        val intrinsicWidth = width ?: if (height != null && ratio != null) height * ratio else boxWidth
        val intrinsicHeight = height ?: if (width != null && ratio != null) width / ratio else boxHeight
        return object : Svg by svg {
            override val width = intrinsicWidth ?: svg.width
            override val height = intrinsicHeight ?: svg.height

            override fun asImage(width: Int, height: Int): Image =
                svg.asImage(width, height).let { image ->
                    if (image is SvgImage) image.withSvgViewport(width, height) else image
                }
        }
    }

    private fun explicitDimensions(source: BufferedSource): Pair<String?, String?>? = runCatching {
        source.peek().inputStream().use { input ->
            val parser = android.util.Xml.newPullParser()
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if (parser.name.substringAfter(':') != "svg") return@use null
                    return@use parser.getAttributeValue(null, "width") to parser.getAttributeValue(null, "height")
                }
                event = parser.next()
            }
            null
        }
    }.getOrNull()
}

/** Canvas dimensions stay at the parent/screen size after Compose clips and scales it.
 * SvgImage otherwise uses those dimensions for the root's 100% width/height. */
private fun SvgImage.withSvgViewport(width: Int, height: Int): SvgImage = SvgImage(
    svg = svg,
    renderOptions = (renderOptions?.let(::RenderOptions) ?: RenderOptions())
        .viewPort(0f, 0f, width.toFloat(), height.toFloat()),
    width = width,
    height = height,
)

/** Explicit export dimensions must replace the inline viewport on a COPY of the options.
 * Coil's generic toBitmap doesn't apply the scaling performed by Compose's ImagePainter. */
internal fun Image.toInlineImageBitmap(width: Int, height: Int): android.graphics.Bitmap =
    (if (this is SvgImage) withSvgViewport(width, height) else this).toBitmap(width, height)

private val SVG_CSS_LENGTH = Regex("^([+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][+-]?\\d+)?)\\s*(px|pt|pc|in|cm|mm|q)?$", RegexOption.IGNORE_CASE)

internal fun svgCssLength(value: String?): Float? {
    val match = SVG_CSS_LENGTH.matchEntire(value?.trim().orEmpty()) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    val scale = when (match.groupValues[2].lowercase()) {
        "pt" -> 96f / 72f
        "pc" -> 16f
        "in" -> 96f
        "cm" -> 96f / 2.54f
        "mm" -> 96f / 25.4f
        "q" -> 96f / 101.6f
        else -> 1f
    }
    return (number * scale).takeIf { it.isFinite() && it > 0f }
}
