package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {

    @Test
    fun `estimateTokens is zero for empty text and rounds partial tokens up`() {
        assertEquals(0, PromptBudget.estimateTokens(""))
        assertEquals(1, PromptBudget.estimateTokens("hi"))
        assertEquals(1, PromptBudget.estimateTokens("."))
    }

    // ── Golden counts: estimateTokens must be an upper bound ─────────────────────
    //
    // Real token counts from Qwen2.x's tokenizer, the 1280-token on-device model:
    // tiktoken 0.14.0 loaded with qwen.tiktoken (151,643 BPE ranks), Qwen2's
    // pre-tokenizer regex and NFC normalisation, run offline. An estimate below the
    // real count lets a prompt pass the budget guards and still overflow the native
    // KV cache, which aborts the process.

    private val hindi = listOf(
        "नमस्ते, क्या आप मुझे कल सुबह सात बजे का अलार्म लगाने में मदद कर सकते हैं? मुझे जल्दी उठना है।" to 82,
        "कृपया माँ को व्हाट्सएप पर संदेश भेजें कि मैं आज देर से घर पहुँचूँगा।" to 65,
    )
    private val chinese = listOf(
        "你好，请帮我写一封邮件给我的老板，告诉他我明天因为身体不舒服需要请假一天，并且会在后天回来上班。谢谢！" to 29,
        "明天早上七点提醒我给妈妈打电话，然后打开无线网络。" to 15,
    )
    private val digits = listOf(
        "My order ids are 4839201938, 5582910374, 9918273645 and the total was 12,849.75 INR" to 55,
        "Call +91 98765 43210 or (415) 555-0132 before 07:30 on 31/10/2026." to 51,
    )
    private val emoji = listOf(
        "lol 😂😂😂 that's so funny 🤣🤣 ok see you tomorrow 👋❤️🔥" to 22,
        "Happy birthday!! 🎉🎂🥳 love you 💖💖 👨‍👩‍👧 family pic 📸🇮🇳" to 28,
    )
    private val russian = listOf(
        "Привет! Напомни мне, пожалуйста, завтра позвонить маме и купить продукты по дороге домой." to 33,
        "Включи Wi-Fi и отправь маме сообщение в WhatsApp, что я опаздываю на двадцать минут." to 28,
    )
    private val json = listOf(
        """{"steps":[{"stepId":"s1","order":1,"action":"TOGGLE_WIFI","params":{"state":"on"},"dependsOn":[]}]}""" to 30,
        """{"toolCall": {"name": "send_message", "arguments": {"contact": "Mom", "app": "whatsapp", "text": "Running late"}}}""" to 35,
    )
    private val english = listOf(
        "Hey, can you help me figure out a good plan for the weekend? I want to go hiking on Saturday and maybe catch a movie on Sunday evening with friends." to 33,
        "Please turn on Wi-Fi, then open WhatsApp and tell my mom that I am running about twenty minutes late for dinner tonight." to 25,
    )

    @Test
    fun `estimateTokens does not undercount Hindi`() = assertNoUndercount(hindi)

    @Test
    fun `estimateTokens does not undercount Chinese`() = assertNoUndercount(chinese)

    @Test
    fun `estimateTokens does not undercount digits and phone numbers`() = assertNoUndercount(digits)

    @Test
    fun `estimateTokens does not undercount emoji`() = assertNoUndercount(emoji)

    @Test
    fun `estimateTokens does not undercount Russian`() = assertNoUndercount(russian)

    @Test
    fun `estimateTokens does not undercount JSON`() = assertNoUndercount(json)

    @Test
    fun `estimateTokens does not undercount English prose`() = assertNoUndercount(english)

    @Test
    fun `estimateTokens keeps English prose within one and a half times its real count`() {
        // Over-counting is safe but trims history and output, so English must not pay much for it.
        english.forEach { (text, qwen) ->
            val estimate = PromptBudget.estimateTokens(text)
            assertTrue("estimate $estimate > 1.5 x real $qwen for \"$text\"", estimate * 2 <= qwen * 3)
        }
    }

    private fun assertNoUndercount(fixtures: List<Pair<String, Int>>) {
        val undercounts = fixtures.mapNotNull { (text, qwen) ->
            val estimate = PromptBudget.estimateTokens(text)
            if (estimate < qwen) "estimate $estimate < real $qwen for \"$text\"" else null
        }
        assertTrue(undercounts.joinToString("\n"), undercounts.isEmpty())
    }

    // ── outputBudget ─────────────────────────────────────────────────────────────

    // The runtime wraps the prompt in the model's chat template. outputBudget must reserve
    // the same 20 tokens for it that buildPrompt reserves when fitting the messages.
    private val templateOverhead = 20

    @Test
    fun `outputBudget returns requested tokens when prompt leaves room`() {
        // A short prompt in a 1280-token context leaves plenty for 500 output tokens
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(100),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertEquals(500, budget)
    }

    @Test
    fun `outputBudget clamps requested tokens to remaining context`() {
        // The context leaves exactly 280 tokens once the prompt and chat template are loaded
        val prompt = "x".repeat(3000)
        val budget = PromptBudget.outputBudget(
            prompt = prompt,
            contextWindow = PromptBudget.estimateTokens(prompt) + templateOverhead + 280,
            requestedOutputTokens = 1500
        )
        assertEquals(280, budget)
    }

    @Test
    fun `outputBudget returns null when prompt fills the context window`() {
        // 6000 chars is well over 1280 tokens: nothing left for output
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(6000),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertNull(budget)
    }

    @Test
    fun `outputBudget returns null when remaining space is below the minimum useful output`() {
        // Only 30 tokens left after prompt and template, below MIN_OUTPUT_TOKENS
        val prompt = "x".repeat(5000)
        val budget = PromptBudget.outputBudget(
            prompt = prompt,
            contextWindow = PromptBudget.estimateTokens(prompt) + templateOverhead + 30,
            requestedOutputTokens = 500
        )
        assertNull(budget)
    }

    @Test
    fun `outputBudget reserves chat template tokens on top of the prompt`() {
        // Without the template the prompt would leave exactly MIN_OUTPUT_TOKENS; with it, too few
        val prompt = "x".repeat(1000)
        val budget = PromptBudget.outputBudget(
            prompt = prompt,
            contextWindow = PromptBudget.estimateTokens(prompt) + PromptBudget.MIN_OUTPUT_TOKENS,
            requestedOutputTokens = 500
        )
        assertNull(budget)
    }

    @Test
    fun `registry qwen spec carries its ekv1280 context window`() {
        val qwen = OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")!!
        assertEquals(1280, qwen.contextWindow)
    }

    // ── fitMessagesToBudget ──────────────────────────────────────────────────────

    @Test
    fun `fitMessagesToBudget retains latest message and prunes oldest when budget exceeded`() {
        val msg1 = com.opendroid.ai.data.models.ChatMessage(
            id = "1",
            text = "Oldest message ".repeat(50),
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )
        val msg2 = com.opendroid.ai.data.models.ChatMessage(
            id = "2",
            text = "Middle message ".repeat(50),
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.AGENT
        )
        val msg3 = com.opendroid.ai.data.models.ChatMessage(
            id = "3",
            text = "Latest user query",
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )

        // Size the context so msg3 + msg2 (with its ~4-token prefix) fit with 10 tokens to
        // spare after overhead 100 and reserved output 128: msg1 cannot fit -> pruned!
        val messageTokens = PromptBudget.estimateTokens(msg3.text) + PromptBudget.estimateTokens(msg2.text) + 4
        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(msg1, msg2, msg3),
            contextWindow = 128 + 100 + messageTokens + 10,
            reservedOutputTokens = 128,
            overheadTokens = 100
        )

        assertEquals(2, fitted.size)
        assertEquals("2", fitted[0].id)
        assertEquals("3", fitted[1].id)
    }

    @Test
    fun `fitMessagesToBudget truncates latest message if it alone exceeds budget`() {
        val massiveLatest = com.opendroid.ai.data.models.ChatMessage(
            id = "1",
            text = "word ".repeat(1000), // ~5000 chars, far over the budget
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )

        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(massiveLatest),
            contextWindow = 500,
            reservedOutputTokens = 128,
            overheadTokens = 100
        )

        assertEquals(1, fitted.size)
        // Available budget is (500 - 128 - 100) = 272 tokens; the newest text is kept
        assertTrue("Expected message to be truncated to fit budget", PromptBudget.estimateTokens(fitted[0].text) <= 272)
        assertTrue(fitted[0].text.isNotEmpty() && massiveLatest.text.endsWith(fitted[0].text))
    }

    @Test
    fun `fitMessagesToBudget truncates a long non-Latin latest message to the token budget`() {
        val massiveLatest = com.opendroid.ai.data.models.ChatMessage(
            id = "1",
            text = hindi.first().first.repeat(40),
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )

        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(massiveLatest),
            contextWindow = 500,
            reservedOutputTokens = 128,
            overheadTokens = 100
        )

        assertEquals(1, fitted.size)
        assertTrue(
            "Expected the Hindi message to be truncated to the 272-token budget",
            PromptBudget.estimateTokens(fitted[0].text) <= 272
        )
        assertTrue(fitted[0].text.isNotEmpty() && massiveLatest.text.endsWith(fitted[0].text))
    }
}
