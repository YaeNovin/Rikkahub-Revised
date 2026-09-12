package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.richtext.richContentColors
import me.rerere.rikkahub.ui.components.ui.AppearanceDropdownMenu
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.components.ui.rememberTintedSurfaceForeground
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme
import me.rerere.rikkahub.ui.theme.currentTextPaletteSeed
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InspirationStarterCards(
    conversationId: Uuid,
    assistantId: Uuid,
    settings: Settings,
    onSelect: (InspirationCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = settings.inspirationSettings(assistantId)
    val builtIns = inspirationCatalog()
    val pool = remember(builtIns, config, settings.extensionManagementMode) {
        inspirationPool(builtIns, config, settings.extensionManagementMode)
    }
    var revision by rememberSaveable(conversationId.toString(), assistantId.toString(), settings.extensionManagementMode) { mutableIntStateOf(0) }
    val visible = remember(pool, config, conversationId, revision) { inspirationBatch(pool, config, conversationId.hashCode(), revision) }
    val keyboard = WindowInsets.isImeVisible
    val fontScale = LocalDensity.current.fontScale
    val store = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    var savingPin by remember { mutableStateOf(false) }
    fun pin(card: InspirationCard) {
        if (savingPin) return
        scope.launch {
            savingPin = true
            try { store.update { current -> current.withInspirationSettings(assistantId) { it.togglePin(card.id) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            finally { savingPin = false }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = inspirationColumns(maxWidth.value, fontScale)
        Column(Modifier.fillMaxWidth().padding(vertical = if (keyboard) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_inspiration_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { revision = if (revision == Int.MAX_VALUE) 0 else revision + 1 },
                    enabled = pool.size > visible.size && visible.any { it.id !in config.pinnedIds }) {
                    Text(stringResource(R.string.inspiration_shuffle))
                }
            }
            if (visible.isEmpty()) Text(stringResource(R.string.inspiration_empty), style = MaterialTheme.typography.bodyMedium)
            if (keyboard) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { card ->
                        InspirationTile(card, card.id in config.pinnedIds,
                            saving = savingPin, onSelect = { onSelect(card) }, onPin = { pin(card) },
                            modifier = Modifier.width(this@BoxWithConstraints.maxWidth.coerceAtMost(300.dp)), compact = true)
                    }
                }
            } else visible.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { card ->
                        InspirationTile(card, card.id in config.pinnedIds, savingPin, onSelect = { onSelect(card) }, onPin = { pin(card) }, modifier = Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InspirationTile(card: InspirationCard, pinned: Boolean, saving: Boolean, onSelect: () -> Unit, onPin: () -> Unit,
    modifier: Modifier, compact: Boolean = false) {
    var menu by remember(card.id) { mutableStateOf(false) }
    val pinLabel = stringResource(if (pinned) R.string.inspiration_unpin else R.string.inspiration_pin)
    Box(modifier) {
        InspirationSurface(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(onClick = onSelect, onLongClickLabel = pinLabel, onLongClick = { menu = true })) {
            Column(Modifier.padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (pinned) HugeIcons.Pin else HugeIcons.Sparkles,
                        if (pinned) stringResource(R.string.inspiration_pinned) else null, Modifier.size(18.dp))
                    Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
                if (!compact) Text(card.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        AppearanceDropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(pinLabel) }, enabled = !saving,
                onClick = { menu = false; onPin() })
        }
    }
}

/** Only paint a tint on the shared backdrop, never another wallpaper inside each card. */
@Composable
internal fun InspirationSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val settings = LocalSettings.current.advancedAppearanceSetting
    val options = settings.inspirationAppearance.normalized()
    val rich = richContentColors()
    val scheme = MaterialTheme.colorScheme
    val base = LocalBaseThemeColorScheme.current ?: scheme
    val active = LocalGlobalBackgroundActive.current
    val container = when {
        !active -> base.surfaceContainer
        options.followRichContent && settings.enableRichContentPerformanceEffects -> rich.container
        options.followRichContent -> base.surfaceContainer
        else -> base.surfaceContainer.copy(alpha = options.surfaceOpacity)
    }
    val border = if (options.followRichContent) rich.border else scheme.primary.copy(alpha = options.borderOpacity)
    val foreground = rememberTintedSurfaceForeground(container, container.alpha, scheme.onSurface,
        LocalAppearanceBackground.current?.readability?.backgrounds ?: listOf(base.background.copy(alpha = 1f)), currentTextPaletteSeed())
    BackgroundReadabilityTheme(active = true, foreground = foreground) {
        Surface(modifier, shape = if (options.followRichContent) MaterialTheme.shapes.large else RoundedCornerShape(options.cornerRadius.dp),
            color = container, contentColor = foreground, border = BorderStroke(1.dp, border), content = content)
    }
}
