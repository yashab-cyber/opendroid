package com.opendroid.ai.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.opendroid.ai.social.domain.model.*
import com.opendroid.ai.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SocialCalendarTab(
    posts: List<SocialPost>,
    onReschedulePost: (String, Long) -> Unit,
    onPublishImmediately: (String) -> Unit,
    onCancelSchedule: (String) -> Unit,
    onNavigateToComposer: () -> Unit
) {
    val theme = AppTheme.colors
    var selectedDayIndex by remember { mutableStateOf(0) }
    var reschedulingPostId by remember { mutableStateOf<String?>(null) }

    // Generate next 14 days
    val calendarDays = remember {
        val list = mutableListOf<CalendarDay>()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val numFormat = SimpleDateFormat("d", Locale.getDefault())

        for (i in 0 until 14) {
            val date = cal.time
            val startOfDay = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val endOfDay = cal.timeInMillis - 1

            list.add(
                CalendarDay(
                    index = i,
                    dayOfWeek = dayFormat.format(date),
                    dayOfMonth = numFormat.format(date),
                    startOfDay = startOfDay,
                    endOfDay = endOfDay,
                    isToday = i == 0
                )
            )
        }
        list
    }

    val selectedDay = calendarDays.getOrElse(selectedDayIndex) { calendarDays[0] }

    val scheduledPosts = remember(posts, selectedDay) {
        posts.filter { post ->
            val time = post.scheduledPublishTime
            time != null && time in selectedDay.startOfDay..selectedDay.endOfDay &&
                (post.status == PostStatus.SCHEDULED || post.status == PostStatus.DRAFT)
        }.sortedBy { it.scheduledPublishTime }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. CALENDAR STRIP ────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Schedule Horizon", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        val totalScheduled = posts.count { it.status == PostStatus.SCHEDULED }
                        Text("$totalScheduled total queued", color = theme.accentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(calendarDays) { day ->
                            val isSelected = selectedDayIndex == day.index
                            val hasPosts = posts.any { post ->
                                val time = post.scheduledPublishTime
                                time != null && time in day.startOfDay..day.endOfDay &&
                                    (post.status == PostStatus.SCHEDULED || post.status == PostStatus.DRAFT)
                            }

                            DayCard(
                                day = day,
                                isSelected = isSelected,
                                hasPosts = hasPosts,
                                onClick = { selectedDayIndex = day.index }
                            )
                        }
                    }
                }
            }
        }

        // ── 2. DAY SUMMARY HEADER ────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (selectedDay.isToday) "Today's Schedule" else "${selectedDay.dayOfWeek}, ${selectedDay.dayOfMonth}",
                        color = theme.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "${scheduledPosts.size} posts scheduled for this day",
                        color = theme.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = onNavigateToComposer,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Schedule Post", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── 3. SCHEDULED POSTS LIST ──────────────────────────────────
        if (scheduledPosts.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.EventNote,
                            contentDescription = null,
                            tint = theme.textSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "No Posts Scheduled",
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap 'Schedule Post' to queue content for this date.",
                            color = theme.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(scheduledPosts, key = { it.id }) { post ->
                ScheduledPostCard(
                    post = post,
                    onReschedule = { reschedulingPostId = post.id },
                    onPublishNow = { onPublishImmediately(post.id) },
                    onCancel = { onCancelSchedule(post.id) }
                )
            }
        }
    }

    // ── RESCHEDULE DIALOG ────────────────────────────────────────────
    reschedulingPostId?.let { postId ->
        ScheduleTimeDialog(
            onDismiss = { reschedulingPostId = null },
            onConfirm = { newEpoch ->
                onReschedulePost(postId, newEpoch)
                reschedulingPostId = null
            }
        )
    }
}

private data class CalendarDay(
    val index: Int,
    val dayOfWeek: String,
    val dayOfMonth: String,
    val startOfDay: Long,
    val endOfDay: Long,
    val isToday: Boolean
)

@Composable
private fun DayCard(
    day: CalendarDay,
    isSelected: Boolean,
    hasPosts: Boolean,
    onClick: () -> Unit
) {
    val theme = AppTheme.colors

    Box(
        modifier = Modifier
            .width(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) theme.accentCyan else theme.surface
            )
            .border(
                1.dp,
                if (isSelected) theme.accentCyan else theme.borderColor,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfWeek,
                color = if (isSelected) Color.Black else theme.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                day.dayOfMonth,
                color = if (isSelected) Color.Black else theme.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Post dot indicator
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            hasPosts && isSelected -> Color.Black
                            hasPosts -> theme.accentCyan
                            else -> Color.Transparent
                        }
                    )
            )
        }
    }
}

@Composable
private fun ScheduledPostCard(
    post: SocialPost,
    onReschedule: () -> Unit,
    onPublishNow: () -> Unit,
    onCancel: () -> Unit
) {
    val theme = AppTheme.colors
    val timeFormat = SimpleDateFormat("HH:mm", LocalConfiguration.current.locales[0])
    val timeString = post.scheduledPublishTime?.let { timeFormat.format(Date(it)) } ?: "--:--"

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Time pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.accentCyan.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        timeString,
                        color = theme.accentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    post.platform.displayName,
                    color = theme.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                if (post.requiresApproval) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(theme.accentOrange.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("APPROVAL NEEDED", color = theme.accentOrange, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(theme.accentCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("AUTO PUBLISH", color = theme.accentCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                post.content,
                color = theme.textPrimary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Cancel", color = theme.textSecondary, fontSize = 11.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onReschedule,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, theme.borderColor)
                    ) {
                        Text("Reschedule", color = theme.textPrimary, fontSize = 11.sp)
                    }

                    Button(
                        onClick = onPublishNow,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Publish Now", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
