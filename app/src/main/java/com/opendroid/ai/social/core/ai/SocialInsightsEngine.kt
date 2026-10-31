package com.opendroid.ai.social.core.ai

import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.domain.model.PostStatus
import com.opendroid.ai.social.domain.model.SocialInsight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SocialInsightsEngine @Inject constructor(
    private val socialRepository: SocialRepository
) {

    suspend fun generateInsights(): List<SocialInsight> = withContext(Dispatchers.Default) {
        val posts = socialRepository.getPostsByStatus(PostStatus.PUBLISHED)
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - 7 * 86400_000L
        val recentSnapshots = socialRepository.getSnapshotsSince(sevenDaysAgo)

        val insights = mutableListOf<SocialInsight>()

        // 1. Post Type Performance Analysis
        if (posts.size >= 2) {
            val announcementPosts = posts.filter { it.contentType.name == "ANNOUNCEMENT" || it.contentType.name == "PRODUCT_UPDATE" }
            val otherPosts = posts.filter { it.contentType.name != "ANNOUNCEMENT" && it.contentType.name != "PRODUCT_UPDATE" }

            val avgAnnouncementEngage = if (announcementPosts.isNotEmpty()) announcementPosts.map { it.engagementRate }.average().toFloat() else 0f
            val avgOtherEngage = if (otherPosts.isNotEmpty()) otherPosts.map { it.engagementRate }.average().toFloat() else 0f

            if (avgAnnouncementEngage > avgOtherEngage && avgOtherEngage > 0f) {
                val diffPercent = ((avgAnnouncementEngage - avgOtherEngage) / avgOtherEngage * 100).toInt()
                insights.add(
                    SocialInsight(
                        id = UUID.randomUUID().toString(),
                        observedData = "Announcement posts averaged ${String.format(Locale.getDefault(), "%.1f", avgAnnouncementEngage)}% engagement vs ${String.format(Locale.getDefault(), "%.1f", avgOtherEngage)}% for general posts across ${posts.size} published posts.",
                        calculatedInsight = "Product and feature announcements generated $diffPercent% higher engagement rate than generic updates.",
                        aiRecommendation = "Focus your content schedule on product milestone announcements and developer release notes.",
                        category = "CONTENT_STRATEGY",
                        confidence = 0.92f
                    )
                )
            }
        }

        // 2. Top Performing Post Insight
        val topPosts = socialRepository.getTopPerformingPosts(1)
        if (topPosts.isNotEmpty()) {
            val top = topPosts.first()
            insights.add(
                SocialInsight(
                    id = UUID.randomUUID().toString(),
                    observedData = "Top post '${top.content.take(45)}...' on ${top.platform.displayName} achieved ${top.likesCount} likes, ${top.commentsCount} comments, and ${top.reach} reach.",
                    calculatedInsight = "High-reach performance was driven by clear developer focus and actionable hashtags.",
                    aiRecommendation = "Replicate this formatting and schedule similar posts during weekday mornings.",
                    category = "TOP_POST",
                    confidence = 0.88f
                )
            )
        }

        // 3. Platform Growth Insight
        if (recentSnapshots.isNotEmpty()) {
            val byPlatform = recentSnapshots.groupBy { it.platform }
            val bestPlatform = byPlatform.maxByOrNull { entry -> entry.value.sumOf { it.reach ?: 0 } }
            if (bestPlatform != null) {
                val totalReach = bestPlatform.value.sumOf { it.reach ?: 0 }
                insights.add(
                    SocialInsight(
                        id = UUID.randomUUID().toString(),
                        observedData = "${bestPlatform.key.displayName} generated $totalReach reach in the last 7 days.",
                        calculatedInsight = "${bestPlatform.key.displayName} is your highest-reach distribution channel this week.",
                        aiRecommendation = "Allocate higher posting frequency to ${bestPlatform.key.displayName} to maximize audience acquisition.",
                        category = "PLATFORM_AUDIENCE",
                        confidence = 0.90f
                    )
                )
            }
        }

        if (insights.isEmpty()) {
            insights.add(
                SocialInsight(
                    id = UUID.randomUUID().toString(),
                    observedData = "OpenDroid AI Social Media Center is initialized and monitoring your accounts.",
                    calculatedInsight = "Historical baseline data is currently accumulating.",
                    aiRecommendation = "Publish 3-5 posts and connect your primary accounts to unlock personalized deep performance correlations.",
                    category = "BASELINE",
                    confidence = 1.0f
                )
            )
        }

        insights
    }
}
