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
import com.opendroid.ai.social.core.ai.SuggestedReply
import com.opendroid.ai.social.domain.model.*
import com.opendroid.ai.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SocialCommentsTab(
    comments: List<SocialComment>,
    unansweredComments: List<SocialComment>,
    onReplyToComment: (String, String) -> Unit,
    onIgnoreComment: (String) -> Unit,
    onGenerateSuggestedReply: (SocialComment, (SuggestedReply) -> Unit) -> Unit
) {
    val theme = AppTheme.colors
    var showOnlyUnanswered by remember { mutableStateOf(true) }
    var selectedPlatform by remember { mutableStateOf<SocialPlatform?>(null) }

    val displayedComments = remember(comments, unansweredComments, showOnlyUnanswered, selectedPlatform) {
        val base = if (showOnlyUnanswered) unansweredComments else comments
        if (selectedPlatform != null) {
            base.filter { it.platform == selectedPlatform }
        } else {
            base
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. FILTER CONTROLS ───────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = showOnlyUnanswered,
                        onClick = { showOnlyUnanswered = true },
                        label = { Text("Unanswered (${unansweredComments.size})", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = theme.accentCyan
                        )
                    )

                    FilterChip(
                        selected = !showOnlyUnanswered,
                        onClick = { showOnlyUnanswered = false },
                        label = { Text("All Comments (${comments.size})", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = theme.accentCyan
                        )
                    )
                }

                // Platform filter
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedPlatform == null,
                            onClick = { selectedPlatform = null },
                            label = { Text("All Platforms", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = theme.surface,
                                selectedLabelColor = theme.textPrimary
                            )
                        )
                    }

                    items(SocialPlatform.values()) { platform ->
                        val count = (if (showOnlyUnanswered) unansweredComments else comments).count { it.platform == platform }
                        if (count > 0) {
                            FilterChip(
                                selected = selectedPlatform == platform,
                                onClick = { selectedPlatform = platform },
                                label = { Text("${platform.displayName} ($count)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = theme.surface,
                                    selectedLabelColor = theme.textPrimary
                                )
                            )
                        }
                    }
                }
            }
        }

        // ── 2. COMMENTS LIST ─────────────────────────────────────────
        if (displayedComments.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.QuestionAnswer,
                            contentDescription = null,
                            tint = theme.textSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            if (showOnlyUnanswered) "All Caught Up!" else "No Comments Found",
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (showOnlyUnanswered) "Every audience comment has been answered or reviewed." else "No comments matching selected filter.",
                            color = theme.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(displayedComments, key = { it.id }) { comment ->
                CommentReviewCard(
                    comment = comment,
                    onReply = { text -> onReplyToComment(comment.id, text) },
                    onIgnore = { onIgnoreComment(comment.id) },
                    onGenerateReply = { callback -> onGenerateSuggestedReply(comment, callback) }
                )
            }
        }
    }
}

@Composable
private fun CommentReviewCard(
    comment: SocialComment,
    onReply: (String) -> Unit,
    onIgnore: () -> Unit,
    onGenerateReply: ((SuggestedReply) -> Unit) -> Unit
) {
    val theme = AppTheme.colors
    val timeFormatted = SimpleDateFormat("MMM d, HH:mm", LocalConfiguration.current.locales[0]).format(Date(comment.timestamp))

    var replyText by remember(comment.id) {
        mutableStateOf(comment.suggestedReply ?: "")
    }
    var isGeneratingReply by remember { mutableStateOf(false) }
    val isPending = comment.replyStatus != CommentReplyStatus.REPLIED && comment.replyStatus != CommentReplyStatus.IGNORED

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Author & Platform
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(theme.surface)
                        .border(0.5.dp, theme.borderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        comment.authorName.take(1).uppercase(),
                        color = theme.accentCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        comment.authorName,
                        color = theme.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        "${comment.platform.displayName} • $timeFormatted",
                        color = theme.textSecondary,
                        fontSize = 10.sp
                    )
                }

                CommentStatusBadge(comment.replyStatus)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Comment text
            Text(
                comment.content,
                color = theme.textPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            // If already answered, show the reply
            if (comment.replyStatus == CommentReplyStatus.REPLIED && !comment.actualReply.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.surface)
                        .padding(10.dp)
                ) {
                    Column {
                        Text("Replied:", color = theme.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(comment.actualReply, color = theme.textPrimary, fontSize = 12.sp)
                    }
                }
            }

            // If unanswered / pending, show reply drafting controls
            if (isPending) {
                Spacer(modifier = Modifier.height(12.dp))

                // Grounded AI suggestion box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.surface)
                        .border(0.5.dp, theme.accentCyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = theme.accentCyan, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Grounded AI Reply Draft", color = theme.accentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            if (isGeneratingReply) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = theme.accentCyan, strokeWidth = 1.5.dp)
                            } else {
                                Text(
                                    "Regenerate",
                                    color = theme.textSecondary,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable {
                                        isGeneratingReply = true
                                        onGenerateReply { suggested ->
                                            replyText = suggested.replyText
                                            isGeneratingReply = false
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = replyText,
                            onValueChange = { replyText = it },
                            placeholder = { Text("Tap Regenerate or type custom reply...", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = theme.textPrimary,
                                unfocusedTextColor = theme.textPrimary,
                                focusedBorderColor = theme.accentCyan,
                                unfocusedBorderColor = theme.borderColor
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Grounded strictly in verified OpenDroid facts. Anti-hallucination active.",
                            color = theme.textSecondary,
                            fontSize = 9.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onIgnore,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Ignore / Dismiss", color = theme.textSecondary, fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            if (replyText.isNotBlank()) {
                                onReply(replyText)
                            }
                        },
                        enabled = replyText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text("Approve & Reply", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentStatusBadge(status: CommentReplyStatus) {
    val theme = AppTheme.colors
    val (color, text) = when (status) {
        CommentReplyStatus.NONE -> theme.textSecondary to "PENDING"
        CommentReplyStatus.SUGGESTED -> theme.accentOrange to "SUGGESTED"
        CommentReplyStatus.APPROVED -> theme.accentCyan to "APPROVED"
        CommentReplyStatus.REPLIED -> theme.accentCyan to "REPLIED"
        CommentReplyStatus.IGNORED -> theme.textSecondary to "DISMISSED"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}
