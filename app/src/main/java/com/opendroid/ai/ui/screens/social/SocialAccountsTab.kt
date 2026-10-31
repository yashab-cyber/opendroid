package com.opendroid.ai.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
fun SocialAccountsTab(
    accounts: List<SocialAccount>,
    onConnectAccount: (SocialPlatform, SocialCredentials, List<SocialPermission>) -> Unit,
    onDisconnectAccount: (String, SocialPlatform) -> Unit,
    onRevokeAccount: (String, SocialPlatform) -> Unit,
    onRefresh: () -> Unit
) {
    val theme = AppTheme.colors
    var connectingPlatform by remember { mutableStateOf<SocialPlatform?>(null) }
    var revokingAccount by remember { mutableStateOf<SocialAccount?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── HEADER & SECURITY NOTICE ─────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(38.dp).clip(CircleShape).background(theme.accentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = theme.accentCyan, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Hardware-Encrypted Credentials",
                            color = theme.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "All API tokens are stored in Android KeyStore (AES-256-GCM). Tokens are never logged or stored in plaintext.",
                            color = theme.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // ── PLATFORM CARDS ───────────────────────────────────────────
        item {
            Text(
                "Connected Platforms",
                color = theme.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        items(SocialPlatform.values()) { platform ->
            val account = accounts.find { it.platform == platform }
            AccountCard(
                platform = platform,
                account = account,
                onConnect = { connectingPlatform = platform },
                onDisconnect = { account?.let { onDisconnectAccount(it.id, it.platform) } },
                onRevoke = { account?.let { revokingAccount = it } }
            )
        }
    }

    // ── CONNECT DIALOG ───────────────────────────────────────────────
    connectingPlatform?.let { platform ->
        ConnectAccountDialog(
            platform = platform,
            onDismiss = { connectingPlatform = null },
            onConfirm = { credentials, permissions ->
                onConnectAccount(platform, credentials, permissions)
                connectingPlatform = null
            }
        )
    }

    // ── REVOKE CONFIRMATION DIALOG ───────────────────────────────────
    revokingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { revokingAccount = null },
            containerColor = theme.cardBackground,
            title = {
                Text("Revoke Access to ${account.platform.displayName}?", color = theme.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will delete all hardware-encrypted cryptographic keys and credentials from Android KeyStore. Any active automated tasks for this platform will stop immediately.",
                    color = theme.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeAccount(account.id, account.platform)
                        revokingAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accentRed)
                ) {
                    Text("Revoke & Purge", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { revokingAccount = null }) {
                    Text("Cancel", color = theme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun AccountCard(
    platform: SocialPlatform,
    account: SocialAccount?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRevoke: () -> Unit
) {
    val theme = AppTheme.colors
    val isConnected = account?.status == AccountStatus.CONNECTED

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, if (isConnected) theme.accentCyan.copy(alpha = 0.35f) else theme.borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Platform Initial Badge
                Box(
                    modifier = Modifier.size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isConnected) theme.accentCyan.copy(alpha = 0.15f) else theme.borderColor.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        platform.displayName.take(2).uppercase(),
                        color = if (isConnected) theme.accentCyan else theme.textSecondary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            platform.displayName,
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(account?.status ?: AccountStatus.DISCONNECTED)
                    }

                    if (isConnected && account != null) {
                        Text(
                            if (account.username.isNotBlank()) "@${account.username}" else account.displayName,
                            color = theme.textSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            "Not connected",
                            color = theme.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                if (!isConnected) {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Connect", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isConnected && account != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = theme.borderColor.copy(alpha = 0.5f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Permissions list
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Permissions:", color = theme.textSecondary, fontSize = 11.sp)
                    account.permissions.forEach { perm ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.surface)
                                .border(0.5.dp, theme.borderColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(perm.title, color = theme.textSecondary, fontSize = 9.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stats & Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val lastSync = account.lastSyncAt
                    val syncText = if (lastSync != null && lastSync > 0) {
                        "Synced " + SimpleDateFormat("MMM d, HH:mm", LocalConfiguration.current.locales[0]).format(Date(lastSync))
                    } else {
                        "Not synced yet"
                    }
                    Text(syncText, color = theme.textSecondary, fontSize = 11.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Disconnect", color = theme.textSecondary, fontSize = 11.sp)
                        }

                        TextButton(
                            onClick = onRevoke,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Revoke", color = theme.accentRed, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: AccountStatus) {
    val theme = AppTheme.colors
    val (bgColor, textColor, text) = when (status) {
        AccountStatus.CONNECTED -> Triple(theme.accentCyan.copy(alpha = 0.15f), theme.accentCyan, "Connected")
        AccountStatus.DISCONNECTED -> Triple(theme.surface, theme.textSecondary, "Offline")
        AccountStatus.EXPIRED -> Triple(theme.accentOrange.copy(alpha = 0.15f), theme.accentOrange, "Expired")
        AccountStatus.REAUTH_REQUIRED -> Triple(theme.accentOrange.copy(alpha = 0.15f), theme.accentOrange, "Reauth Needed")
        AccountStatus.PERMISSION_DENIED -> Triple(theme.accentRed.copy(alpha = 0.15f), theme.accentRed, "Denied")
        AccountStatus.ERROR -> Triple(theme.accentRed.copy(alpha = 0.15f), theme.accentRed, "Error")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConnectAccountDialog(
    platform: SocialPlatform,
    onDismiss: () -> Unit,
    onConfirm: (SocialCredentials, List<SocialPermission>) -> Unit
) {
    val theme = AppTheme.colors

    var tokenOrKey by remember { mutableStateOf("") }
    var channelOrChatId by remember { mutableStateOf("") }
    var useSandboxMock by remember { mutableStateOf(false) }

    val selectedPermissions = remember {
        mutableStateListOf(
            SocialPermission.READ_POSTS,
            SocialPermission.PUBLISH_POSTS,
            SocialPermission.READ_COMMENTS,
            SocialPermission.REPLY_TO_COMMENTS,
            SocialPermission.READ_ANALYTICS
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.cardBackground,
        title = {
            Text("Connect ${platform.displayName}", color = theme.textPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Credentials will be encrypted with AES-256-GCM hardware keys.",
                    color = theme.textSecondary,
                    fontSize = 11.sp
                )

                // Sandbox toggle
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { useSandboxMock = !useSandboxMock },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useSandboxMock,
                        onCheckedChange = { useSandboxMock = it },
                        colors = CheckboxDefaults.colors(checkedColor = theme.accentCyan)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("Use Sandbox Mock Mode", color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Simulates live API without external accounts", color = theme.textSecondary, fontSize = 10.sp)
                    }
                }

                if (!useSandboxMock) {
                    val label1 = when (platform) {
                        SocialPlatform.TELEGRAM -> "Bot Token"
                        SocialPlatform.DISCORD -> "Bot Token or Webhook URL"
                        SocialPlatform.X -> "Bearer Token / API Key"
                        SocialPlatform.LINKEDIN -> "OAuth Access Token"
                        SocialPlatform.INSTAGRAM -> "Graph API Access Token"
                        SocialPlatform.FACEBOOK -> "Page Access Token"
                        SocialPlatform.YOUTUBE -> "OAuth Token / API Key"
                    }

                    OutlinedTextField(
                        value = tokenOrKey,
                        onValueChange = { tokenOrKey = it },
                        label = { Text(label1, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = theme.textPrimary,
                            unfocusedTextColor = theme.textPrimary,
                            focusedBorderColor = theme.accentCyan,
                            unfocusedBorderColor = theme.borderColor
                        )
                    )

                    if (platform == SocialPlatform.TELEGRAM || platform == SocialPlatform.DISCORD) {
                        val label2 = if (platform == SocialPlatform.TELEGRAM) "Chat ID / Channel ID" else "Channel ID (optional)"
                        OutlinedTextField(
                            value = channelOrChatId,
                            onValueChange = { channelOrChatId = it },
                            label = { Text(label2, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = theme.textPrimary,
                                unfocusedTextColor = theme.textPrimary,
                                focusedBorderColor = theme.accentCyan,
                                unfocusedBorderColor = theme.borderColor
                            )
                        )
                    }
                }

                Text("Granted Permissions:", color = theme.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(SocialPermission.PUBLISH_POSTS, SocialPermission.REPLY_TO_COMMENTS, SocialPermission.READ_ANALYTICS).forEach { perm ->
                        val isChecked = selectedPermissions.contains(perm)
                        FilterChip(
                            selected = isChecked,
                            onClick = {
                                if (isChecked) selectedPermissions.remove(perm) else selectedPermissions.add(perm)
                            },
                            label = { Text(perm.title, fontSize = 10.sp) },
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
                    val creds = if (useSandboxMock) {
                        SocialCredentials(
                            accountId = "mock_${platform.id}_acc",
                            platform = platform,
                            accessToken = "mock_${platform.id}_token"
                        )
                    } else {
                        SocialCredentials(
                            accountId = "acc_${platform.id}",
                            platform = platform,
                            accessToken = tokenOrKey,
                            botToken = if (platform == SocialPlatform.TELEGRAM || platform == SocialPlatform.DISCORD) tokenOrKey else null,
                            channelOrTargetId = if (channelOrChatId.isNotBlank()) channelOrChatId else null
                        )
                    }
                    onConfirm(creds, selectedPermissions.toList())
                },
                enabled = useSandboxMock || tokenOrKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = theme.accentCyan)
            ) {
                Text("Authorize & Connect", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.textSecondary)
            }
        }
    )
}
