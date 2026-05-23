package com.opendroid.ai.core.agent

import android.util.Log
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.data.models.ChatMessage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vision engine that captures screenshots and analyzes them using a vision-capable LLM.
 * Uses the existing AccessibilityService's takeScreenshotAndEncode() for capture,
 * with a fallback to getScreenText() for text-only analysis.
 */
@Singleton
class VisionEngine @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory
) {
    companion object {
        private const val TAG = "VisionEngine"
    }

    /**
     * Capture the current screen as a base64-encoded JPEG string.
     * Uses the AccessibilityService's existing screenshot capability.
     */
    suspend fun captureScreenBase64(): String? {
        val service = OpenDroidAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "Accessibility service instance is null")
            return null
        }

        return try {
            service.takeScreenshotAndEncode()
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed: ${e.message}")
            null
        }
    }

    /**
     * Extract visible text from the screen via accessibility nodes.
     * This works even when screenshot capture fails.
     */
    fun getScreenText(): String? {
        val service = OpenDroidAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "Accessibility service not available for text extraction")
            return null
        }
        return try {
            val text = service.getScreenText()
            if (text.isNotBlank()) text else null
        } catch (e: Exception) {
            Log.e(TAG, "Screen text extraction failed: ${e.message}")
            null
        }
    }

    /**
     * Capture the screen and analyze it with a vision-capable LLM.
     * Falls back to text-only analysis if screenshot capture fails.
     */
    suspend fun analyzeCurrentScreen(
        userQuestion: String = "What do you see on this screen?"
    ): String {
        // Try image-based analysis first
        val base64Image = captureScreenBase64()

        if (base64Image != null) {
            return analyzeWithImage(base64Image, userQuestion)
        }

        // Fallback: text-based analysis using accessibility tree
        val screenText = getScreenText()
        if (screenText != null) {
            return analyzeWithText(screenText, userQuestion)
        }

        return "Could not capture or read the screen. The Accessibility Service may need to be re-enabled in Settings > Accessibility > OpenDroid."
    }

    private suspend fun analyzeWithImage(
        base64Image: String,
        userQuestion: String
    ): String {
        val visionPrompt = """
            Analyze this Android screenshot.
            User question: $userQuestion
            
            Describe:
            1. What app is open
            2. What content is visible
            3. Answer the user's specific question
            4. Any important information on screen
            
            Be concise and helpful.
        """.trimIndent()

        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val imageMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = visionPrompt,
                sender = ChatMessage.Sender.USER,
                imageBase64 = base64Image
            )
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are a vision AI that analyzes Android screenshots. Be concise and accurate.",
                    messages = listOf(imageMessage),
                    temperature = 0.3f,
                    maxTokens = 500,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            response.content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Vision analysis failed: ${e.message}")
            "I captured the screenshot but couldn't analyze it: ${e.message}"
        }
    }

    private suspend fun analyzeWithText(
        screenText: String,
        userQuestion: String
    ): String {
        val textPrompt = """
            I extracted the following text from the user's Android screen:
            
            ---
            $screenText
            ---
            
            User question: $userQuestion
            
            Based on the visible text, describe what's on screen and answer the user's question.
            Be concise and helpful.
        """.trimIndent()

        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = textPrompt,
                sender = ChatMessage.Sender.USER
            )
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are an AI that analyzes Android screen content from extracted text. Be concise and accurate.",
                    messages = listOf(message),
                    temperature = 0.3f,
                    maxTokens = 500,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            response.content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Text analysis failed: ${e.message}")
            "I read the screen text but couldn't analyze it: ${e.message}"
        }
    }
}
