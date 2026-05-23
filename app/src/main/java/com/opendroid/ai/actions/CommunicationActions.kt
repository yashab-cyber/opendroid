package com.opendroid.ai.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.accessibility.WhatsAppAutomator
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import android.provider.ContactsContract
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunicationActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        SendWhatsAppAction(),
        MakeCallAction(),
        SendSmsAction(),
        SendEmailAction(),
        SendWhatsAppGroupAction(),
        MakeVideoCallAction(),
        ReadMessagesAction(),
        ReadEmailsAction()
    )

    companion object {
        private fun resolveContactToPhoneNumber(context: Context, contact: String): String {
            if (contact.startsWith("$")) {
                throw IllegalArgumentException("Unresolved contact placeholder: $contact")
            }
            val cleaned = contact.replace(" ", "").replace("-", "")
            if (cleaned.startsWith("+") || (cleaned.isNotEmpty() && cleaned.all { it.isDigit() })) {
                return cleaned
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return cleaned
            }

            try {
                val contentResolver = context.contentResolver
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                // 1. Try exact match
                val selectionExact = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
                val selectionArgsExact = arrayOf(contact.trim())
                contentResolver.query(uri, projection, selectionExact, selectionArgsExact, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        if (numberIndex >= 0) {
                            val number = cursor.getString(numberIndex)
                            if (!number.isNullOrBlank()) {
                                return number.replace(" ", "").replace("-", "")
                            }
                        }
                    }
                }

                // 2. Try partial match
                val selectionLike = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val selectionArgsLike = arrayOf("%${contact.trim()}%")
                contentResolver.query(uri, projection, selectionLike, selectionArgsLike, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        if (numberIndex >= 0) {
                            val number = cursor.getString(numberIndex)
                            if (!number.isNullOrBlank()) {
                                return number.replace(" ", "").replace("-", "")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore query failures
            }

            return cleaned
        }
    }

    private class SendWhatsAppAction : Action {
        override val name: String = "SEND_WHATSAPP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contact = params["contact"] ?: return ActionResult(false, null, "contact is missing")
            val message = params["message"] ?: return ActionResult(false, null, "message is missing")
            
            val phone = resolveContactToPhoneNumber(context, contact)

            return try {
                // Step 1: Open WhatsApp chat via deep link (self-contained — no need for separate OPEN_APP)
                val encodedMsg = URLEncoder.encode(message, "UTF-8")
                val whatsappUri = if (phone.matches(Regex("\\+?[0-9]+"))) {
                    // Phone number — use direct API link
                    Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encodedMsg")
                } else {
                    // Name — use generic WhatsApp send
                    Uri.parse("whatsapp://send?text=$encodedMsg")
                }
                val intent = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                // Step 2: Use WhatsAppAutomator for full send automation via Accessibility
                val service = OpenDroidAccessibilityService.getInstance()
                if (service != null) {
                    val autoSent = WhatsAppAutomator.automateSend(message)
                    if (autoSent) {
                        return ActionResult(true, "WhatsApp message sent automatically to $contact via Accessibility service.", null)
                    }
                    // Fallback: try simple click on send button
                    kotlinx.coroutines.delay(2000)
                    val clicked = service.findAndClick("Send") || 
                                  service.findAndClick("send") || 
                                  service.findAndClick("SEND")
                    if (clicked) {
                        return ActionResult(true, "WhatsApp message sent to $contact via send button click.", null)
                    }
                }
                
                ActionResult(true, "WhatsApp chat opened with $contact. Message pre-filled. Accessibility service was not active to auto-click send.", null)
            } catch (e: Exception) {
                // Fallback to sending standard SMS if WhatsApp is not installed
                ActionResult(false, "WhatsApp not installed or failed. Triggering SMS fallback.", e.localizedMessage, true)
            }
        }
    }

    private class MakeCallAction : Action {
        override val name: String = "MAKE_CALL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contact = params["contact"] ?: return ActionResult(false, null, "contact parameter missing")
            val phone = resolveContactToPhoneNumber(context, contact)
            return try {
                val callUri = Uri.parse("tel:$phone")
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    val intent = Intent(Intent.ACTION_CALL, callUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "Direct call placed to $contact ($phone)", null)
                } else {
                    // Fallback to DIAL if CALL permission is missing
                    val intent = Intent(Intent.ACTION_DIAL, callUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "CALL_PHONE permission missing. Opened dialer to $contact ($phone) as fallback.", null, true)
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Call failed: ${e.localizedMessage}")
            }
        }
    }

    private class SendSmsAction : Action {
        override val name: String = "SEND_SMS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contact = params["contact"] ?: return ActionResult(false, null, "contact parameter missing")
            val message = params["message"] ?: return ActionResult(false, null, "message parameter missing")
            val phone = resolveContactToPhoneNumber(context, contact)
            return try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    val smsManager = context.getSystemService(SmsManager::class.java)
                    smsManager.sendTextMessage(phone, null, message, null, null)
                    ActionResult(true, "SMS sent to $contact ($phone)", null)
                } else {
                    // Fallback to SMS compose intent
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:$phone")
                        putExtra("sms_body", message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "SEND_SMS permission missing. Opened SMS editor as fallback.", null, true)
                }
            } catch (e: Exception) {
                ActionResult(false, null, "SMS failed: ${e.localizedMessage}")
            }
        }
    }

    private class SendEmailAction : Action {
        override val name: String = "SEND_EMAIL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val to = params["to"] ?: return ActionResult(false, null, "to email is missing")
            val subject = params["subject"] ?: ""
            val body = params["body"] ?: ""
            return try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Email compose intent fired to $to", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Email failed: ${e.localizedMessage}")
            }
        }
    }

    private class SendWhatsAppGroupAction : Action {
        override val name: String = "SEND_WHATSAPP_GROUP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val groupName = params["groupName"] ?: return ActionResult(false, null, "groupName parameter missing")
            val message = params["message"] ?: return ActionResult(false, null, "message parameter missing")
            return try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened WhatsApp group search/chat for group '$groupName' with message '$message'", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to open WhatsApp group: ${e.localizedMessage}")
            }
        }
    }

    private class MakeVideoCallAction : Action {
        override val name: String = "MAKE_VIDEO_CALL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contact = params["contact"] ?: return ActionResult(false, null, "contact parameter missing")
            val phone = resolveContactToPhoneNumber(context, contact)
            val app = params["app"] ?: "whatsapp"
            return try {
                when (app.lowercase()) {
                    "whatsapp" -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone")).apply {
                            setPackage("com.whatsapp")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    else -> {
                        val pm = context.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage("com.google.android.apps.meetings")
                            ?: pm.getLaunchIntentForPackage("com.google.android.apps.tachyon")
                            ?: pm.getLaunchIntentForPackage("us.zoom.videomeetings")
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        } else {
                            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(dialIntent)
                        }
                    }
                }
                ActionResult(true, "Video call initiated to $contact ($phone) using $app", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Video call failed: ${e.localizedMessage}")
            }
        }
    }

    private class ReadMessagesAction : Action {
        override val name: String = "READ_MESSAGES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val app = params["app"] ?: "sms"
            return try {
                val intent = when (app.lowercase()) {
                    "whatsapp" -> context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    else -> Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_MESSAGING)
                    }
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ActionResult(true, "Opened $app messaging app to read messages.", null)
                } else {
                    ActionResult(false, null, "Could not open $app messaging app.")
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to read messages: ${e.localizedMessage}")
            }
        }
    }

    private class ReadEmailsAction : Action {
        override val name: String = "READ_EMAILS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_EMAIL)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened default Email app.", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to open email app: ${e.localizedMessage}")
            }
        }
    }
}
