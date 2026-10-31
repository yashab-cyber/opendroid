package com.opendroid.ai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.social.domain.model.AutomationLevel
import com.opendroid.ai.ui.screens.social.*
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.SocialViewModel

data class SocialTabItem(
    val title: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    viewModel: SocialViewModel
) {
    val theme = AppTheme.colors

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val accounts by viewModel.accounts.collectAsState()
    val connectedAccounts by viewModel.connectedAccounts.collectAsState()
    val posts by viewModel.posts.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val unansweredComments by viewModel.unansweredComments.collectAsState()
    val interactions by viewModel.interactions.collectAsState()
    val campaigns by viewModel.campaigns.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val auditLogs by viewModel.auditLogs.collectAsState()
    val automationLevel by viewModel.automationLevel.collectAsState()
    val summary by viewModel.analyticsSummary.collectAsState()
    val insights by viewModel.insights.collectAsState()
    val weeklyReport by viewModel.weeklyReport.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val tabs = remember {
        listOf(
            SocialTabItem("Overview", Icons.Default.Dashboard),
            SocialTabItem("Accounts", Icons.Default.AccountCircle),
            SocialTabItem("Inbox", Icons.Default.Inbox),
            SocialTabItem("Content", Icons.Default.EditNote),
            SocialTabItem("Calendar", Icons.Default.CalendarMonth),
            SocialTabItem("Comments", Icons.Default.ChatBubbleOutline),
            SocialTabItem("Campaigns", Icons.Default.Campaign),
            SocialTabItem("Analytics", Icons.Default.BarChart),
            SocialTabItem("Reports", Icons.Default.Description),
            SocialTabItem("Settings", Icons.Default.Tune)
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = theme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.surface)
                    .border(0.5.dp, theme.borderColor)
                    .statusBarsPadding()
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(theme.accentCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                tint = theme.accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Social Studio",
                                color = theme.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "${connectedAccounts.size} active channels • ${automationLevel.displayName}",
                                color = theme.textSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = theme.accentCyan,
                                strokeWidth = 2.dp
                            )
                        }

                        IconButton(
                            onClick = { viewModel.refreshAll() },
                            modifier = Modifier.size(34.dp).background(theme.background, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Sync",
                                tint = theme.textPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Horizontal Pill Tab Bar
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(tabs) { index, tab ->
                        val isSelected = selectedTabIndex == index
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isSelected) theme.accentCyan else theme.cardBackground
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) theme.accentCyan else theme.borderColor,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { selectedTabIndex = index }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    tab.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) Color.Black else theme.textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    tab.title,
                                    color = if (isSelected) Color.Black else theme.textPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = selectedTabIndex,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                },
                label = "SocialTabTransition"
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> SocialOverviewTab(
                        summary = summary,
                        insights = insights,
                        posts = posts,
                        interactions = interactions,
                        onNavigateToTab = { selectedTabIndex = it },
                        onApprovePost = { postId -> viewModel.approvePost(postId, false) }
                    )
                    1 -> SocialAccountsTab(
                        accounts = accounts,
                        onConnectAccount = { platform, creds, perms -> viewModel.connectAccount(platform, creds, perms) },
                        onDisconnectAccount = { accountId, platform -> viewModel.disconnectAccount(accountId, platform) },
                        onRevokeAccount = { accountId, platform -> viewModel.revokeAccount(accountId, platform) },
                        onRefresh = { viewModel.refreshAll() }
                    )
                    2 -> SocialInboxTab(
                        interactions = interactions,
                        onResolve = { id -> viewModel.resolveInteraction(id) },
                        onDismiss = { id -> viewModel.dismissInteraction(id) },
                        onReply = { id, text -> viewModel.replyToComment(id, text) },
                        onRefresh = { viewModel.refreshAll() }
                    )
                    3 -> SocialContentTab(
                        posts = posts,
                        isGenerating = isGenerating,
                        onGeneratePost = { topic, platform, tone, type -> viewModel.generatePost(topic, platform, tone, type) },
                        onCreateDraft = { platform, content, type, time -> viewModel.createDraft(platform, content, type, time) },
                        onApprovePost = { postId, immediate -> viewModel.approvePost(postId, immediate) },
                        onPublishPostNow = { postId -> viewModel.publishPostNow(postId) },
                        onSchedulePost = { postId, time -> viewModel.schedulePost(postId, time) },
                        onCancelPost = { postId -> viewModel.cancelPost(postId) },
                        onDeletePost = { postId -> viewModel.deletePost(postId) },
                        onShorten = { content, platform, cb -> viewModel.shortenContent(content, platform, cb) },
                        onExpand = { content, platform, cb -> viewModel.expandContent(content, platform, cb) },
                        onChangeTone = { content, tone, platform, cb -> viewModel.changeToneContent(content, tone, platform, cb) },
                        onAddCta = { content, type, platform, cb -> viewModel.addCtaContent(content, type, platform, cb) },
                        onRemoveHashtags = { content -> viewModel.removeHashtagsContent(content) }
                    )
                    4 -> SocialCalendarTab(
                        posts = posts,
                        onReschedulePost = { postId, time -> viewModel.schedulePost(postId, time) },
                        onPublishImmediately = { postId -> viewModel.publishPostNow(postId) },
                        onCancelSchedule = { postId -> viewModel.cancelPost(postId) },
                        onNavigateToComposer = { selectedTabIndex = 3 }
                    )
                    5 -> SocialCommentsTab(
                        comments = comments,
                        unansweredComments = unansweredComments,
                        onReplyToComment = { id, text -> viewModel.replyToComment(id, text) },
                        onIgnoreComment = { id -> viewModel.ignoreComment(id) },
                        onGenerateSuggestedReply = { comment, cb -> viewModel.generateReplyForComment(comment, cb) }
                    )
                    6 -> SocialCampaignsTab(
                        campaigns = campaigns,
                        isGenerating = isGenerating,
                        onCreateCampaign = { name, obj, days, platforms -> viewModel.createCampaign(name, obj, days, platforms) }
                    )
                    7 -> SocialAnalyticsTab(
                        summary = summary,
                        accounts = accounts,
                        posts = posts,
                        onRefresh = { viewModel.refreshAll() }
                    )
                    8 -> SocialReportsTab(
                        weeklyReport = weeklyReport,
                        insights = insights,
                        onRegenerate = { viewModel.loadAnalyticsAndInsights() }
                    )
                    9 -> SocialSettingsTab(
                        currentLevel = automationLevel,
                        rules = rules,
                        auditLogs = auditLogs,
                        onSelectLevel = { lvl -> viewModel.setAutomationLevel(lvl) },
                        onSaveRule = { rule -> viewModel.saveRule(rule) },
                        onToggleRule = { id, enabled -> viewModel.toggleRule(id, enabled) },
                        onDeleteRule = { id -> viewModel.deleteRule(id) }
                    )
                }
            }
        }
    }
}
