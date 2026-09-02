package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var section by rememberSaveable { mutableStateOf(StatsSection.DASHBOARD) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (section == StatsSection.DASHBOARD) {
                                R.string.stats_page_dashboard_title
                            } else {
                                R.string.stats_detail_title
                            }
                        )
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = section == StatsSection.DASHBOARD,
                    onClick = { section = StatsSection.DASHBOARD },
                    icon = { Icon(HugeIcons.ChartColumn, contentDescription = null) },
                    label = { Text(stringResource(R.string.stats_page_dashboard_title)) },
                )
                NavigationBarItem(
                    selected = section == StatsSection.DETAILS,
                    onClick = {
                        section = StatsSection.DETAILS
                        vm.refreshDetails()
                    },
                    icon = { Icon(HugeIcons.Rocket01, contentDescription = null) },
                    label = { Text(stringResource(R.string.stats_detail_title)) },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (section == StatsSection.DETAILS) {
            StatsDetailPage(
                vm = vm,
                contentPadding = padding,
            )
        } else if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ActivityCard(
                        activityPerDay = stats.conversationsPerDay,
                        onClick = { navController.navigate(Screen.History) },
                    )
                }
                item {
                    PrimaryMetricsRow(
                        stats = stats,
                        onMessagesClick = {
                            section = StatsSection.DETAILS
                            vm.refreshDetails()
                        },
                        onTokensClick = {
                            section = StatsSection.DETAILS
                            vm.refreshDetails()
                        },
                        onRequestsClick = {
                            section = StatsSection.DETAILS
                            vm.refreshDetails()
                        },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.stats_page_configuration_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item {
                    SecondaryMetricsRow(
                        stats = stats,
                        onProvidersClick = { navController.navigate(Screen.SettingProvider) },
                        onApiKeysClick = { navController.navigate(Screen.SettingProvider) },
                        onModelsClick = { navController.navigate(Screen.SettingModels) },
                    )
                }
            }
        }
    }
}

private enum class StatsSection {
    DASHBOARD,
    DETAILS,
}

@Composable
private fun ActivityCard(
    activityPerDay: Map<LocalDate, Int>,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardCardHeader(
                icon = HugeIcons.ChartColumn,
                title = stringResource(R.string.stats_page_activity_title),
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val weeks = when {
                    maxWidth < 340.dp -> 16
                    maxWidth < 420.dp -> 20
                    else -> 24
                }
                val cellSize = if (maxWidth < 340.dp) 9.dp else 10.dp
                GithubActivityHeatmap(
                    activityPerDay = activityPerDay,
                    weeks = weeks,
                    cellSize = cellSize,
                )
            }
        }
    }
}

