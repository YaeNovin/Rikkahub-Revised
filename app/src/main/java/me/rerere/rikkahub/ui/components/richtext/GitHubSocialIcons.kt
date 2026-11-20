package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

internal enum class GitHubSocialGlyph { STAR, FORK, ISSUE, GITHUB }
// Reuses the GitHub mark already bundled in assets/icons/github.svg.
private val gitHubMark by lazy { PathParser().parsePathString("M12 0c6.63 0 12 5.276 12 11.79-.001 5.067-3.29 9.567-8.175 11.187-.6.118-.825-.25-.825-.56 0-.398.015-1.665.015-3.242 0-1.105-.375-1.813-.81-2.181 2.67-.295 5.475-1.297 5.475-5.822 0-1.297-.465-2.344-1.23-3.169.12-.295.54-1.503-.12-3.125 0 0-1.005-.324-3.3 1.209a11.32 11.32 0 00-3-.398c-1.02 0-2.04.133-3 .398-2.295-1.518-3.3-1.209-3.3-1.209-.66 1.622-.24 2.83-.12 3.125-.765.825-1.23 1.887-1.23 3.169 0 4.51 2.79 5.527 5.46 5.822-.345.294-.66.81-.765 1.577-.69.31-2.415.81-3.495-.973-.225-.354-.9-1.223-1.845-1.209-1.005.015-.405.56.015.781.51.28 1.095 1.327 1.23 1.666.24.663 1.02 1.93 4.035 1.385 0 .988.015 1.916.015 2.196 0 .31-.225.664-.825.56C3.303 21.374-.003 16.867 0 11.791 0 5.276 5.37 0 12 0z").toPath() }

@Composable
internal fun GitHubSocialIcon(glyph: GitHubSocialGlyph, modifier: Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            when (glyph) {
                GitHubSocialGlyph.GITHUB -> drawPath(gitHubMark, color)
                GitHubSocialGlyph.ISSUE -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.8f))
                    drawCircle(color, 1.5f, Offset(12f, 12f))
                }
                GitHubSocialGlyph.STAR -> drawPath(Path().apply {
                    moveTo(12f, 2f); lineTo(15f, 8.3f); lineTo(22f, 9.3f); lineTo(17f, 14.3f)
                    lineTo(18.2f, 21.5f); lineTo(12f, 18f); lineTo(5.8f, 21.5f)
                    lineTo(7f, 14.3f); lineTo(2f, 9.3f); lineTo(9f, 8.3f); close()
                }, color, style = Stroke(1.7f))
                GitHubSocialGlyph.FORK -> {
                    drawLine(color, Offset(7f, 6f), Offset(7f, 17f), 1.8f)
                    drawPath(Path().apply { moveTo(17f, 6f); lineTo(17f, 8f); cubicTo(17f, 12f, 7f, 10f, 7f, 15f) }, color, style = Stroke(1.8f))
                    listOf(Offset(7f, 4f), Offset(17f, 4f), Offset(7f, 19f)).forEach { drawCircle(color, 2f, it, style = Stroke(1.8f)) }
                }
            }
        }
    }
}
