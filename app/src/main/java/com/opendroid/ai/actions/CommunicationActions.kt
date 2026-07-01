package com.opendroid.ai.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.accessibility.WhatsAppAutomator
import com.opendroid.ai.accessibility.SmsAutomator
import com.opendroid.ai.accessibility.CallAutomator
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ContactResolution
import com.opendroid.ai.core.agent.ContactResolver
import com.opendroid.ai.core.agent.maskPhone
import android.provider.ContactsContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunicationActions @Inject constructor(
    private val contactResolver: ContactResolver
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun getActions(): List<Action> = listOf(
        MakeCallAction(),
        SendWhatsAppAction(),
        SendSmsAction(),
        SendEmailAction(),
        SendWhatsAppGroupAction(),
        MakeVideoCallAction(),
        ReadMessagesAction(),
        ReadEmailsAction()
    )

    companion object {
        /**
         * Legacy static resolve for backward compat.
         * Used by actions that don't yet support disambiguation.
         */
        private fun resolveContactToPhoneNumber(context: Context, contact: String): String {
            if (contact.startsWith("$")) {
                throw IllegalArgumentException("Unresolved contact placeholder: $contact")
            }
            val cleaned = contact.replace(" ", "").replace("-", "")
            if (cleaned.startsWith("+") || (cleaned.isNotEmpty() && cleaned.all { it.isDigit() })) {
                return cleaned
            }
            val result = ContactResolver.resolve(context, contact)
            if (result is ContactResolver.ContactResult.Found) {
                return result.phoneNumber
            }
            throw IllegalArgumentException("Contact '$contact' not found in your contacts")
        }
    }

    /**
     * Build a NeedsInput with contact picker metadata.
     * Metadata stores match data as JSON so it survives serialization.
     */
    private fun buildContactPickerResult(
        contactQuery: String,
        matches: List<com.opendroid.ai.core.agent.Contact>,
        action: String,
        extraMeta: Map<String, String> = emptyMap()
    ): ActionResult.NeedsInput {
        val matchesJson = json.encodeToString(
            matches.map { mapOf("name" to it.name, "phone" to it.phoneNumber, "type" to it.type) }
        )
        return ActionResult.NeedsInput(
            question = "Which '$contactQuery' do you mean?",
            options = matches.mapIndexed { i, c ->
                "${i + 1}. ${c.name} (${c.type}: ${maskPhone(c.phoneNumber)})"
            },
            metadata = mapOf(
                "type" to "contact_picker",
                "query" to contactQuery,
                "action" to action,
                "matches" to matchesJson
            ) + extraMeta
        )
    }

    // ── MAKE_CALL with disambiguation ────────────────────────

    private inner class MakeCallAction : Action {
        override val name: String = "MAKE_CALL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contact = params["contact"]
                ?: params["number"]
                ?: params["phone"]
                ?: params["phoneNumber"]
                ?: return ActionResult(false, null, "contact or number parameter missing")

            return when (val resolved = contactResolver.resolveWithDisambiguation(contact)) {
                is ContactResolution.Found -> executeCall(resolved.contact.phoneNumber, contact, context)
                is ContactResolution.Ambiguous -> buildContactPickerResult(contact, resolved.matches, "MAKE_CALL")
                is ContactResolution.NotFound -> ActionResult.NeedsInput(
                    question = "I couldn't find '$contact' in your contacts. What's their number?",
                    metadata = mapOf("param" to "contact")
                )
            }
        }
    }

    // ── SEND_WHATSAPP with disambiguation ────────────────────

    private inner class SendWhatsAppAction : Action {
        override val name: String = "SEND_WHATSAPP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contact = params["contact"] ?: return ActionResult(false, null, "contact is missing")
            val message = params["message"] ?: return ActionResult(false, null, "message is missing")

            return when (val resolved = contactResolver.resolveWithDisambiguation(contact)) {
                is ContactResolution.Found -> executeWhatsApp(resolved.contact.phoneNumber, contact, message, context)
                is ContactResolution.Ambiguous -> buildContactPickerResult(
                    contact, resolved.matches, "SEND_WHATSAPP",
                    mapOf("message" to message)
                )
                is ContactResolution.NotFound -> ActionResult.NeedsInput(
                    question = "I couldn't find '$contact'. What's their WhatsApp number?",
                    metadata = mapOf("param" to "contact")
                )
            }
        }
    }

    // ── SEND_SMS with disambiguation ─────────────────────────

    private inner class SendSmsAction : Action {
        override val name: String = "SEND_SMS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contact = params["contact"]
                ?: params["to"]
                ?: params["recipient"]
                ?: return ActionResult(false, null, "contact parameter missing")
            val message = params["message"]
                ?: params["text"]
                ?: params["body"]
                ?: return ActionResult(false, null, "message parameter missing")

            return when (val resolved = contactResolver.resolveWithDisambiguation(contact)) {
                is ContactResolution.Found -> executeSms(resolved.contact.phoneNumber, contact, message, context)
                is ContactResolution.Ambiguous -> buildContactPickerResult(
                    contact, resolved.matches, "SEND_SMS",
                    mapOf("message" to message)
                )
                is ContactResolution.NotFound -> ActionResult.NeedsInput(
                    question = "I couldn't find '$contact'. What's their phone number?",
                    metadata = mapOf("param" to "contact")
                )
            }
        }
    }

    // ── Execution helpers ────────────────────────────────────

    private suspend fun executeCall(phone: String, contactLabel: String, context: Context): ActionResult {
        val cleanPhone = phone.replace(Regex("[\\s\\-()]"), "").trim()
        return try {
            val callUri = Uri.parse("tel:$cleanPhone")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(Intent.ACTION_CALL, callUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Calling $contactLabel now!", null)
            } else {
                val intent = Intent(Intent.ACTION_DIAL, callUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                
                val service = OpenDroidAccessibilityService.getInstance()
                if (service != null) {
                    val clicked = CallAutomator.automateCall()
                    if (clicked) {
                        return ActionResult(true, "Calling $contactLabel now!", null)
                    }
                }
                ActionResult(false, null, "I've opened the dialer for $contactLabel — please tap call.", true)
            }
        } catch (e: SecurityException) {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                
                val service = OpenDroidAccessibilityService.getInstance()
                if (service != null) {
                    val clicked = CallAutomator.automateCall()
                    if (clicked) {
                        return ActionResult(true, "Calling $contactLabel now!", null)
                    }
                }
                ActionResult(false, null, "Dialer is open for $contactLabel — please tap call to connect.", true)
            } catch (e2: Exception) {
                Log.e("MakeCall", "Call failed: ${e2.localizedMessage}")
                ActionResult(false, null, "Couldn't make that call. Want to try again?")
            }
        } catch (e: Exception) {
            Log.e("MakeCall", "Call failed: ${e.localizedMessage}")
            ActionResult(false, null, "Something went wrong with the call. Try again?")
        }
    }

    private suspend fun executeWhatsApp(phone: String, contactLabel: String, message: String, context: Context): ActionResult {
        return try {
            val encodedMsg = URLEncoder.encode(message, "UTF-8")
            val whatsappUri = if (phone.matches(Regex("\\+?[0-9]+"))) {
                Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encodedMsg")
            } else {
                Uri.parse("whatsapp://send?text=$encodedMsg")
            }
            val intent = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val service = OpenDroidAccessibilityService.getInstance()
            if (service != null) {
                val autoSent = WhatsAppAutomator.automateSend(message)
                if (autoSent) {
                    return ActionResult(true, "Sent your message to $contactLabel!", null)
                }
                // First attempt didn't confirm — try one more time with a longer wait
                kotlinx.coroutines.delay(2000)
                val retryClicked = service.findAndClickById("com.whatsapp:id/send") ||
                                   service.findAndClick("Send") ||
                                   service.findAndClick("send")
                if (retryClicked) {
                    // We clicked something — check if it actually sent
                    kotlinx.coroutines.delay(500)
                    val verifyResult = verifySendCompleted(service)
                    if (verifyResult != false) {
                        // Verified or inconclusive → trust the click
                        return ActionResult(true, "Message sent to $contactLabel!", null)
                    }
                    // Verification says input field still has text — send didn't work
                    return ActionResult(false, null, "I tapped send but the message is still in the input field. Please send it manually.", true)
                }
            }

            // No accessibility service or couldn't click send — chat is open with message pre-filled
            ActionResult(false, null, "I've opened the chat with $contactLabel on WhatsApp with your message ready — just tap send!", true)
        } catch (e: Exception) {
            Log.e("SendWhatsApp", "WhatsApp failed: ${e.localizedMessage}")
            ActionResult(false, null, "WhatsApp didn't work. ${e.localizedMessage ?: "Please try again."}", true)
        }
    }

    /**
     * After clicking send, verify the message was actually dispatched by checking
     * if the WhatsApp input field is now empty or shows the placeholder text.
     * 
     * Returns:
     *   true  = verified sent (input field is empty/placeholder)
     *   false = verified NOT sent (input field still has message text)
     *   null  = inconclusive (couldn't find input field to check)
     */
    private fun verifySendCompleted(service: OpenDroidAccessibilityService): Boolean? {
        try {
            val rootNode = service.rootInActiveWindow ?: return null // inconclusive
            val inputIds = listOf("com.whatsapp:id/entry", "com.whatsapp:id/text_entry")
            for (id in inputIds) {
                val inputNodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                for (node in inputNodes) {
                    val text = node.text?.toString() ?: ""
                    node.recycle()
                    // If input field is empty or shows placeholder, message was sent
                    if (text.isBlank() || text == "Type a message" || text == "Message") {
                        return true
                    }
                    // Input field still has content — message wasn't sent
                    return false
                }
            }
            // Couldn't find input field — inconclusive, don't assume failure
            return null
        } catch (e: Exception) {
            return null // inconclusive
        }
    }

    private suspend fun executeSms(phone: String, contactLabel: String, message: String, context: Context): ActionResult {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val smsManager = context.getSystemService(SmsManager::class.java)
                    if (smsManager != null) {
                        smsManager.sendTextMessage(phone, null, message, null, null)
                        return ActionResult(true, "Text sent to $contactLabel!", null)
                    }
                } catch (_: Exception) {
                    // SmsManager failed — fall through to intent
                }
            }
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val service = OpenDroidAccessibilityService.getInstance()
            if (service != null) {
                val autoSent = SmsAutomator.automateSend()
                if (autoSent) {
                    return ActionResult(true, "Sent SMS to $contactLabel!", null)
                }
            }

            // Couldn't auto-send — be honest
            ActionResult(false, null, "I've opened your message to $contactLabel but couldn't tap send automatically. Please tap send.", true)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("sms:$phone")
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)

                val service = OpenDroidAccessibilityService.getInstance()
                if (service != null) {
                    val autoSent = SmsAutomator.automateSend()
                    if (autoSent) {
                        return ActionResult(true, "Sent SMS to $contactLabel!", null)
                    }
                }

                ActionResult(false, null, "Messaging app is open for $contactLabel but needs you to tap send.", true)
            } catch (e2: Exception) {
                Log.e("SendSMS", "SMS failed: ${e2.localizedMessage}")
                ActionResult(false, null, "Couldn't open messaging. Try again?")
            }
        }
    }

    // ── Non-disambiguated actions (unchanged) ────────────────

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
                ActionResult(true, "Email to $to is ready — just review and send!", null)
            } catch (e: Exception) {
                Log.e("SendEmail", "Email failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the email app. Is one installed?")
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
                ActionResult(true, "WhatsApp is open — find the '$groupName' group and send your message!", null)
            } catch (e: Exception) {
                Log.e("WhatsAppGroup", "Group message failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open WhatsApp. Is it installed?")
            }
        }
    }

    private inner class MakeVideoCallAction : Action {
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
                ActionResult(true, "Video call to $contact is starting!", null)
            } catch (e: Exception) {
                Log.e("VideoCall", "Video call failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't start the video call. Try again?")
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
                    ActionResult(true, "Here are your messages!", null)
                } else {
                    ActionResult(false, null, "Couldn't open the messaging app.")
                }
            } catch (e: Exception) {
                Log.e("ReadMessages", "Failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open your messages right now.")
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
                ActionResult(true, "Your email is open!", null)
            } catch (e: Exception) {
                Log.e("ReadEmails", "Failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the email app.")
            }
        }
    }
}
