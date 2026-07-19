package com.opendroid.ai.data.models

data class AutoReplyConfig(
    // Auto-reply is opt-in: replying to untrusted incoming messages with LLM
    // output is a prompt-injection / data-exfiltration surface, so every
    // channel stays off until the user explicitly enables it.
    val globalEnabled: Boolean = false,
    val whatsappEnabled: Boolean = false,
    val smsEnabled: Boolean = false,
    val emailEnabled: Boolean = false,
    val replyDelayMinutes: Int = 15,
    val blacklistedContacts: Set<String> = emptySet(),
    val whitelistedContacts: Set<String> = emptySet(),
    val customPrompt: String? = null,
    val maxRepliesPerContactPerHour: Int = 3
)
