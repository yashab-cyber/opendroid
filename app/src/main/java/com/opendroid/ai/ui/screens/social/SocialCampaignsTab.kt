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
fun SocialCampaignsTab(
    campaigns: List<SocialCampaign>,
    isGenerating: Boolean,
    onCreateCampaign: (String, String, Int, List<SocialPlatform>) -> Unit
) {
    val theme = AppTheme.colors
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. HEADER & NEW CAMPAIGN CTA ─────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Campaign Strategies", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Multi-day coordinated cross-platform campaigns", color = theme.textSecondary, fontSize = 11.sp)
                }

                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Campaign", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── 2. CAMPAIGNS LIST ────────────────────────────────────────
        if (campaigns.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp).clip(CircleShape).background(theme.accentCyan.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Campaign, contentDescription = null, tint = theme.accentCyan, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            "No Campaigns Created Yet",
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Launch an AI-generated multi-day marketing campaign with teaser, release, and follow-up posts scheduled automatically across your platforms.",
                            color = theme.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showCreateDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Generate First Campaign", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            items(campaigns, key = { it.id }) { campaign ->
                CampaignCard(campaign = campaign)
            }
        }
    }

    // ── CREATE CAMPAIGN WIZARD DIALOG ────────────────────────────────
    if (showCreateDialog) {
        CreateCampaignDialog(
            isGenerating = isGenerating,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, objective, duration, platforms ->
                onCreateCampaign(name, objective, duration, platforms)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun CampaignCard(campaign: SocialCampaign) {
    val theme = AppTheme.colors
    val timeFormat = SimpleDateFormat("MMM d", LocalConfiguration.current.locales[0])
    val startStr = timeFormat.format(Date(campaign.startDate))
    val endStr = timeFormat.format(Date(campaign.endDate))

    val now = System.currentTimeMillis()
    val totalDuration = (campaign.endDate - campaign.startDate).coerceAtLeast(1L)
    val elapsed = (now - campaign.startDate).coerceIn(0L, totalDuration)
    val progress = (elapsed.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Name & Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(campaign.name, color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("$startStr – $endStr", color = theme.textSecondary, fontSize = 11.sp)
                }

                CampaignStatusBadge(campaign.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Objective
            Text(
                campaign.objective,
                color = theme.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Timeline Progress", color = theme.textSecondary, fontSize = 10.sp)
                Text("${(progress * 100).toInt()}%", color = theme.accentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = theme.accentCyan,
                trackColor = theme.surface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Target Platforms
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Platforms:", color = theme.textSecondary, fontSize = 11.sp)
                campaign.platforms.forEach { platform ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.surface)
                            .border(0.5.dp, theme.borderColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(platform.displayName, color = theme.textPrimary, fontSize = 9.sp)
                    }
                }
            }

            if (campaign.targetAudience.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Target Audience: ${campaign.targetAudience}",
                    color = theme.accentCyan,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun CampaignStatusBadge(status: CampaignStatus) {
    val theme = AppTheme.colors
    val (color, text) = when (status) {
        CampaignStatus.ACTIVE -> theme.accentCyan to "ACTIVE"
        CampaignStatus.PLANNING -> theme.accentOrange to "PLANNING"
        CampaignStatus.PAUSED -> theme.textSecondary to "PAUSED"
        CampaignStatus.COMPLETED -> theme.textSecondary to "COMPLETED"
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
private fun CreateCampaignDialog(
    isGenerating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, List<SocialPlatform>) -> Unit
) {
    val theme = AppTheme.colors
    var name by remember { mutableStateOf("") }
    var objective by remember { mutableStateOf("") }
    var durationDays by remember { mutableStateOf(7) }
    val selectedPlatforms = remember {
        mutableStateListOf(SocialPlatform.X, SocialPlatform.TELEGRAM, SocialPlatform.LINKEDIN)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.cardBackground,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = theme.accentCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("AI Campaign Generator", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Campaign Name", fontSize = 12.sp) },
                    placeholder = { Text("e.g. OpenDroid v2.0 Global Launch", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.textPrimary,
                        unfocusedTextColor = theme.textPrimary,
                        focusedBorderColor = theme.accentCyan,
                        unfocusedBorderColor = theme.borderColor
                    )
                )

                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it },
                    label = { Text("Campaign Objective & Goals", fontSize = 12.sp) },
                    placeholder = { Text("e.g. Highlight on-device privacy, fast responsiveness, and attract open source developers.", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.textPrimary,
                        unfocusedTextColor = theme.textPrimary,
                        focusedBorderColor = theme.accentCyan,
                        unfocusedBorderColor = theme.borderColor
                    )
                )

                Text("Duration: $durationDays days", color = theme.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 7, 14, 30).forEach { days ->
                        FilterChip(
                            selected = durationDays == days,
                            onClick = { durationDays = days },
                            label = { Text("$days Days", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = theme.accentCyan
                            )
                        )
                    }
                }

                Text("Target Platforms:", color = theme.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SocialPlatform.values()) { platform ->
                        val isSelected = selectedPlatforms.contains(platform)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) selectedPlatforms.remove(platform) else selectedPlatforms.add(platform)
                            },
                            label = { Text(platform.displayName, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = theme.accentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = theme.accentCyan
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && objective.isNotBlank() && selectedPlatforms.isNotEmpty()) {
                        onConfirm(name, objective, durationDays, selectedPlatforms.toList())
                    }
                },
                enabled = name.isNotBlank() && objective.isNotBlank() && selectedPlatforms.isNotEmpty() && !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Text("Generate Strategy", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.textSecondary)
            }
        }
    )
}
