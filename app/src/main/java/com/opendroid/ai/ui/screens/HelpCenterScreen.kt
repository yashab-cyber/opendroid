package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpCenterScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "HELP CENTER",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentNeonGreen,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = AccentNeonGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Header card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Help",
                            tint = AccentCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "How can we help?",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Quick answers to common questions",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            item {
                PolicySection(
                    title = "GETTING STARTED",
                    content = "1. Launch OpenDroid and complete the onboarding setup\n" +
                            "2. Grant the requested permissions (microphone, accessibility, etc.)\n" +
                            "3. Go to Settings and enter your LLM provider API key\n" +
                            "4. Start talking or typing commands!\n\n" +
                            "Tip: Google Gemini is the default provider. Get a free API key at ai.google.dev"
                )
            }

            item {
                PolicySection(
                    title = "VOICE COMMANDS",
                    content = "OpenDroid listens for the wake word \"Hey OpenDroid\" when the app is running.\n\n" +
                            "Examples of what you can say:\n\n" +
                            "• \"Send a WhatsApp message to Mom saying I'll be late\"\n" +
                            "• \"Set an alarm for 7 AM tomorrow\"\n" +
                            "• \"Turn on the flashlight\"\n" +
                            "• \"What's the weather like today?\"\n" +
                            "• \"Play some music on Spotify\"\n" +
                            "• \"Call John\"\n" +
                            "• \"Take a photo\""
                )
            }

            item {
                PolicySection(
                    title = "SETTING UP API KEYS",
                    content = "OpenDroid needs an LLM API key to generate responses:\n\n" +
                            "1. Go to Settings → Provider API Keys\n" +
                            "2. Enter your API key for the provider you want to use\n" +
                            "3. Select that provider from the \"Active Brain Provider\" dropdown\n\n" +
                            "Supported providers:\n" +
                            "• Google Gemini (recommended for beginners)\n" +
                            "• OpenAI (GPT-4, GPT-3.5)\n" +
                            "• Anthropic Claude\n" +
                            "• Groq (fast inference)\n" +
                            "• Mistral AI, OpenRouter, Together AI, Cohere, DeepSeek\n" +
                            "• Ollama (fully local, no API key needed)"
                )
            }

            item {
                PolicySection(
                    title = "ACCESSIBILITY SERVICE",
                    content = "The Accessibility Service lets OpenDroid tap buttons and type in other apps (e.g., sending WhatsApp messages automatically).\n\n" +
                            "To enable it:\n" +
                            "1. Go to Android Settings → Accessibility\n" +
                            "2. Find \"OpenDroid\" in Installed Services\n" +
                            "3. Toggle it ON\n\n" +
                            "Note: This is optional. Without it, OpenDroid will still open apps but may need you to tap the final \"Send\" button."
                )
            }

            item {
                PolicySection(
                    title = "MACROS & AUTOMATION",
                    content = "You can create macros to run multiple actions in sequence:\n\n" +
                            "• Go to the Macros tab\n" +
                            "• Create a new macro with a name and list of steps\n" +
                            "• Schedule macros with cron expressions for timed automation\n\n" +
                            "Example: Create a \"Good Morning\" macro that turns on lights, reads the weather, and plays your favorite playlist."
                )
            }

            item {
                PolicySection(
                    title = "MEMORY SYSTEM",
                    content = "OpenDroid has 4 types of memory:\n\n" +
                            "• Working Memory — Current session context (auto-cleared)\n" +
                            "• Episodic Memory — Conversation history\n" +
                            "• Semantic Memory — Facts about you (name, preferences)\n" +
                            "• Procedural Memory — Learned task patterns\n\n" +
                            "You can view and clear any memory type from the Memory tab."
                )
            }

            item {
                PolicySection(
                    title = "TROUBLESHOOTING",
                    content = "\"OpenDroid isn't responding\"\n" +
                            "→ Check that your API key is valid and the provider is reachable.\n\n" +
                            "\"Voice commands don't work\"\n" +
                            "→ Make sure microphone permission is granted and the service is running.\n\n" +
                            "\"WhatsApp messages aren't sending automatically\"\n" +
                            "→ Enable the Accessibility Service in Android Settings.\n\n" +
                            "\"App crashes on startup\"\n" +
                            "→ Clear app data and re-enter your settings. Your API keys are encrypted and will need to be re-entered."
                )
            }

            item {
                PolicySection(
                    title = "CONTACT & SUPPORT",
                    content = "• GitHub: Report bugs and request features on our GitHub Issues page\n" +
                            "• Discord: Join our community for live help and discussion\n" +
                            "• Email: Reach the development team for security or privacy concerns\n\n" +
                            "OpenDroid is open-source and community-driven. We welcome contributions!"
                )
            }
        }
    }
}
