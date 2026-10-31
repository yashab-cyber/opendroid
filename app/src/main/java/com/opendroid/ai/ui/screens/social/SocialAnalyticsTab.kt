package com.opendroid.ai.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.social.core.analytics.AnalyticsSummary
import com.opendroid.ai.social.domain.model.SocialAccount
import com.opendroid.ai.social.domain.model.SocialPost
import com.opendroid.ai.ui.components.DonutChart
import com.opendroid.ai.ui.components.SimpleBarChart
import com.opendroid.ai.ui.components.SimpleLineChart
import com.opendroid.ai.ui.theme.AppTheme
import java.util.Locale

@Composable
fun SocialAnalyticsTab(
    summary: AnalyticsSummary?,
    accounts: List<SocialAccount>,
    posts: List<SocialPost>,
    onRefresh: () -> Unit
) {
    val theme = AppTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    var selectedRangeDays by remember { mutableStateOf(7) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. TIME RANGE SELECTOR & HEADER ──────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Performance Analytics", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7 to "7D", 30 to "30D", 90 to "90D").forEach { (days, label) ->
                        FilterChip(
                            selected = selectedRangeDays == days,
                            onClick = { selectedRangeDays = days },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = theme.accentCyan
                            )
                        )
                    }
                }
            }
        }

        // ── 2. METRIC CARDS ROW ──────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsMetricBox(
                    title = "Followers",
                    value = summary?.totalFollowers?.toString() ?: "0",
                    delta = summary?.followersGrowthDelta?.let { if (it >= 0) "+$it" else "$it" } ?: "+0",
                    modifier = Modifier.weight(1f)
                )
                AnalyticsMetricBox(
                    title = "Total Reach",
                    value = summary?.totalReach?.let { formatCount(it.toLong()) } ?: "0",
                    delta = summary?.reachGrowthDelta?.let { if (it >= 0) "+$it" else "$it" } ?: "+0",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsMetricBox(
                    title = "Engagement",
                    value = summary?.averageEngagementRate?.let { "${String.format(locale, "%.1f", it)}%" } ?: "0.0%",
                    delta = "Active",
                    modifier = Modifier.weight(1f)
                )
                AnalyticsMetricBox(
                    title = "Total Posts",
                    value = summary?.totalPosts?.toString() ?: "0",
                    delta = "Published",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── 3. FOLLOWER GROWTH TREND (LINE CHART) ────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audience Growth Trend", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Daily follower trajectory over the period", color = theme.textSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    val trendPoints = listOf(120f, 135f, 150f, 175f, 210f, 240f, 290f)
                    val trendLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

                    SimpleLineChart(
                        dataPoints = trendPoints,
                        labels = trendLabels,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        lineColor = theme.accentCyan
                    )
                }
            }
        }

        // ── 4. DAILY REACH & ENGAGEMENT (BAR CHART) ──────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Daily Impressions & Reach", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Organic post visibility across all channels", color = theme.textSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    val barValues = listOf(450f, 620f, 890f, 1200f, 950f, 1400f, 1800f)
                    val barLabels = listOf("M", "T", "W", "T", "F", "S", "S")

                    SimpleBarChart(
                        values = barValues,
                        labels = barLabels,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        barColor = theme.textPrimary
                    )
                }
            }
        }

        // ── 5. PLATFORM SHARE (DONUT CHART) ──────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audience Share by Platform", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        val donutColors = listOf(
                            theme.accentCyan,
                            theme.accentPurple,
                            theme.accentOrange,
                            theme.textSecondary
                        )

                        DonutChart(
                            proportions = listOf(45f, 25f, 20f, 10f),
                            colors = donutColors,
                            modifier = Modifier.size(100.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LegendItem("X (Twitter)", "45%", theme.accentCyan)
                            LegendItem("Telegram", "25%", theme.accentPurple)
                            LegendItem("LinkedIn", "20%", theme.accentOrange)
                            LegendItem("Discord / Others", "10%", theme.textSecondary)
                        }
                    }
                }
            }
        }

        // ── 6. PLATFORM BREAKDOWN CARDS ──────────────────────────────
        item {
            Text("Channel Breakdown", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        if (accounts.isEmpty()) {
            item {
                Text("No accounts connected yet.", color = theme.textSecondary, fontSize = 12.sp)
            }
        } else {
            items(accounts) { account ->
                val accountPosts = posts.filter { it.accountId == account.id || it.platform == account.platform }
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, theme.borderColor, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(theme.surface)
                                .border(0.5.dp, theme.borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(account.platform.displayName.take(1), color = theme.accentCyan, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.platform.displayName, color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                if (account.username.isNotBlank()) "@${account.username}" else account.displayName,
                                color = theme.textSecondary,
                                fontSize = 11.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("${accountPosts.size} posts", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("Active sync", color = theme.accentCyan, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsMetricBox(
    title: String,
    value: String,
    delta: String,
    modifier: Modifier = Modifier
) {
    val theme = AppTheme.colors
    Card(
        modifier = modifier.border(1.dp, theme.borderColor, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = theme.textSecondary, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = theme.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(delta, color = theme.accentCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LegendItem(label: String, percent: String, color: Color) {
    val theme = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = theme.textPrimary, fontSize = 11.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(percent, color = theme.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
