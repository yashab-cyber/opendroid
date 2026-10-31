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
import com.opendroid.ai.social.core.ai.Tone
import com.opendroid.ai.social.domain.model.*
import com.opendroid.ai.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SocialContentTab(
    posts: List<SocialPost>,
    isGenerating: Boolean,
    onGeneratePost: (String, SocialPlatform, Tone, ContentType) -> Unit,
    onCreateDraft: (SocialPlatform, String, ContentType, Long?) -> Unit,
    onApprovePost: (String, Boolean) -> Unit,
    onPublishPostNow: (String) -> Unit,
    onSchedulePost: (String, Long) -> Unit,
    onCancelPost: (String) -> Unit,
    onDeletePost: (String) -> Unit,
    onShorten: (String, SocialPlatform, (String) -> Unit) -> Unit,
    onExpand: (String, SocialPlatform, (String) -> Unit) -> Unit,
    onChangeTone: (String, Tone, SocialPlatform, (String) -> Unit) -> Unit,
    onAddCta: (String, String, SocialPlatform, (String) -> Unit) -> Unit,
    onRemoveHashtags: (String) -> String
) {
    val theme = AppTheme.colors

    var selectedPlatform by remember { mutableStateOf(SocialPlatform.X) }
    var topicInput by remember { mutableStateOf("") }
    var currentContent by remember { mutableStateOf("") }
    var selectedTone by remember { mutableStateOf(Tone.PROFESSIONAL) }
    var selectedContentType by remember { mutableStateOf(ContentType.ANNOUNCEMENT) }
    var activeFilter by remember { mutableStateOf<PostStatus?>(null) }
    var schedulingPostId by remember { mutableStateOf<String?>(null) }
    var showScheduleDialogForCurrentDraft by remember { mutableStateOf(false) }

    val platformLimits = mapOf(
        SocialPlatform.X to 280,
        SocialPlatform.INSTAGRAM to 2200,
        SocialPlatform.LINKEDIN to 3000,
        SocialPlatform.TELEGRAM to 4096,
        SocialPlatform.DISCORD to 2000,
        SocialPlatform.FACEBOOK to 5000,
        SocialPlatform.YOUTUBE to 1000
    )
    val maxLimit = platformLimits[selectedPlatform] ?: 280

    val filteredPosts = remember(posts, activeFilter) {
        if (activeFilter == null) posts else posts.filter { it.status == activeFilter }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. AI CONTENT COMPOSER CARD ──────────────────────────────
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = theme.accentCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("AI Content Composer", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        if (isGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = theme.accentCyan, strokeWidth = 2.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Platform Selector
                    Text("Target Platform", color = theme.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(SocialPlatform.values()) { platform ->
                            FilterChip(
                                selected = selectedPlatform == platform,
                                onClick = { selectedPlatform = platform },
                                label = { Text(platform.displayName, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = theme.accentCyan
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Topic Prompt Input
                    OutlinedTextField(
                        value = topicInput,
                        onValueChange = { topicInput = it },
                        label = { Text("What would you like to post about?", fontSize = 12.sp) },
                        placeholder = { Text("e.g. OpenDroid v2 release with local AI agent capabilities", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = theme.textPrimary,
                            unfocusedTextColor = theme.textPrimary,
                            focusedBorderColor = theme.accentCyan,
                            unfocusedBorderColor = theme.borderColor
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tone Selector
                    Text("Tone", color = theme.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(Tone.values()) { tone ->
                            FilterChip(
                                selected = selectedTone == tone,
                                onClick = { selectedTone = tone },
                                label = { Text(tone.name.lowercase().capitalize(Locale.ROOT), fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = theme.surface,
                                    selectedLabelColor = theme.textPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Generate Button
                    Button(
                        onClick = {
                            if (topicInput.isNotBlank()) {
                                onGeneratePost(topicInput, selectedPlatform, selectedTone, selectedContentType)
                            }
                        },
                        enabled = topicInput.isNotBlank() && !isGenerating,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Platform Post", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = theme.borderColor.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Direct Content Editor
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Draft Content", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        val isOverLimit = currentContent.length > maxLimit
                        Text(
                            "${currentContent.length} / $maxLimit",
                            color = if (isOverLimit) theme.accentRed else theme.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isOverLimit) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = currentContent,
                        onValueChange = { currentContent = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        placeholder = { Text("Generated or custom post text appears here...", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = theme.textPrimary,
                            unfocusedTextColor = theme.textPrimary,
                            focusedBorderColor = theme.accentCyan,
                            unfocusedBorderColor = theme.borderColor
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick AI Modifiers Row
                    Text("AI Modifiers", color = theme.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            ModifierChip(
                                title = "Shorten",
                                icon = Icons.Default.Compress,
                                onClick = {
                                    if (currentContent.isNotBlank()) {
                                        onShorten(currentContent, selectedPlatform) { currentContent = it }
                                    }
                                }
                            )
                        }
                        item {
                            ModifierChip(
                                title = "Expand",
                                icon = Icons.Default.Expand,
                                onClick = {
                                    if (currentContent.isNotBlank()) {
                                        onExpand(currentContent, selectedPlatform) { currentContent = it }
                                    }
                                }
                            )
                        }
                        item {
                            ModifierChip(
                                title = "Add CTA",
                                icon = Icons.Default.TouchApp,
                                onClick = {
                                    if (currentContent.isNotBlank()) {
                                        onAddCta(currentContent, "download", selectedPlatform) { currentContent = it }
                                    }
                                }
                            )
                        }
                        item {
                            ModifierChip(
                                title = "Clean Hashtags",
                                icon = Icons.Default.Tag,
                                onClick = {
                                    if (currentContent.isNotBlank()) {
                                        currentContent = onRemoveHashtags(currentContent)
                                    }
                                }
                            )
                        }
                        item {
                            ModifierChip(
                                title = "Make Excited",
                                icon = Icons.Default.Celebration,
                                onClick = {
                                    if (currentContent.isNotBlank()) {
                                        onChangeTone(currentContent, Tone.EXCITED, selectedPlatform) { currentContent = it }
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Composer Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (currentContent.isNotBlank()) {
                                    onCreateDraft(selectedPlatform, currentContent, selectedContentType, null)
                                    currentContent = ""
                                    topicInput = ""
                                }
                            },
                            enabled = currentContent.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor)
                        ) {
                            Text("Save Draft", color = theme.textPrimary, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { showScheduleDialogForCurrentDraft = true },
                            enabled = currentContent.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor)
                        ) {
                            Text("Schedule", color = theme.textPrimary, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (currentContent.isNotBlank()) {
                                    onCreateDraft(selectedPlatform, currentContent, selectedContentType, null)
                                    currentContent = ""
                                    topicInput = ""
                                }
                            },
                            enabled = currentContent.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan)
                        ) {
                            Text("Post Now", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ── 2. POST LIST HEADER & FILTERS ────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Post History & Queue", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = activeFilter == null,
                        onClick = { activeFilter = null },
                        label = { Text("All (${posts.size})", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = theme.accentCyan
                        )
                    )
                }

                items(listOf(PostStatus.DRAFT, PostStatus.SCHEDULED, PostStatus.PUBLISHED, PostStatus.FAILED)) { status ->
                    val count = posts.count { it.status == status }
                    FilterChip(
                        selected = activeFilter == status,
                        onClick = { activeFilter = status },
                        label = { Text("${status.name.lowercase().capitalize(Locale.ROOT)} ($count)", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = theme.accentCyan
                        )
                    )
                }
            }
        }

        // ── 3. POST ITEMS ────────────────────────────────────────────
        if (filteredPosts.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No posts found in this filter", color = theme.textSecondary, fontSize = 13.sp)
                }
            }
        } else {
            items(filteredPosts, key = { it.id }) { post ->
                PostItemCard(
                    post = post,
                    onApprove = { onApprovePost(post.id, false) },
                    onPublishNow = { onPublishPostNow(post.id) },
                    onSchedule = { schedulingPostId = post.id },
                    onCancel = { onCancelPost(post.id) },
                    onDelete = { onDeletePost(post.id) }
                )
            }
        }
    }

    // ── SCHEDULE PICKER DIALOG (FOR EXISTING POST) ───────────────────
    schedulingPostId?.let { postId ->
        ScheduleTimeDialog(
            onDismiss = { schedulingPostId = null },
            onConfirm = { timeEpoch ->
                onSchedulePost(postId, timeEpoch)
                schedulingPostId = null
            }
        )
    }

    // ── SCHEDULE PICKER DIALOG (FOR CURRENT DRAFT) ───────────────────
    if (showScheduleDialogForCurrentDraft) {
        ScheduleTimeDialog(
            onDismiss = { showScheduleDialogForCurrentDraft = false },
            onConfirm = { timeEpoch ->
                onCreateDraft(selectedPlatform, currentContent, selectedContentType, timeEpoch)
                currentContent = ""
                topicInput = ""
                showScheduleDialogForCurrentDraft = false
            }
        )
    }
}

@Composable
private fun ModifierChip(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val theme = AppTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(theme.surface)
            .border(0.5.dp, theme.borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = theme.accentCyan, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(title, color = theme.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PostItemCard(
    post: SocialPost,
    onApprove: () -> Unit,
    onPublishNow: () -> Unit,
    onSchedule: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val theme = AppTheme.colors
    val timeFormat = SimpleDateFormat("MMM d, HH:mm", LocalConfiguration.current.locales[0])
    val dateString = when {
        post.publishedTime != null -> "Published " + timeFormat.format(Date(post.publishedTime))
        post.scheduledPublishTime != null -> "Scheduled for " + timeFormat.format(Date(post.scheduledPublishTime))
        else -> "Created " + timeFormat.format(Date(post.createdAt))
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Platform, Date, Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(theme.surface)
                        .border(0.5.dp, theme.borderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        post.platform.displayName.take(1),
                        color = theme.accentCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(post.platform.displayName, color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(dateString, color = theme.textSecondary, fontSize = 10.sp)
                }

                PostStatusBadge(post.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Post content
            Text(post.content, color = theme.textPrimary, fontSize = 12.sp, lineHeight = 17.sp)

            if (!post.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Error: ${post.errorMessage}", color = theme.accentRed, fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Delete", color = theme.accentRed, fontSize = 11.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (post.status == PostStatus.DRAFT) {
                        OutlinedButton(
                            onClick = onSchedule,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, theme.borderColor)
                        ) {
                            Text("Schedule", color = theme.textPrimary, fontSize = 11.sp)
                        }

                        Button(
                            onClick = onPublishNow,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Publish Now", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (post.status == PostStatus.SCHEDULED) {
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, theme.borderColor)
                        ) {
                            Text("Cancel", color = theme.textSecondary, fontSize = 11.sp)
                        }

                        Button(
                            onClick = onPublishNow,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Publish Now", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (post.status == PostStatus.FAILED) {
                        Button(
                            onClick = onPublishNow,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Retry", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostStatusBadge(status: PostStatus) {
    val theme = AppTheme.colors
    val (color, text) = when (status) {
        PostStatus.PUBLISHED -> theme.accentCyan to "PUBLISHED"
        PostStatus.SCHEDULED -> theme.accentCyan to "SCHEDULED"
        PostStatus.DRAFT -> theme.textSecondary to "DRAFT"
        PostStatus.FAILED -> theme.accentRed to "FAILED"
        PostStatus.CANCELLED -> theme.textSecondary to "CANCELLED"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ScheduleTimeDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val theme = AppTheme.colors
    var selectedOffsetHours by remember { mutableStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.cardBackground,
        title = { Text("Schedule Post Time", color = theme.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select when this post should be automatically published:", color = theme.textSecondary, fontSize = 12.sp)

                listOf(
                    1 to "In 1 hour",
                    3 to "In 3 hours",
                    6 to "In 6 hours",
                    12 to "In 12 hours",
                    24 to "Tomorrow (24 hours)",
                    48 to "In 2 days"
                ).forEach { (hours, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedOffsetHours = hours },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedOffsetHours == hours,
                            onClick = { selectedOffsetHours = hours },
                            colors = RadioButtonDefaults.colors(selectedColor = theme.accentCyan)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = theme.textPrimary, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val epoch = System.currentTimeMillis() + (selectedOffsetHours * 3600 * 1000L)
                    onConfirm(epoch)
                },
                colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan)
            ) {
                Text("Schedule", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.textSecondary)
            }
        }
    )
}
