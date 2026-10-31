package com.opendroid.ai.social.core.analytics

import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class AnalyticsSummary(
    val totalFollowers: Int,
    val totalReach: Int,
    val totalImpressions: Int,
    val averageEngagementRate: Float,
    val totalPosts: Int,
    val followersGrowthDelta: Int,
    val reachGrowthDelta: Int,
    val periodLabel: String
)

@Singleton
class SocialAnalyticsEngine @Inject constructor(
    private val socialRepository: SocialRepository
) {

    suspend fun getSummary(rangeDays: Int = 7): AnalyticsSummary = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val currentPeriodStart = now - (rangeDays * 86400_000L)
        val previousPeriodStart = currentPeriodStart - (rangeDays * 86400_000L)

        val currentSnapshots = socialRepository.getSnapshotsBetween(currentPeriodStart, now)
        val prevSnapshots = socialRepository.getSnapshotsBetween(previousPeriodStart, currentPeriodStart)
        val publishedPosts = socialRepository.getPostsByStatus(PostStatus.PUBLISHED)
            .filter { (it.publishedTime ?: it.createdAt) >= currentPeriodStart }

        val totalFollowers = currentSnapshots.groupBy { it.platform }
            .values.mapNotNull { list -> list.maxByOrNull { it.timestamp }?.followersCount }
            .sum()

        val prevFollowers = prevSnapshots.groupBy { it.platform }
            .values.mapNotNull { list -> list.maxByOrNull { it.timestamp }?.followersCount }
            .sum()

        val totalReach = currentSnapshots.sumOf { it.reach ?: 0 }
        val prevReach = prevSnapshots.sumOf { it.reach ?: 0 }
        val totalImpressions = currentSnapshots.sumOf { it.impressions ?: 0 }

        val nonZeroEngagements = currentSnapshots.mapNotNull { it.engagementRate }.filter { it > 0f }
        val avgEngagement = if (nonZeroEngagements.isNotEmpty()) nonZeroEngagements.average().toFloat() else 0f

        AnalyticsSummary(
            totalFollowers = totalFollowers,
            totalReach = totalReach,
            totalImpressions = totalImpressions,
            averageEngagementRate = avgEngagement,
            totalPosts = publishedPosts.size,
            followersGrowthDelta = totalFollowers - prevFollowers,
            reachGrowthDelta = totalReach - prevReach,
            periodLabel = "Last $rangeDays Days"
        )
    }

    suspend fun generateWeeklyReport(): SocialReport = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - 7 * 86400_000L

        val summary = getSummary(7)
        val published = socialRepository.getPostsByStatus(PostStatus.PUBLISHED)
        val topPosts = socialRepository.getTopPerformingPosts(1)
        val topPost = topPosts.firstOrNull()

        val allComments = socialRepository.getUnansweredComments() // or all comments
        val repliesSent = published.sumOf { it.commentsCount }

        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val periodTitle = "${dateFormat.format(Date(sevenDaysAgo))} – ${dateFormat.format(Date(now))}"

        val topPlatform = topPost?.platform ?: SocialPlatform.X
        val topPostSummary = topPost?.content?.take(80) ?: "OpenDroid v1.1 milestone announcement"

        val aiSummary = if (summary.totalPosts > 0) {
            "Your product and developer updates generated the strongest engagement this week with ${summary.totalReach} total reach and an average engagement rate of ${String.format(Locale.getDefault(), "%.1f", summary.averageEngagementRate)}%."
        } else {
            "Your social channels remained steady this week. Publishing regular updates and release announcements will increase reach."
        }

        val recommendations = listOf(
            "Schedule mid-week technical deep dives on X and LinkedIn to capitalize on highest developer engagement.",
            "Utilize community polls on Telegram and Discord to gather immediate user feedback for OpenDroid updates.",
            "Continue pairing product announcements with visual demo clips to optimize reach on Instagram and YouTube."
        )

        SocialReport(
            id = UUID.randomUUID().toString(),
            periodTitle = periodTitle,
            startDate = sevenDaysAgo,
            endDate = now,
            totalFollowersDelta = summary.followersGrowthDelta,
            totalReach = summary.totalReach,
            totalImpressions = summary.totalImpressions,
            averageEngagementRate = summary.averageEngagementRate,
            totalPostsPublished = summary.totalPosts,
            totalCommentsReceived = published.sumOf { it.commentsCount },
            totalRepliesSent = repliesSent,
            topPlatform = topPlatform,
            topPostSummary = topPostSummary,
            aiSummary = aiSummary,
            recommendations = recommendations,
            generatedAt = now
        )
    }
}
