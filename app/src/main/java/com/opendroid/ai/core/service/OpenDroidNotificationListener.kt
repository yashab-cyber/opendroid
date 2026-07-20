package com.opendroid.ai.core.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.opendroid.ai.core.agent.AutoReplyEngine
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Listens for all system notifications, saves them to the database,
 * triggers pattern analysis, and schedules auto-replies for messages.
 */
@AndroidEntryPoint
class OpenDroidNotificationListener : NotificationListenerService() {

    @Inject lateinit var notificationDao: NotificationDao
    @Inject lateinit var autoReplyEngine: AutoReplyEngine
    @Inject lateinit var notificationIntelligence: NotificationIntelligence

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "NotifListener"
        private const val OWN_PACKAGE = "com.opendroid.ai"

        // Known message app packages
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b"
        )
        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )
        private val EMAIL_PACKAGES = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail"
        )

        @Volatile
        private var instance: OpenDroidNotificationListener? = null

        fun getInstance(): OpenDroidNotificationListener? = instance
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Skip our own notifications
        if (sbn.packageName == OWN_PACKAGE) return

        // Skip ongoing/system notifications (progress bars, media controls, etc.)
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        serviceScope.launch {
            try {
                val entity = parseNotification(sbn)
                if (entity != null) {
                    val id = notificationDao.insertNotification(entity)
                    val savedEntity = entity.copy(id = id)
                    Log.d(TAG, "Saved notification: ${entity.appName} — ${entity.title}: ${entity.text.take(50)}")

                    // Trigger pattern analysis periodically
                    notificationIntelligence.analyzeIfNeeded()

                    // Schedule auto-reply if it's a message, but NOT if it's
                    // our own reply echoing back as a notification (loop prevention)
                    if (isMessageNotification(sbn)) {
                        val contactName = entity.contactName ?: entity.title
                        val isBounceback = autoReplyEngine.isOwnReplyBounceback(
                            sbn.packageName, contactName
                        )
                        // WhatsApp shows your own sent messages as "You: <text>"
                        val isSelfMessage = entity.text.startsWith("You: ") ||
                            entity.title.equals("You", ignoreCase = true)

                        if (isBounceback) {
                            Log.d(TAG, "Skipping auto-reply: bounceback from own reply to $contactName")
                        } else if (isSelfMessage) {
                            Log.d(TAG, "Skipping auto-reply: self-message detected for $contactName")
                        } else {
                            autoReplyEngine.scheduleAutoReply(savedEntity, sbn, applicationContext)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process notification: ${e.message}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: track notification dismissals
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
    }

    /**
     * Parse a StatusBarNotification into our database entity.
     */
    private fun parseNotification(sbn: StatusBarNotification): NotificationEntity? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ""

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        // Skip empty notifications
        if (title.isBlank() && text.isBlank()) return null

        val appName = getAppName(sbn.packageName)
        val category = classifyNotification(sbn)
        val contactName = extractContactName(sbn, title)
        val senderEmail = if (category == "EMAIL") extractSenderEmail(sbn, title) else null

        return NotificationEntity(
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            category = category,
            contactName = contactName,
            senderEmail = senderEmail
        )
    }

    /**
     * Get human-readable app name from package name.
     */
    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    /**
     * Classify notification type based on package and category.
     */
    private fun classifyNotification(sbn: StatusBarNotification): String {
        val pkg = sbn.packageName
        return when {
            WHATSAPP_PACKAGES.contains(pkg) -> "MESSAGE"
            SMS_PACKAGES.contains(pkg) -> "MESSAGE"
            EMAIL_PACKAGES.contains(pkg) -> "EMAIL"
            sbn.notification?.category == Notification.CATEGORY_MESSAGE -> "MESSAGE"
            sbn.notification?.category == Notification.CATEGORY_EMAIL -> "EMAIL"
            sbn.notification?.category == Notification.CATEGORY_SOCIAL -> "SOCIAL"
            sbn.notification?.category == Notification.CATEGORY_SYSTEM -> "SYSTEM"
            else -> "OTHER"
        }
    }

    /**
     * Extract the sender/contact name from notification.
     */
    private fun extractContactName(sbn: StatusBarNotification, title: String): String? {
        val extras = sbn.notification?.extras ?: return title.ifBlank { null }

        // Try messaging-style person
        val messagingPerson = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        if (!messagingPerson.isNullOrBlank()) return messagingPerson

        // For WhatsApp, the title is usually the contact name
        if (WHATSAPP_PACKAGES.contains(sbn.packageName)) {
            return title.ifBlank { null }
        }

        // For SMS, title is the contact/number
        if (SMS_PACKAGES.contains(sbn.packageName)) {
            return title.ifBlank { null }
        }

        // For email, try to extract sender
        if (EMAIL_PACKAGES.contains(sbn.packageName)) {
            return title.ifBlank { null }
        }

        return title.ifBlank { null }
    }

    /**
     * Check if this is a message notification that could receive an auto-reply.
     */
    private fun isMessageNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (WHATSAPP_PACKAGES.contains(pkg)) return true
        if (SMS_PACKAGES.contains(pkg)) return true
        if (EMAIL_PACKAGES.contains(pkg)) return true
        if (sbn.notification?.category == Notification.CATEGORY_MESSAGE) return true
        if (sbn.notification?.category == Notification.CATEGORY_EMAIL) return true
        return false
    }

    /**
     * Extract sender email address from email notification extras.
     * Gmail includes the sender email in various extras; Outlook typically
     * includes it in the title as "Name <email@example.com>".
     */
    private fun extractSenderEmail(sbn: StatusBarNotification, title: String): String? {
        val extras = sbn.notification?.extras ?: return null
        val emailRegex = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

        // 1. Check android.subText / android.infoText — Gmail puts account email here
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (!subText.isNullOrBlank()) {
            emailRegex.find(subText)?.value?.let { return it }
        }
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        if (!infoText.isNullOrBlank()) {
            emailRegex.find(infoText)?.value?.let { return it }
        }

        // 2. Check the big text (full email body often starts with "From: ...")
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (!bigText.isNullOrBlank()) {
            // Look for "From: ... <email>" pattern in the body
            val fromMatch = Regex("(?i)from:?\\s*.*?([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})").find(bigText)
            fromMatch?.groupValues?.get(1)?.let { return it }
        }

        // 3. Check the title — Outlook often uses "Name <email@example.com>" format
        emailRegex.find(title)?.value?.let { return it }

        // 4. Check EXTRA_PEOPLE_LIST (API 28+)
        try {
            @Suppress("DEPRECATION")
            val people = extras.getStringArray(Notification.EXTRA_PEOPLE)
            people?.forEach { person ->
                if (person.startsWith("mailto:")) {
                    return person.removePrefix("mailto:")
                }
                emailRegex.find(person)?.value?.let { return it }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Get a fresh active StatusBarNotification matching the given package and contact.
     * This is needed because the original SBN's PendingIntent can go stale after the
     * auto-reply delay period. Re-fetching ensures we have a valid reply action.
     */
    fun getActiveNotification(packageName: String, contactName: String?): StatusBarNotification? {
        return try {
            val activeNotifications = getActiveNotifications() ?: return null
            // Try to find a matching notification by package and sender name
            activeNotifications.firstOrNull { sbn ->
                sbn.packageName == packageName &&
                sbn.notification?.extras?.let { extras ->
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""
                    contactName == null || title == contactName || convTitle == contactName
                } == true &&
                sbn.notification?.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
            } ?: activeNotifications.firstOrNull { sbn ->
                // Fallback: any notification from the same package that has reply actions
                sbn.packageName == packageName &&
                sbn.notification?.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active notifications: ${e.message}")
            null
        }
    }
}

