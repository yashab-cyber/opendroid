package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinkedInAdapter @Inject constructor(
    httpClient: OkHttpClient
) : BaseSocialAdapter(httpClient), SocialPlatformAdapter {

    override val platform: SocialPlatform = SocialPlatform.LINKEDIN
    override val displayName: String = "LinkedIn"
    override val supportedPermissions: List<SocialPermission> = listOf(
        SocialPermission.READ_PROFILE,
        SocialPermission.READ_POSTS,
        SocialPermission.READ_COMMENTS,
        SocialPermission.REPLY_TO_COMMENTS,
        SocialPermission.PUBLISH_POSTS,
        SocialPermission.SCHEDULE_POSTS,
        SocialPermission.READ_ANALYTICS
    )

    override suspend fun validateCredentials(credentials: SocialCredentials): Result<SocialAccountInfo> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                SocialAccountInfo(
                    id = "li_${credentials.accountId.ifBlank { "opendroid_corp" }}",
                    username = "opendroid-ai",
                    displayName = "OpenDroid Autonomous AI",
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.linkedin.com/v2/userinfo")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            SocialAccountInfo(
                id = json.getString("sub"),
                username = json.optString("email", "linkedin_user"),
                displayName = json.optString("name", "LinkedIn Member"),
                avatarUrl = json.optString("picture", null),
                grantedPermissions = supportedPermissions
            )
        }
    }

    override suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            val urn = "urn:li:share:${System.currentTimeMillis()}"
            return Result.success(
                PublishPostResult(
                    success = true,
                    platformPostId = urn,
                    url = "https://www.linkedin.com/feed/update/$urn"
                )
            )
        }

        val authorUrn = "urn:li:person:${credentials.accountId}"
        val textObj = JSONObject().apply { put("text", post.content) }
        val shareCommentary = JSONObject().apply { put("shareCommentary", textObj) }
        val specificContent = JSONObject().apply {
            put("com.linkedin.ugc.ShareContent", shareCommentary)
        }
        val visibility = JSONObject().apply {
            put("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC")
        }
        val payload = JSONObject().apply {
            put("author", authorUrn)
            put("lifecycleState", "PUBLISHED")
            put("specificContent", specificContent)
            put("visibility", visibility)
        }

        val request = Request.Builder()
            .url("https://api.linkedin.com/v2/ugcPosts")
            .header("Authorization", "Bearer $token")
            .header("X-Restli-Protocol-Version", "2.0.0")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val urn = json.optString("id", "urn:li:share:${System.currentTimeMillis()}")
            PublishPostResult(
                success = true,
                platformPostId = urn,
                url = "https://www.linkedin.com/feed/update/$urn"
            )
        }
    }

    override suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                listOf(
                    SocialInteraction(
                        id = "li_int_1",
                        platform = SocialPlatform.LINKEDIN,
                        accountId = credentials.accountId,
                        authorName = "Dr. Robert Vance, VP Engineering",
                        authorHandle = "linkedin.com/in/rvance",
                        type = InteractionType.DIRECT_MESSAGE,
                        category = InteractionCategory.COLLABORATION,
                        priority = InteractionPriority.HIGH,
                        content = "We are interested in licensing OpenDroid's autonomous on-device workflow engine for our mobile fleet.",
                        timestamp = System.currentTimeMillis() - 86400_000,
                        status = "UNREAD",
                        suggestedAction = "Draft partnership response"
                    )
                )
            )
        }
        return Result.success(emptyList())
    }

    override suspend fun fetchComments(credentials: SocialCredentials, postId: String?): Result<List<SocialComment>> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                listOf(
                    SocialComment(
                        id = "li_comm_1",
                        platformCommentId = "li_urn_c1",
                        postId = postId,
                        platform = SocialPlatform.LINKEDIN,
                        authorName = "Sarah Jenkins",
                        authorAvatarUrl = null,
                        content = "Very impressed with the zero-permission fallback and pure OLED UI architecture.",
                        timestamp = System.currentTimeMillis() - 14400_000,
                        sentiment = Sentiment.POSITIVE,
                        category = InteractionCategory.POSITIVE_FEEDBACK,
                        priority = InteractionPriority.NORMAL
                    )
                )
            )
        }
        return Result.success(emptyList())
    }

    override suspend fun replyToComment(credentials: SocialCredentials, commentId: String, text: String): Result<ReplyCommentResult> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                ReplyCommentResult(
                    success = true,
                    platformReplyId = "li_reply_${System.currentTimeMillis()}"
                )
            )
        }
        // There is no API call behind this yet; reporting success marked comments as
        // replied although nothing reached LinkedIn (#93).
        return Result.failure(UnsupportedOperationException("Replying on LinkedIn isn't supported yet."))
    }

    override suspend fun fetchAnalytics(credentials: SocialCredentials): Result<SocialAnalytics> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                SocialAnalytics(
                    accountId = credentials.accountId,
                    platform = SocialPlatform.LINKEDIN,
                    followersCount = 2150,
                    followingCount = 310,
                    postsCount = 42,
                    totalLikes = 890,
                    totalComments = 175,
                    totalShares = 94,
                    reach = 22400,
                    impressions = 41200,
                    engagementRate = 5.2f
                )
            )
        }
        return Result.success(
            SocialAnalytics(
                accountId = credentials.accountId,
                platform = SocialPlatform.LINKEDIN,
                followersCount = null
            )
        )
    }

    override suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials> {
        return Result.success(credentials)
    }
}
