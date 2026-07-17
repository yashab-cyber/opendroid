package com.opendroid.ai.core.agent

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.prompts.AutoReplyPrompts
import com.opendroid.ai.core.memory.MemoryManager
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the auto-reply pipeline:
 * 1. Check if auto-reply is enabled for this app/contact
 * 2. Wait the configured delay (default 15 min) — abort if user replies manually
 * 3. Build context from notification history + user memory
 * 4. Generate reply via LLM
 * 5. Dispatch via ReplyDispatcher
 * 6. Log result to database
 */
@Singleton
class AutoReplyEngine @Inject constructor(
    private val notificationDao: NotificationDao,
    private val llmProviderFactory: LLMProviderFactory,
    private val memoryManager: MemoryManager,
    private val notificationIntelligence: NotificationIntelligence,
    private val replyDispatcher: ReplyDispatcher,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "AutoReplyEngine"
        // Cooldown period after sending an auto-reply. Any notification from the same
        // contact within this window is treated as the messaging app echoing our own
        // sent reply back as a notification — skip it to prevent infinite loops.
        private const val REPLY_BOUNCEBACK_COOLDOWN_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track pending auto-replies so we can cancel them if user replies manually
    private val pendingReplies = mutableMapOf<String, Job>()

    // Track when we last sent an auto-reply per contact key to detect bouncebacks
    private val recentlySentTimestamps = ConcurrentHashMap<String, Long>()

    /**
     * Returns true if a notification from this package/contact is likely our own
     * auto-reply bouncing back as a notification (e.g. WhatsApp showing "You: ...")
     * within the cooldown window.
     */
    fun isOwnReplyBounceback(packageName: String, contactName: String?): Boolean {
        val contactKey = "$packageName:${contactName ?: ""}"
        val lastSentTime = recentlySentTimestamps[contactKey] ?: return false
        val elapsed = System.currentTimeMillis() - lastSentTime
        if (elapsed < REPLY_BOUNCEBACK_COOLDOWN_MS) {
            Log.d(TAG, "Suppressing bounceback for $contactKey (sent ${elapsed}ms ago)")
            return true
        }
        // Cooldown expired — clean up
        recentlySentTimestamps.remove(contactKey)
        return false
    }

    /**
     * Called when a message notification is received.
     * Schedules an auto-reply after the configured delay.
     */
    fun scheduleAutoReply(
        notification: NotificationEntity,
        sbn: StatusBarNotification?,
        context: Context
    ) {
        val contactKey = "${notification.packageName}:${notification.contactName ?: notification.title}"

        // Cancel any existing pending reply for this contact (they sent another message)
        pendingReplies[contactKey]?.cancel()

        val job = scope.launch {
            try {
                val config = settingsRepository.autoReplyConfig.first()

                if (!shouldAutoReply(notification, config)) {
                    Log.d(TAG, "Auto-reply skipped for ${notification.contactName}: policy check failed")
                    return@launch
                }

                // Wait the configured delay
                val delayMs = config.replyDelayMinutes * 60 * 1000L
                Log.d(TAG, "Scheduling auto-reply in ${config.replyDelayMinutes} min for ${notification.contactName}")
                delay(delayMs)

                // Re-check config (user might have disabled it during the wait)
                val freshConfig = settingsRepository.autoReplyConfig.first()
                if (!freshConfig.globalEnabled) {
                    Log.d(TAG, "Auto-reply disabled during wait period")
                    return@launch
                }

                // Check rate limit
                val oneHourAgo = System.currentTimeMillis() - 3_600_000
                val recentReplyCount = notificationDao.getAutoReplyCountForContact(
                    notification.contactName ?: notification.title,
                    oneHourAgo
                )
                if (recentReplyCount >= freshConfig.maxRepliesPerContactPerHour) {
                    Log.d(TAG, "Rate limit reached for ${notification.contactName}")
                    return@launch
                }

                // Generate and send reply
                val replyText = generateReply(notification, freshConfig)
                if (replyText.isNullOrBlank()) {
                    Log.w(TAG, "LLM returned empty reply, skipping")
                    return@launch
                }

                val sent = dispatchReply(notification, sbn, replyText, context)
                if (sent) {
                    // Record the send timestamp BEFORE marking in DB so the
                    // bounceback check is ready when the echo notification fires
                    recentlySentTimestamps[contactKey] = System.currentTimeMillis()
                    notificationDao.markAsAutoReplied(notification.id, replyText)
                    Log.d(TAG, "Auto-reply sent to ${notification.contactName}: ${replyText.take(50)}...")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Auto-reply cancelled for ${notification.contactName}")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-reply failed: ${e.message}")
            } finally {
                pendingReplies.remove(contactKey)
            }
        }

        pendingReplies[contactKey] = job
    }

    /**
     * Cancel a pending auto-reply (e.g., user opened the app and is replying manually).
     */
    fun cancelPendingReply(packageName: String, contactName: String?) {
        val contactKey = "$packageName:${contactName ?: ""}"
        pendingReplies[contactKey]?.cancel()
        pendingReplies.remove(contactKey)
    }

    /**
     * Cancel all pending auto-replies.
     */
    fun cancelAll() {
        pendingReplies.values.forEach { it.cancel() }
        pendingReplies.clear()
    }

    private fun shouldAutoReply(notification: NotificationEntity, config: AutoReplyConfig): Boolean {
        if (!config.globalEnabled) return false

        // Check per-app enablement
        val appEnabled = when {
            notification.packageName.contains("whatsapp", ignoreCase = true) -> config.whatsappEnabled
            notification.category == "EMAIL" -> config.emailEnabled
            notification.category == "MESSAGE" -> config.smsEnabled
            else -> false
        }
        if (!appEnabled) return false

        // Check blacklist
        val contact = notification.contactName ?: notification.title
        if (config.blacklistedContacts.any { it.equals(contact, ignoreCase = true) }) {
            return false
        }

        // Check whitelist (if set, only reply to whitelisted contacts)
        if (config.whitelistedContacts.isNotEmpty()) {
            if (!config.whitelistedContacts.any { it.equals(contact, ignoreCase = true) }) {
                return false
            }
        }

        return true
    }

    private suspend fun generateReply(notification: NotificationEntity, config: AutoReplyConfig): String? {
        return try {
            val provider = llmProviderFactory.getActiveProvider()

            // Get user name from secure prefs context
            val userContext = memoryManager.getRelevantContext("")
            val userName = extractUserName(userContext)
            val senderName = notification.contactName ?: notification.title

            // Build conversation history from recent notifications with this contact
            val recentMessages = notificationDao.getNotificationsForContact(senderName)
            val conversationHistory = recentMessages
                .sortedBy { it.timestamp }
                .takeLast(10)
                .joinToString("\n") { msg ->
                    val prefix = if (msg.isAutoReplied) "$userName" else senderName
                    val text = if (msg.isAutoReplied) (msg.autoReplyText ?: "") else msg.text
                    "[$prefix]: $text"
                }

            // Build the prompt based on app type
            val systemPrompt = when {
                notification.packageName.contains("whatsapp", ignoreCase = true) ->
                    AutoReplyPrompts.buildWhatsAppReplyPrompt(
                        userName, senderName, notification.text,
                        conversationHistory, userContext, config.customPrompt
                    )
                notification.category == "EMAIL" ->
                    AutoReplyPrompts.buildEmailReplyPrompt(
                        userName, senderName, notification.title, notification.text,
                        userContext, config.customPrompt
                    )
                else ->
                    AutoReplyPrompts.buildSmsReplyPrompt(
                        userName, senderName, notification.text,
                        conversationHistory, userContext, config.customPrompt
                    )
            }

            val response = provider.complete(
                LLMRequest(
                    systemPrompt = systemPrompt,
                    messages = listOf(
                        com.opendroid.ai.data.models.ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = "Generate the reply.",
                            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
                        )
                    ),
                    temperature = 0.7f,
                    maxTokens = 150,
                    responseFormat = ResponseFormat.TEXT
                )
            )

            response.content.trim().removeSurrounding("\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate reply: ${e.message}")
            null
        }
    }

    private fun dispatchReply(
        notification: NotificationEntity,
        sbn: StatusBarNotification?,
        replyText: String,
        context: Context
    ): Boolean {
        return when {
            // WhatsApp — use notification inline reply with a fresh SBN
            notification.packageName.contains("whatsapp", ignoreCase = true) -> {
                // Fetch a fresh SBN from the NotificationListenerService — the original
                // one captured at schedule time may have a stale PendingIntent after the delay
                val listener = com.opendroid.ai.core.service.OpenDroidNotificationListener.getInstance()
                val freshSbn = listener?.getActiveNotification(
                    notification.packageName,
                    notification.contactName
                )
                val targetSbn = freshSbn ?: sbn
                if (targetSbn != null) {
                    val replied = replyDispatcher.replyViaNotificationAction(targetSbn, replyText)
                    if (!replied && freshSbn == null && sbn != null) {
                        // Both failed — the notification was probably dismissed
                        Log.w(TAG, "WhatsApp reply failed: notification may have been dismissed")
                    }
                    replied
                } else {
                    Log.w(TAG, "No StatusBarNotification available for WhatsApp reply (neither fresh nor original)")
                    false
                }
            }
            // SMS
            notification.category == "MESSAGE" -> {
                // Try fresh SBN for inline reply first
                val listener = com.opendroid.ai.core.service.OpenDroidNotificationListener.getInstance()
                val freshSbn = listener?.getActiveNotification(
                    notification.packageName,
                    notification.contactName
                ) ?: sbn

                if (freshSbn != null && replyDispatcher.replyViaNotificationAction(freshSbn, replyText)) {
                    true
                } else {
                    // Fallback to direct SMS send via phone number
                    val contact = notification.contactName ?: notification.title
                    val phoneRegex = Regex("""^\+?[0-9\s\-()]{7,20}$""")
                    if (phoneRegex.matches(contact.trim())) {
                        replyDispatcher.replyViaSms(contact.trim(), replyText, context)
                    } else {
                        Log.w(TAG, "SMS direct reply skipped: Contact name is not a valid phone number ($contact) and no notification reply action was available")
                        false
                    }
                }
            }
            // Email — use the extracted sender email address
            notification.category == "EMAIL" -> {
                val senderEmail = notification.senderEmail ?: ""
                // Try fresh SBN for inline reply first
                val listener = com.opendroid.ai.core.service.OpenDroidNotificationListener.getInstance()
                val freshSbn = listener?.getActiveNotification(
                    notification.packageName,
                    notification.contactName
                ) ?: sbn
                replyDispatcher.replyViaEmail(freshSbn, senderEmail, notification.title, replyText, context)
            }
            else -> false
        }
    }

    private fun extractUserName(context: String): String {
        val match = Regex("User Name: (.+?)(?:;|\$)").find(context)
        return match?.groupValues?.get(1)?.trim() ?: "User"
    }
}
