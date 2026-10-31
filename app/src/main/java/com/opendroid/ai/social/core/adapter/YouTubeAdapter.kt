package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeAdapter @Inject constructor(
    httpClient: OkHttpClient
) : BaseSocialAdapter(httpClient), SocialPlatformAdapter {

    override val platform: SocialPlatform = SocialPlatform.YOUTUBE
    override val displayName: String = "YouTube"
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
                    id = "yt_${credentials.accountId.ifBlank { "opendroid_channel" }}",
                    username = "OpenDroidDev",
                    displayName = "OpenDroid Official",
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            )
        }

        val request = Request.Builder()
            .url("https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&mine=true")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val items = json.getJSONArray("items")
            if (items.length() == 0) throw IllegalStateException("No YouTube channel found for account")
            val channel = items.getJSONObject(0)
            val snippet = channel.getJSONObject("snippet")
            SocialAccountInfo(
                id = channel.getString("id"),
                username = snippet.optString("customUrl", "youtube_channel"),
                displayName = snippet.getString("title"),
                avatarUrl = snippet.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url"),
                grantedPermissions = supportedPermissions
            )
        }
    }

    override suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult> {
        // Community post or video announcement
        if (isSandboxToken(credentials.accessToken)) {
            val vidId = "yt_post_${System.currentTimeMillis()}"
            return Result.success(
                PublishPostResult(
                    success = true,
                    platformPostId = vidId,
                    url = "https://youtube.com/post/$vidId"
                )
            )
        }
        // There is no API call behind this yet; reporting success marked posts PUBLISHED
        // although nothing reached YouTube (#93).
        return Result.failure(UnsupportedOperationException("Publishing to YouTube isn't supported yet."))
    }

    override suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                listOf(
                    SocialInteraction(
                        id = "yt_int_1",
                        platform = SocialPlatform.YOUTUBE,
                        accountId = credentials.accountId,
                        authorName = "AndroidDevTips",
                        authorHandle = "@androidtips",
                        type = InteractionType.COMMENT,
                        category = InteractionCategory.QUESTION,
                        priority = InteractionPriority.NORMAL,
                        content = "Could you post a tutorial video on creating custom macros?",
                        timestamp = System.currentTimeMillis() - 12000_000,
                        status = "UNREAD",
                        suggestedAction = "Add to video content pipeline"
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
                        id = "yt_comm_1",
                        platformCommentId = "yt_c_101",
                        postId = postId,
                        platform = SocialPlatform.YOUTUBE,
                        authorName = "TechLeadPro",
                        authorAvatarUrl = null,
                        content = "How does OpenDroid handle accessibility node hierarchies so quickly?",
                        timestamp = System.currentTimeMillis() - 6000_000,
                        sentiment = Sentiment.POSITIVE,
                        category = InteractionCategory.QUESTION,
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
                    platformReplyId = "yt_reply_${System.currentTimeMillis()}"
                )
            )
        }
        // No API call behind this yet; see publishPost (#93).
        return Result.failure(UnsupportedOperationException("Replying on YouTube isn't supported yet."))
    }

    override suspend fun fetchAnalytics(credentials: SocialCredentials): Result<SocialAnalytics> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                SocialAnalytics(
                    accountId = credentials.accountId,
                    platform = SocialPlatform.YOUTUBE,
                    subscribersCount = 5400,
                    followersCount = 5400,
                    postsCount = 19,
                    totalViews = 142000,
                    totalLikes = 8900,
                    totalComments = 950,
                    engagementRate = 6.9f
                )
            )
        }

        val request = Request.Builder()
            .url("https://www.googleapis.com/youtube/v3/channels?part=statistics&mine=true")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val stats = json.getJSONArray("items").getJSONObject(0).getJSONObject("statistics")
            SocialAnalytics(
                accountId = credentials.accountId,
                platform = SocialPlatform.YOUTUBE,
                subscribersCount = stats.optInt("subscriberCount"),
                followersCount = stats.optInt("subscriberCount"),
                postsCount = stats.optInt("videoCount"),
                totalViews = stats.optInt("viewCount"),
                totalComments = stats.optInt("commentCount")
            )
        }
    }

    override suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials> {
        return Result.success(credentials)
    }
}
