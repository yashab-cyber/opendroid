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
fun SocialInboxTab(
    interactions: List<SocialInteraction>,
    onResolve: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onReply: (String, String) -> Unit,
    onRefresh: () -> Unit
) {
    val theme = AppTheme.colors
    var selectedCategory by remember { mutableStateOf<InteractionCategory?>(null) }
    var replyingInteraction by remember { mutableStateOf<SocialInteraction?>(null) }

    val filteredInteractions = remember(interactions, selectedCategory) {
        if (selectedCategory == null) {
            interactions
        } else {
            interactions.filter { it.category == selectedCategory }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── CATEGORY FILTER ROW ──────────────────────────────────────
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All (${interactions.size})", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = theme.accentCyan
                        )
                    )
                }

                items(InteractionCategory.values()) { cat ->
                    val count = interactions.count { it.category == cat }
                    if (count > 0) {
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text("${cat.name.replace('_', ' ')} ($count)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = theme.accentCyan
                            )
                        )
                    }
                }
            }
        }

        // ── INTERACTIONS LIST ────────────────────────────────────────
        if (filteredInteractions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inbox,
                            contentDescription = null,
                            tint = theme.textSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Inbox is Clean",
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "No pending notifications, mentions, or messages.",
                            color = theme.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(filteredInteractions, key = { it.id }) { item ->
                InteractionCard(
                    interaction = item,
                    onReplyClick = { replyingInteraction = item },
                    onResolveClick = { onResolve(item.id) },
                    onDismissClick = { onDismiss(item.id) }
                )
            }
        }
    }

    // ── REPLY MODAL ──────────────────────────────────────────────────
    replyingInteraction?.let { interaction ->
        var replyText by remember { mutableStateOf(interaction.suggestedAction ?: "") }

        AlertDialog(
            onDismissRequest = { replyingInteraction = null },
            containerColor = theme.cardBackground,
            title = {
                Text(
                    "Reply on ${interaction.platform.displayName}",
                    color = theme.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "${interaction.authorName}: \"${interaction.content}\"",
                        color = theme.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 3
                    )

                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        label = { Text("Your Reply", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = theme.textPrimary,
                            unfocusedTextColor = theme.textPrimary,
                            focusedBorderColor = theme.accentCyan,
                            unfocusedBorderColor = theme.borderColor
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReply(interaction.id, replyText)
                        replyingInteraction = null
                    },
                    enabled = replyText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan)
                ) {
                    Text("Send Reply", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { replyingInteraction = null }) {
                    Text("Cancel", color = theme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun InteractionCard(
    interaction: SocialInteraction,
    onReplyClick: () -> Unit,
    onResolveClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    val theme = AppTheme.colors
    val timeFormatted = SimpleDateFormat("MMM d, HH:mm", LocalConfiguration.current.locales[0]).format(Date(interaction.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Platform, Author, Timestamp, Priority
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
                        interaction.platform.displayName.take(1),
                        color = theme.accentCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            interaction.authorName.ifBlank { "User" },
                            color = theme.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PriorityBadge(interaction.priority)
                    }
                    Text(
                        "${interaction.category.name} • $timeFormatted",
                        color = theme.textSecondary,
                        fontSize = 10.sp
                    )
                }

                InteractionStatusBadge(interaction.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body content
            Text(
                interaction.content,
                color = theme.textPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            // AI Suggestion pill if present
            if (!interaction.suggestedAction.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.surface)
                        .border(0.5.dp, theme.accentCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = theme.accentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Suggested: \"${interaction.suggestedAction}\"",
                            color = theme.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 2
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismissClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Dismiss", color = theme.textSecondary, fontSize = 11.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onResolveClick,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, theme.borderColor)
                    ) {
                        Text("Resolve", color = theme.textPrimary, fontSize = 11.sp)
                    }

                    Button(
                        onClick = onReplyClick,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Reply", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityBadge(priority: InteractionPriority) {
    val theme = AppTheme.colors
    val (color, text) = when (priority) {
        InteractionPriority.URGENT -> theme.accentRed to "URGENT"
        InteractionPriority.HIGH -> theme.accentOrange to "HIGH"
        InteractionPriority.NORMAL -> theme.accentCyan to "NORMAL"
        InteractionPriority.LOW -> theme.textSecondary to "LOW"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InteractionStatusBadge(status: String) {
    val theme = AppTheme.colors
    val (color, text) = when (status.uppercase()) {
        "UNREAD" -> theme.accentCyan to "UNREAD"
        "RESOLVED" -> theme.textSecondary to "RESOLVED"
        "REPLIED" -> theme.accentCyan to "REPLIED"
        else -> theme.textSecondary to status
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}
