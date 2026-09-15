package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu as MaterialDropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
fun AppearanceDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MaterialTheme.shapes.small,
    maxHeight: Dp = 320.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        // The visible viewport below owns scrolling; sharing a ScrollState with Material's
        // enclosing column would overwrite its range and move the background with the list.
        scrollState = rememberScrollState(),
        properties = properties,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        AppearanceMenuSurface(shape = shape, scrollState = scrollState, maxHeight = maxHeight, content = content)
    }
}

@Composable
fun AppearanceMenuSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    scrollState: ScrollState = rememberScrollState(),
    maxHeight: Dp = 320.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val imeBottom = WindowInsets.ime.getBottom(density)
    val limits = remember(window, density.density, imeBottom, maxHeight) {
        menuViewportLimits(window, density.density, imeBottom, maxHeight.value)
    }
    val policy = remember(limits) { MenuViewportMeasurePolicy(limits) }
    Layout(modifier = modifier, measurePolicy = policy, content = {
        // Background sizing uses only the visible viewport. Only the foreground options scroll.
        IsolatedOverlaySurface(
            modifier = Modifier.fillMaxWidth().shadow(elevation = 8.dp, shape = shape, clip = false),
            shape = shape,
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState), content = content)
        }
    })
}
