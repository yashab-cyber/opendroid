package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
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
fun PrivacyPolicyScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PRIVACY POLICY",
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
                        .border(1.dp, AccentNeonGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Privacy",
                            tint = AccentNeonGreen,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Your Privacy Matters",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Last updated: May 2026",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Section: Overview
            item {
                PolicySection(
                    title = "1. OVERVIEW",
                    content = "OpenDroid is an autonomous AI assistant that runs entirely on your Android device. " +
                            "We are committed to protecting your privacy and ensuring transparency about how your data is handled. " +
                            "This policy explains what data OpenDroid collects, how it is used, and your rights regarding that data."
                )
            }

            // Section: Data Collection
            item {
                PolicySection(
                    title = "2. DATA COLLECTION",
                    content = "OpenDroid processes the following data locally on your device:\n\n" +
                            "• Voice commands and text queries you provide\n" +
                            "• Device state information (battery level, WiFi status, connectivity)\n" +
                            "• Contact names for resolving communication actions\n" +
                            "• Conversation history for context-aware responses\n" +
                            "• User preferences and semantic memory facts you share\n\n" +
                            "All data is stored in a local Room database on your device. No data is collected by the OpenDroid developers."
                )
            }

            // Section: LLM Providers
            item {
                PolicySection(
                    title = "3. THIRD-PARTY LLM PROVIDERS",
                    content = "OpenDroid sends your queries to the LLM provider you configure (e.g., Google Gemini, OpenAI, Anthropic, Groq, etc.) " +
                            "to generate responses and action plans. Each provider has its own privacy policy governing how they handle your data.\n\n" +
                            "• Your API keys are stored locally on your device and are never transmitted to OpenDroid servers.\n" +
                            "• Query data sent to LLM providers is subject to their respective privacy policies.\n" +
                            "• You can switch providers or use local models (Ollama) at any time to keep data fully on-device."
                )
            }

            // Section: Permissions
            item {
                PolicySection(
                    title = "4. DEVICE PERMISSIONS",
                    content = "OpenDroid requests the following permissions to function:\n\n" +
                            "• Microphone — For voice command input\n" +
                            "• Accessibility Service — For automating app interactions (WhatsApp, etc.)\n" +
                            "• Contacts — For resolving contact names to phone numbers\n" +
                            "• Phone — For placing calls\n" +
                            "• SMS — For sending text messages\n" +
                            "• Camera — For taking photos/flashlight control\n" +
                            "• Storage — For file management actions\n" +
                            "• Location — For weather and directions features\n\n" +
                            "All permissions are optional. Features requiring ungranted permissions will gracefully degrade or prompt you."
                )
            }

            // Section: Data Storage
            item {
                PolicySection(
                    title = "5. DATA STORAGE & RETENTION",
                    content = "• All conversation history, memory facts, and task logs are stored in a local SQLite database on your device.\n" +
                            "• Memory entries support time-to-live (TTL) and are automatically cleaned on expiration.\n" +
                            "• You can clear any memory type (Working, Episodic, Semantic, Procedural) from the Memory screen.\n" +
                            "• Uninstalling the app removes all stored data permanently."
                )
            }

            // Section: Data Sharing
            item {
                PolicySection(
                    title = "6. DATA SHARING",
                    content = "OpenDroid does NOT:\n\n" +
                            "• Sell, rent, or share your personal data with third parties\n" +
                            "• Collect analytics, telemetry, or usage statistics\n" +
                            "• Transmit data to any server owned by the OpenDroid team\n" +
                            "• Display advertisements or use ad-tracking technologies\n\n" +
                            "The only external data transmission occurs when your queries are sent to the LLM provider you have configured."
                )
            }

            // Section: Security
            item {
                PolicySection(
                    title = "7. SECURITY",
                    content = "• API keys are stored in Android SharedPreferences on your device.\n" +
                            "• All LLM API communication uses HTTPS encryption.\n" +
                            "• The accessibility service only activates when explicitly enabled by you.\n" +
                            "• Destructive actions (device restart, file deletion) require user confirmation."
                )
            }

            // Section: Children
            item {
                PolicySection(
                    title = "8. CHILDREN'S PRIVACY",
                    content = "OpenDroid is not intended for use by children under 13 years of age. " +
                            "We do not knowingly collect information from children."
                )
            }

            // Section: Changes
            item {
                PolicySection(
                    title = "9. CHANGES TO THIS POLICY",
                    content = "We may update this privacy policy from time to time. Any changes will be reflected in the app with an updated \"Last updated\" date. " +
                            "Continued use of OpenDroid after changes constitutes acceptance of the updated policy."
                )
            }

            // Section: Contact
            item {
                PolicySection(
                    title = "10. CONTACT",
                    content = "If you have questions about this privacy policy or OpenDroid's data practices, " +
                            "please open an issue on our GitHub repository or contact the development team."
                )
            }
        }
    }
}

@Composable
private fun PolicySection(
    title: String,
    content: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = content,
                fontSize = 13.sp,
                color = TextPrimary,
                lineHeight = 20.sp
            )
        }
    }
}
