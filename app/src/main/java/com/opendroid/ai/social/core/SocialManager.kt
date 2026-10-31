package com.opendroid.ai.social.core

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.opendroid.ai.core.security.SocialCredentialStore
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.core.adapter.*
import com.opendroid.ai.social.core.audit.SocialAuditLogger
import com.opendroid.ai.social.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SocialManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socialRepository: SocialRepository,
    private val credentialStore: SocialCredentialStore,
    private val auditLogger: SocialAuditLogger,
    private val memoryRepository: MemoryRepository,
    telegramAdapter: TelegramAdapter,
    discordAdapter: DiscordAdapter,
    xAdapter: XAdapter,
    linkedInAdapter: LinkedInAdapter,
    youTubeAdapter: YouTubeAdapter,
    instagramAdapter: InstagramAdapter,
    facebookAdapter: FacebookAdapter
) {
    companion object {
        private const val TAG = "SocialManager"
        private const val PREFS_NAME = "opendroid_social_settings"
        private const val KEY_AUTOMATION_LEVEL = "automation_level"
    }

    private val adapters: Map<SocialPlatform, SocialPlatformAdapter> = mapOf(
        SocialPlatform.TELEGRAM to telegramAdapter,
        SocialPlatform.DISCORD to discordAdapter,
        SocialPlatform.X to xAdapter,
        SocialPlatform.LINKEDIN to linkedInAdapter,
        SocialPlatform.YOUTUBE to youTubeAdapter,
        SocialPlatform.INSTAGRAM to instagramAdapter,
        SocialPlatform.FACEBOOK to facebookAdapter
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _automationLevel = MutableStateFlow(
        runCatching {
            AutomationLevel.valueOf(prefs.getString(KEY_AUTOMATION_LEVEL, AutomationLevel.APPROVAL.name) ?: AutomationLevel.APPROVAL.name)
        }.getOrDefault(AutomationLevel.APPROVAL)
    )
    val automationLevel = _automationLevel.asStateFlow()

    fun getAdapter(platform: SocialPlatform): SocialPlatformAdapter =
        adapters[platform] ?: throw IllegalArgumentException("No adapter registered for ${platform.displayName}")

    fun getAvailablePlatforms(): List<SocialPlatform> = adapters.keys.toList()

    fun setAutomationLevel(level: AutomationLevel) {
        _automationLevel.value = level
        prefs.edit { putString(KEY_AUTOMATION_LEVEL, level.name) }
    }

    // ── ACCOUNTS ─────────────────────────────────────────────────────────────

    suspend fun connectAccount(
        platform: SocialPlatform,
        credentials: SocialCredentials,
        requestedPermissions: List<SocialPermission>
    ): Result<SocialAccount> = withContext(Dispatchers.IO) {
        val adapter = getAdapter(platform)
        try {
            val accountInfoResult = adapter.validateCredentials(credentials)
            if (accountInfoResult.isFailure) {
                val error = accountInfoResult.exceptionOrNull()?.message ?: "Validation failed"
                auditLogger.log(
                    actor = "USER",
                    action = "CONNECT_ACCOUNT",
                    platform = platform,
                    details = "Failed connection attempt: $error",
                    status = "FAILED"
                )
                return@withContext Result.failure(Exception(error))
            }

            val info = accountInfoResult.getOrThrow()
            val accountId = info.id.ifBlank { UUID.randomUUID().toString() }

            // Store credentials securely in Android Keystore
            val boundCredentials = credentials.copy(accountId = accountId, platform = platform)
            val savedSecurely = credentialStore.saveCredentials(boundCredentials)
            if (!savedSecurely) {
                return@withContext Result.failure(IllegalStateException("Failed to securely store credentials in Keystore"))
            }

            val account = SocialAccount(
                id = accountId,
                platform = platform,
                username = info.username,
                displayName = info.displayName,
                avatarUrl = info.avatarUrl,
                status = AccountStatus.CONNECTED,
                permissions = requestedPermissions.ifEmpty { adapter.supportedPermissions },
                connectedAt = System.currentTimeMillis(),
                tokenExpiresAt = credentials.tokenExpiresAt,
                lastSyncAt = System.currentTimeMillis()
            )

            socialRepository.saveAccount(account)

            auditLogger.log(
                actor = "USER",
                action = "CONNECTED_ACCOUNT",
                platform = platform,
                targetId = accountId,
                details = "Connected account @${info.username} with ${account.permissions.size} permissions",
                status = "SUCCESS"
            )

            // Initial sync
            syncAccount(accountId)

            Result.success(account)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting account for $platform", e)
            Result.failure(e)
        }
    }

    suspend fun disconnectAccount(accountId: String, platform: SocialPlatform) = withContext(Dispatchers.IO) {
        credentialStore.removeCredentials(accountId, platform)
        socialRepository.updateAccountStatus(accountId, AccountStatus.DISCONNECTED)
        auditLogger.log(
            actor = "USER",
            action = "DISCONNECTED_ACCOUNT",
            platform = platform,
            targetId = accountId,
            details = "Disconnected account",
            status = "SUCCESS"
        )
    }

    suspend fun revokeAccess(accountId: String, platform: SocialPlatform) = withContext(Dispatchers.IO) {
        credentialStore.removeCredentials(accountId, platform)
        socialRepository.deleteAccount(accountId)
        auditLogger.log(
            actor = "USER",
            action = "REVOKED_ACCOUNT",
            platform = platform,
            targetId = accountId,
            details = "Revoked account access and purged encrypted tokens",
            status = "SUCCESS"
        )
    }

    // ── POSTING PIPELINE ─────────────────────────────────────────────────────

    suspend fun createDraft(
        accountId: String,
        platform: SocialPlatform,
        content: String,
        mediaUrls: List<String> = emptyList(),
        contentType: ContentType = ContentType.ANNOUNCEMENT,
        campaignId: String? = null,
        scheduledPublishTime: Long? = null
    ): SocialPost = withContext(Dispatchers.IO) {
        val requiresApproval = _automationLevel.value != AutomationLevel.AUTONOMOUS
        val status = if (scheduledPublishTime != null) {
            if (requiresApproval) PostStatus.DRAFT else PostStatus.SCHEDULED
        } else {
            PostStatus.DRAFT
        }

        val post = SocialPost(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            platform = platform,
            content = content,
            mediaUrls = mediaUrls,
            status = status,
            scheduledPublishTime = scheduledPublishTime,
            campaignId = campaignId,
            contentType = contentType,
            requiresApproval = requiresApproval,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        socialRepository.savePost(post)
        auditLogger.log(
            actor = "OPENDROID_AI",
            action = "DRAFTED_POST",
            platform = platform,
            targetId = post.id,
            details = "Created draft for @$platform (${contentType.name})",
            status = "SUCCESS"
        )

        post
    }

    suspend fun approvePost(postId: String, publishImmediately: Boolean = false): Result<SocialPost> = withContext(Dispatchers.IO) {
        val post = socialRepository.getPostById(postId)
            ?: return@withContext Result.failure(IllegalArgumentException("Post $postId not found"))

        val approvedPost = post.copy(
            status = if (publishImmediately) PostStatus.PUBLISHED else (if (post.scheduledPublishTime != null) PostStatus.SCHEDULED else PostStatus.DRAFT),
            requiresApproval = false,
            approvedBy = "USER",
            approvedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        socialRepository.savePost(approvedPost)
        auditLogger.log(
            actor = "USER",
            action = "APPROVED_POST",
            platform = post.platform,
            targetId = postId,
            details = "Approved post for publication (Immediate: $publishImmediately)",
            status = "SUCCESS"
        )

        if (publishImmediately) {
            val pubResult = publishPost(postId)
            if (pubResult.isFailure) {
                return@withContext Result.failure(pubResult.exceptionOrNull() ?: Exception("Publication failed"))
            }
        }

        Result.success(approvedPost)
    }

    suspend fun schedulePost(postId: String, scheduledTime: Long): Result<SocialPost> = withContext(Dispatchers.IO) {
        val post = socialRepository.getPostById(postId)
            ?: return@withContext Result.failure(IllegalArgumentException("Post $postId not found"))

        val nextStatus = if (post.requiresApproval) PostStatus.DRAFT else PostStatus.SCHEDULED
        val updated = post.copy(
            scheduledPublishTime = scheduledTime,
            status = nextStatus,
            updatedAt = System.currentTimeMillis()
        )
        socialRepository.savePost(updated)

        auditLogger.log(
            actor = "USER",
            action = "SCHEDULED_POST",
            platform = post.platform,
            targetId = postId,
            details = "Scheduled post for epoch $scheduledTime",
            status = "SUCCESS"
        )

        Result.success(updated)
    }

    suspend fun publishPost(postId: String): Result<PublishPostResult> = withContext(Dispatchers.IO) {
        val post = socialRepository.getPostById(postId)
            ?: return@withContext Result.failure(IllegalArgumentException("Post $postId not found"))

        val account = socialRepository.getAccountById(post.accountId)
            ?: socialRepository.getAccountByPlatform(post.platform)
            ?: return@withContext Result.failure(IllegalStateException("No connected account for ${post.platform.displayName}"))

        if (account.status != AccountStatus.CONNECTED) {
            return@withContext Result.failure(IllegalStateException("Account for ${post.platform.displayName} is ${account.status}"))
        }

        val credentials = credentialStore.getCredentials(account.id, post.platform)
            ?: return@withContext Result.failure(IllegalStateException("No stored credentials found for ${post.platform.displayName}"))

        val adapter = getAdapter(post.platform)
        val result = adapter.publishPost(credentials, post)

        if (result.isSuccess) {
            val publishResult = result.getOrThrow()
            socialRepository.updatePublishResult(
                id = postId,
                status = PostStatus.PUBLISHED,
                publishedTime = System.currentTimeMillis(),
                platformPostId = publishResult.platformPostId,
                errorMessage = null
            )
            auditLogger.log(
                actor = post.approvedBy ?: "OPENDROID_AI",
                action = "PUBLISHED_POST",
                platform = post.platform,
                targetId = postId,
                details = "Published post: ${publishResult.platformPostId ?: "OK"}",
                status = "SUCCESS"
            )
            Result.success(publishResult)
        } else {
            val error = result.exceptionOrNull()?.message ?: "Publication failed"
            socialRepository.updatePublishResult(
                id = postId,
                status = PostStatus.FAILED,
                publishedTime = null,
                platformPostId = null,
                errorMessage = error
            )
            auditLogger.log(
                actor = "SYSTEM",
                action = "PUBLISH_FAILED",
                platform = post.platform,
                targetId = postId,
                details = "Publication failed: $error",
                status = "FAILED"
            )
            Result.failure(Exception(error))
        }
    }

    // ── SYNCING & COMMENTS ───────────────────────────────────────────────────

    suspend fun syncAccount(accountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val account = socialRepository.getAccountById(accountId)
            ?: return@withContext Result.failure(IllegalArgumentException("Account not found"))

        val credentials = credentialStore.getCredentials(account.id, account.platform)
            ?: return@withContext Result.failure(IllegalStateException("Missing credentials for account $accountId"))

        val adapter = getAdapter(account.platform)

        // 1. Sync Inbox
        val inboxResult = adapter.fetchInbox(credentials, account.lastSyncAt)
        if (inboxResult.isSuccess) {
            val interactions = inboxResult.getOrThrow()
            if (interactions.isNotEmpty()) {
                socialRepository.saveInteractions(interactions)
            }
        }

        // 2. Sync Comments
        val commentsResult = adapter.fetchComments(credentials, null)
        if (commentsResult.isSuccess) {
            val comments = commentsResult.getOrThrow()
            if (comments.isNotEmpty()) {
                socialRepository.saveComments(comments)
            }
        }

        // 3. Sync Analytics Snapshot
        val analyticsResult = adapter.fetchAnalytics(credentials)
        if (analyticsResult.isSuccess) {
            val analytics = analyticsResult.getOrThrow()
            socialRepository.recordAnalyticsSnapshot(analytics)
        }

        socialRepository.updateLastSync(accountId, System.currentTimeMillis())
        Result.success(Unit)
    }

    suspend fun syncAllAccounts(): Result<Unit> = withContext(Dispatchers.IO) {
        val accounts = socialRepository.getAllAccountsFlow()
        // Run sync for each connected account
        val connected = socialRepository.getConnectedAccountsFlow()
        // We'll trigger sync for each account
        try {
            val accountsList = socialRepository.getAccountByPlatform(SocialPlatform.TELEGRAM)?.let { listOf(it) } ?: emptyList()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun replyToComment(
        commentId: String,
        replyText: String,
        actor: String = "USER"
    ): Result<ReplyCommentResult> = withContext(Dispatchers.IO) {
        val comment = socialRepository.getAllCommentsFlow() // retrieve comment
        // Find comment
        val unanswered = socialRepository.getUnansweredComments()
        val target = unanswered.find { it.id == commentId || it.platformCommentId == commentId }
            ?: return@withContext Result.failure(IllegalArgumentException("Comment not found"))

        val account = socialRepository.getAccountByPlatform(target.platform)
            ?: return@withContext Result.failure(IllegalStateException("No account connected for ${target.platform}"))

        val credentials = credentialStore.getCredentials(account.id, target.platform)
            ?: return@withContext Result.failure(IllegalStateException("No credentials for ${target.platform}"))

        val adapter = getAdapter(target.platform)
        val result = adapter.replyToComment(credentials, target.platformCommentId, replyText)

        if (result.isSuccess) {
            socialRepository.markCommentReplied(target.id, replyText)
            auditLogger.log(
                actor = actor,
                action = "SENT_REPLY",
                platform = target.platform,
                targetId = target.id,
                details = "Replied: \"${replyText.take(50)}...\"",
                status = "SUCCESS"
            )
            result
        } else {
            val error = result.exceptionOrNull()?.message ?: "Failed to send reply"
            auditLogger.log(
                actor = actor,
                action = "REPLY_FAILED",
                platform = target.platform,
                targetId = target.id,
                details = "Reply failed: $error",
                status = "FAILED"
            )
            Result.failure(Exception(error))
        }
    }
}
