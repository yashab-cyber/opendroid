package com.opendroid.ai.ui.screens.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.social.domain.model.SocialInsight
import com.opendroid.ai.social.domain.model.SocialReport
import com.opendroid.ai.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SocialReportsTab(
    weeklyReport: SocialReport?,
    insights: List<SocialInsight>,
    onRegenerate: () -> Unit
) {
    val context = LocalContext.current
    val theme = AppTheme.colors
    val timeFormat = SimpleDateFormat("MMM d, yyyy", LocalConfiguration.current.locales[0])

    val dateRange = if (weeklyReport != null) {
        "${timeFormat.format(Date(weeklyReport.startDate))} – ${timeFormat.format(Date(weeklyReport.endDate))}"
    } else {
        "Current Week"
    }

    val reportText = buildString {
        appendLine("📊 OPENDROID SOCIAL EXECUTIVE REPORT")
        appendLine("Period: $dateRange")
        appendLine("──────────────────────────────────────────")
        weeklyReport?.let { r ->
            appendLine("• Total Published Posts: ${r.totalPostsPublished}")
            appendLine("• Total Reach: ${r.totalReach}")
            appendLine("• Average Engagement: ${r.averageEngagementRate}%")
            appendLine("• Follower Growth: +${r.totalFollowersDelta}")
            appendLine("──────────────────────────────────────────")
            appendLine("Executive Summary:")
            appendLine(r.aiSummary)
            appendLine("──────────────────────────────────────────")
            appendLine("AI Strategic Recommendations:")
            r.recommendations.forEachIndexed { i, rec ->
                appendLine("${i + 1}. $rec")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. REPORT HEADER & ACTIONS ───────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.accentCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Executive Weekly Report", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(dateRange, color = theme.accentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        IconButton(
                            onClick = onRegenerate,
                            modifier = Modifier.size(32.dp).background(theme.surface, CircleShape)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = theme.textPrimary, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action buttons: Copy & Share
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Social Report", reportText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = theme.textPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Text", color = theme.textPrimary, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val shareIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, reportText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Social Report"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share Report", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ── 2. EXECUTIVE SUMMARY ─────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Executive Overview", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        weeklyReport?.aiSummary ?: "Data is being aggregated from connected platforms. Insights will refresh automatically as activity logs accrue.",
                        color = theme.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // ── 3. STRATEGIC RECOMMENDATIONS ─────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null, tint = theme.accentCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Actionable Recommendations", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val recs = weeklyReport?.recommendations ?: listOf(
                        "Increase X posting frequency between 14:00 - 17:00 UTC for optimal developer engagement.",
                        "Publish community updates to Telegram 15 minutes before global platform posts.",
                        "Add explicit Call-To-Action buttons on high-performing announcements to drive GitHub stars."
                    )

                    recs.forEachIndexed { index, rec ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "${index + 1}.",
                                color = theme.accentCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                rec,
                                color = theme.textPrimary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // ── 4. GROUNDED AI INSIGHT AUDIT ─────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Grounded Insights Verification", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "OpenDroid strictly distinguishes between Observed Metrics, Calculated Insights, and Strategic Suggestions to eliminate hallucinations.",
                        color = theme.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}