@Composable
private fun GithubActivityHeatmap(
    activityPerDay: Map<LocalDate, Int>,
    weeks: Int,
    cellSize: Dp,
) {
    val today = LocalDate.now()
    val endSunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val startSunday = endSunday.minusWeeks((weeks - 1).toLong())
    val visibleDates = buildList {
        repeat(weeks * 7) { offset -> add(startSunday.plusDays(offset.toLong())) }
    }
    val activeCounts = visibleDates.mapNotNull { date -> activityPerDay[date]?.takeIf { it > 0 } }.sorted()
    val thresholds = heatmapThresholds(activeCounts)
    val activeDays = activeCounts.size
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    Text(
        text = stringResource(
            R.string.stats_page_activity_summary,
            activeDays,
            startSunday.format(formatter),
            today.format(formatter),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.width(18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(7) { dayIndex ->
                Box(modifier = Modifier.size(cellSize), contentAlignment = Alignment.CenterStart) {
                    val label = when (dayIndex) {
                        1 -> stringResource(R.string.stats_page_dow_mon)
                        3 -> stringResource(R.string.stats_page_dow_wed)
                        5 -> stringResource(R.string.stats_page_dow_fri)
                        else -> ""
                    }
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(weeks) { weekIndex ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { dayIndex ->
                        val date = startSunday.plusDays((weekIndex * 7L) + dayIndex)
                        val count = activityPerDay[date] ?: 0
                        HeatmapCell(
                            level = when {
                                date.isAfter(today) -> -1
                                count == 0 -> 0
                                count <= thresholds.first -> 1
                                count <= thresholds.second -> 2
                                count <= thresholds.third -> 3
                                else -> 4
                            },
                            size = cellSize,
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.stats_page_heatmap_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        repeat(5) { level -> HeatmapCell(level = level, size = 10.dp) }
        Text(
            text = stringResource(R.string.stats_page_heatmap_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun heatmapThresholds(activeCounts: List<Int>): Triple<Int, Int, Int> = Triple(
    activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 },
    activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 },
    activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 },
)

@Composable
private fun HeatmapCell(level: Int, size: Dp) {
    val color = when (level) {
        -1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        0 -> MaterialTheme.colorScheme.surfaceVariant
        1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
        3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun PrimaryMetricsRow(
    stats: AppStats,
    onMessagesClick: () -> Unit,
    onTokensClick: () -> Unit,
    onRequestsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrimaryMetricCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Message01,
            label = stringResource(R.string.stats_page_total_messages),
            value = formatCount(stats.totalMessages.toLong()),
            supporting = stringResource(
                R.string.stats_page_conversations_summary,
                formatCount(stats.totalConversations.toLong()),
            ),
            onClick = onMessagesClick,
        )
        PrimaryMetricCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Cpu,
            label = stringResource(R.string.stats_page_total_tokens),
            value = formatTokens(stats.totalTokens),
            supporting = stringResource(
                R.string.stats_page_token_breakdown,
                formatTokens(stats.totalPromptTokens),
                formatTokens(stats.totalCompletionTokens),
            ),
            onClick = onTokensClick,
        )
        PrimaryMetricCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Rocket01,
            label = stringResource(R.string.stats_page_request_count),
            value = formatCount(stats.requestCount.toLong()),
            supporting = stringResource(
                R.string.stats_page_cached_token_summary,
                formatTokens(stats.totalCachedTokens),
            ),
            onClick = onRequestsClick,
        )
    }
}

@Composable
private fun SecondaryMetricsRow(
    stats: AppStats,
    onProvidersClick: () -> Unit,
    onApiKeysClick: () -> Unit,
    onModelsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryMetricCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.ServerStack01,
            label = stringResource(R.string.stats_page_configured_providers),
            value = formatCount(stats.configuredProviders.toLong()),
            supporting = stringResource(R.string.stats_page_enabled_providers, stats.enabledProviders),
            onClick = onProvidersClick,
        )
        SecondaryMetricCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Zap,
            label = stringResource(R.string.stats_page_enabled_api_keys),
            value = formatCount(stats.enabledApiKeys.toLong()),
            supporting = stringResource(R.string.stats_page_api_key_providers, stats.apiKeyProviders),
            onClick = onApiKeysClick,
        )
        SecondaryMetricCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.AiBrain01,
            label = stringResource(R.string.stats_page_model_usage),
            value = formatCount(stats.usedModels.toLong()),
            supporting = stringResource(R.string.stats_page_configured_models, stats.configuredModels),
            onClick = onModelsClick,
        )
    }
}

@Composable
private fun PrimaryMetricCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            DashboardCardHeader(icon = icon)
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SecondaryMetricCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 108.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            DashboardCardHeader(icon = icon, compact = true)
            Text(text = value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardCardHeader(
    icon: ImageVector,
    title: String? = null,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (compact) 17.dp else 20.dp),
            )
            title?.let { Text(text = it, style = MaterialTheme.typography.titleMedium) }
        }
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (compact) 14.dp else 16.dp),
        )
    }
}

internal fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}

internal fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(Locale.US, count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}
