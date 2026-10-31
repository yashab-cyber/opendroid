package com.opendroid.ai.actions

import android.content.Context
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.core.SocialManager
import com.opendroid.ai.social.core.ai.*
import com.opendroid.ai.social.core.analytics.SocialAnalyticsEngine
import com.opendroid.ai.social.domain.model.ContentType
import com.opendroid.ai.social.domain.model.SocialPlatform
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SocialActions @Inject constructor(
    private val socialManager: SocialManager,
    private val socialRepository: SocialRepository,
    private val contentComposer: SocialContentComposer,
    private val replyEngine: SocialCommentReplyEngine,
    private val campaignGenerator: SocialCampaignGenerator,
    private val analyticsEngine: SocialAnalyticsEngine,
    private val memoryRepository: MemoryRepository
) {

    fun getActions(): List<Action> = listOf(
        ShowSocialPerformanceAction(),
        CreateSocialPostAction(),
        ScheduleSocialPostAction(),
        CheckSocialInboxAction(),
        DraftCommentRepliesAction(),
        ShowTopContentAction(),
        CreateWeeklyReportAction(),
        CreateSocialCampaignAction()
    )

    inner class ShowSocialPerformanceAction : Action {
        override val name: String = "SOCIAL_SHOW_PERFORMANCE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val days = params["days"]?.toIntOrNull() ?: 7
                val summary = analyticsEngine.getSummary(days)
                val text = buildString {
                    appendLine("📊 **Social Media Performance (${summary.periodLabel})**")
                    appendLine("• Total Followers: ${summary.totalFollowers} (${if (summary.followersGrowthDelta >= 0) "+${summary.followersGrowthDelta}" else "${summary.followersGrowthDelta}"})")
                    appendLine("• Total Reach: ${summary.totalReach}")
                    appendLine("• Total Impressions: ${summary.totalImpressions}")
                    appendLine("• Avg Engagement Rate: ${String.format(Locale.getDefault(), "%.1f", summary.averageEngagementRate)}%")
                    appendLine("• Posts Published: ${summary.totalPosts}")
                }
                ActionResult(true, text)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to retrieve social performance: ${e.message}")
            }
        }
    }

    inner class CreateSocialPostAction : Action {
        override val name: String = "SOCIAL_CREATE_POST"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val topic = params["topic"] ?: params["content"] ?: "OpenDroid milestone announcement"
            val platformStr = params["platform"] ?: "X"
            val platform = SocialPlatform.fromId(platformStr)
            val toneStr = params["tone"] ?: "PROFESSIONAL"
            val tone = runCatching { Tone.valueOf(toneStr.uppercase()) }.getOrDefault(Tone.PROFESSIONAL)

            return try {
                val generated = contentComposer.generateContentForPlatform(topic, platform, tone)
                val account = socialRepository.getAccountByPlatform(platform)
                val accountId = account?.id ?: "pending"

                val draft = socialManager.createDraft(
                    accountId = accountId,
                    platform = platform,
                    content = generated.content,
                    contentType = ContentType.ANNOUNCEMENT
                )

                val responseText = buildString {
                    appendLine("✨ **Drafted Post for ${platform.displayName}**")
                    appendLine("\"${draft.content}\"")
                    appendLine("\nDraft saved with ID `${draft.id}`. Say \"approve\" or view in Social Center to publish.")
                }
                ActionResult(true, responseText)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to generate post: ${e.message}")
            }
        }
    }

    inner class ScheduleSocialPostAction : Action {
        override val name: String = "SOCIAL_SCHEDULE_POST"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val postId = params["postId"] ?: params["id"]
            val hoursFromNow = params["hours"]?.toLongOrNull() ?: 24L
            val scheduledTime = System.currentTimeMillis() + (hoursFromNow * 3600_000L)

            return try {
                if (postId != null) {
                    val result = socialManager.schedulePost(postId, scheduledTime)
                    if (result.isSuccess) {
                        ActionResult(true, "Post scheduled for publication in $hoursFromNow hours.")
                    } else {
                        ActionResult(false, null, result.exceptionOrNull()?.message)
                    }
                } else {
                    ActionResult(false, null, "Missing post ID to schedule.")
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to schedule post: ${e.message}")
            }
        }
    }

    inner class CheckSocialInboxAction : Action {
        override val name: String = "SOCIAL_CHECK_INBOX"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val unread = socialRepository.getUnreadInteractionsFlow().first()
                if (unread.isEmpty()) {
                    ActionResult(true, "Inbox is all caught up! No pending unread interactions.")
                } else {
                    val text = buildString {
                        appendLine("📬 **Social Inbox (${unread.size} unread interactions)**")
                        unread.take(5).forEach { item ->
                            appendLine("• [${item.platform.displayName}] **${item.authorName}** (${item.category.name}): \"${item.content.take(60)}\"")
                        }
                    }
                    ActionResult(true, text)
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to check inbox: ${e.message}")
            }
        }
    }

    inner class DraftCommentRepliesAction : Action {
        override val name: String = "SOCIAL_DRAFT_REPLIES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val unanswered = socialRepository.getUnansweredComments()
                if (unanswered.isEmpty()) {
                    ActionResult(true, "All comments have been replied to or reviewed.")
                } else {
                    var draftedCount = 0
                    for (comment in unanswered.take(3)) {
                        val reply = replyEngine.generateReply(comment)
                        socialRepository.updateSuggestedReply(comment.id, reply.replyText)
                        draftedCount++
                    }
                    ActionResult(true, "Drafted AI replies for $draftedCount comments. Ready for your review in Social Center.")
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to draft replies: ${e.message}")
            }
        }
    }

    inner class ShowTopContentAction : Action {
        override val name: String = "SOCIAL_SHOW_TOP_CONTENT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val topPosts = socialRepository.getTopPerformingPosts(3)
                if (topPosts.isEmpty()) {
                    ActionResult(true, "No published posts found in historical records yet.")
                } else {
                    val text = buildString {
                        appendLine("🏆 **Top Performing Content**")
                        topPosts.forEachIndexed { index, post ->
                            appendLine("${index + 1}. [${post.platform.displayName}] \"${post.content.take(50)}...\"")
                            appendLine("   Likes: ${post.likesCount} | Comments: ${post.commentsCount} | Engagement: ${String.format(Locale.getDefault(), "%.1f", post.engagementRate)}%")
                        }
                    }
                    ActionResult(true, text)
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to fetch top content: ${e.message}")
            }
        }
    }

    inner class CreateWeeklyReportAction : Action {
        override val name: String = "SOCIAL_CREATE_REPORT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val report = analyticsEngine.generateWeeklyReport()
                val text = buildString {
                    appendLine("📋 **OpenDroid Social Report (${report.periodTitle})**")
                    appendLine("• Followers Growth: ${report.totalFollowersDelta}")
                    appendLine("• Total Reach: ${report.totalReach}")
                    appendLine("• Avg Engagement: ${String.format(Locale.getDefault(), "%.1f", report.averageEngagementRate)}%")
                    appendLine("• Top Platform: ${report.topPlatform.displayName}")
                    appendLine("\n**AI Summary:** ${report.aiSummary}")
                    appendLine("\n**Key Recommendations:**")
                    report.recommendations.forEachIndexed { i, rec ->
                        appendLine("${i + 1}. $rec")
                    }
                }
                ActionResult(true, text)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to create social report: ${e.message}")
            }
        }
    }

    inner class CreateSocialCampaignAction : Action {
        override val name: String = "SOCIAL_CREATE_CAMPAIGN"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val name = params["name"] ?: "OpenDroid v1.1 Launch"
            val objective = params["objective"] ?: "Announce rollout, drive developer adoption, and showcase local AI"
            val days = params["days"]?.toIntOrNull() ?: 7

            return try {
                val plan = campaignGenerator.generateCampaignPlan(name, objective, days)
                socialRepository.saveCampaign(plan.campaign)
                plan.plannedPosts.forEach { post ->
                    socialRepository.savePost(post)
                }

                val text = buildString {
                    appendLine("🎯 **Campaign Strategy Generated: ${plan.campaign.name}**")
                    appendLine("• Objective: ${plan.campaign.objective}")
                    appendLine("• Duration: $days days across ${plan.campaign.platforms.joinToString { it.displayName }}")
                    appendLine("• Strategy: ${plan.campaign.strategySummary}")
                    appendLine("\nCreated ${plan.plannedPosts.size} draft posts ready for your review and approval.")
                }
                ActionResult(true, text)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to generate campaign: ${e.message}")
            }
        }
    }
}
