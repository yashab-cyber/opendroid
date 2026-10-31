package com.opendroid.ai.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.R
import com.opendroid.ai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ABOUT",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
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
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Identity Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            Brush.linearGradient(listOf(TextPrimary.copy(alpha = 0.3f), AccentCyan.copy(alpha = 0.4f))),
                            RoundedCornerShape(16.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(TextPrimary.copy(alpha = 0.1f), AccentCyan.copy(alpha = 0.15f))
                                    )
                                )
                                .border(1.5.dp, TextPrimary.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // bot.png is a 512x341 canvas whose glyph only covers the
                            // middle ~160x172, so it draws tiny at the badge's own size.
                            // requiredSize scales past the 80.dp parent constraint; the
                            // transparent padding overflows into the Box's circle clip.
                            Image(
                                painter = painterResource(id = R.drawable.bot),
                                contentDescription = "OpenDroid app icon",
                                modifier = Modifier.requiredSize(150.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "OpenDroid",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Autonomous AI Agent for Android",
                            fontSize = 14.sp,
                            color = AccentCyan,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Description Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "WHAT IS OPENDROID?",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "OpenDroid is an advanced autonomous AI assistant that runs directly on your Android device. " +
                                    "It can understand natural language commands, create multi-step execution plans, and automate " +
                                    "virtually any task on your phone — from sending messages and making calls to controlling " +
                                    "system settings and managing files.\n\n" +
                                    "Powered by your choice of LLM provider (Gemini, OpenAI, Claude, Groq, local Ollama, and more), " +
                                    "OpenDroid combines intelligent planning with real device automation through Android's Accessibility framework.",
                            fontSize = 13.sp,
                            color = TextPrimary,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            // Features Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "KEY CAPABILITIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        FeatureItem(Icons.Default.Chat, "Natural Language Control", "Speak or type commands in plain English")
                        FeatureItem(Icons.Default.List, "Multi-Step Planning", "Automatically breaks complex tasks into executable steps")
                        FeatureItem(Icons.Default.Star, "Persistent Memory", "Remembers your preferences across sessions")
                        FeatureItem(Icons.Default.Build, "Custom Macros", "Record and replay complex workflows")
                        FeatureItem(Icons.Default.Accessibility, "App Automation", "Controls other apps via Accessibility Service")
                        FeatureItem(Icons.Default.Settings, "System Control", "WiFi, Bluetooth, flashlight, volume, and more")
                        FeatureItem(Icons.Default.Call, "Communication", "WhatsApp, calls, SMS, email — hands-free")
                        FeatureItem(Icons.Default.Lock, "Privacy-First", "All data stays on your device")
                    }
                }
            }

            // Tech Stack Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "TECHNOLOGY STACK",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        TechItem("Language", "Kotlin")
                        TechItem("UI Framework", "Jetpack Compose + Material 3")
                        TechItem("Architecture", "MVVM + Hilt DI")
                        TechItem("Database", "Room (SQLite)")
                        TechItem("AI Integration", "Multi-provider LLM support")
                        TechItem("Automation", "Android Accessibility Service")
                        TechItem("Async", "Kotlin Coroutines + Flow")
                        TechItem("Serialization", "kotlinx.serialization")
                    }
                }
            }

            // Supported Providers Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "SUPPORTED LLM PROVIDERS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val providers = listOf(
                            "Google Gemini", "OpenAI (GPT-4o, etc.)", "Anthropic Claude",
                            "Groq", "Mistral AI", "OpenRouter", "Together AI",
                            "Cohere", "DeepSeek", "Copilot API", "Opencode-Go", "Ollama (Local)"
                        )
                        providers.forEach { provider ->
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(AccentCyan)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = provider,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Open Source Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AccentPurple.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "OPEN SOURCE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentPurple
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "OpenDroid is open source software. Contributions, bug reports, and feature requests are welcome.",
                            fontSize = 13.sp,
                            color = TextPrimary,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "github.com/JMAN730/opendroid",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentCyan
                        )
                    }
                }
            }

            // Footer
            item {
                Text(
                    text = "Made with ❤ for the Android community",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun FeatureItem(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = AccentCyan,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun TechItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}
