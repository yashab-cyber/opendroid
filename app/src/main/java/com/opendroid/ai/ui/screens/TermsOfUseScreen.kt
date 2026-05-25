package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
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
fun TermsOfUseScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "TERMS OF USE",
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
                            imageVector = Icons.Default.Description,
                            contentDescription = "Terms",
                            tint = AccentNeonGreen,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Terms of Use",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Effective: May 2026",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            item {
                PolicySection(
                    title = "1. ACCEPTANCE OF TERMS",
                    content = "By downloading, installing, or using OpenDroid (\"the App\"), you agree to be bound by these Terms of Use. " +
                            "If you do not agree to these terms, do not use the App.\n\n" +
                            "OpenDroid is an open-source, autonomous AI assistant for Android. These terms govern your use of the App and all related services."
                )
            }

            item {
                PolicySection(
                    title = "2. PERMITTED USE",
                    content = "You may use OpenDroid for personal, non-commercial purposes including:\n\n" +
                            "• Automating device tasks (messaging, calls, alarms, etc.)\n" +
                            "• Managing smart home devices\n" +
                            "• Searching the web and retrieving information\n" +
                            "• File management and device control\n" +
                            "• Voice-activated commands\n\n" +
                            "You agree NOT to use OpenDroid to:\n\n" +
                            "• Violate any laws or regulations\n" +
                            "• Harass, spam, or harm other individuals\n" +
                            "• Attempt to bypass device security or access unauthorized systems\n" +
                            "• Interfere with other applications in a harmful manner"
                )
            }

            item {
                PolicySection(
                    title = "3. API KEYS & THIRD-PARTY SERVICES",
                    content = "OpenDroid connects to third-party LLM providers (Google Gemini, OpenAI, Anthropic, etc.) using API keys you provide.\n\n" +
                            "• You are responsible for obtaining and managing your own API keys.\n" +
                            "• API key usage is subject to the respective provider's terms of service.\n" +
                            "• OpenDroid is not responsible for charges incurred through third-party API usage.\n" +
                            "• Your API keys are stored locally on your device using AES-256 encryption and are never transmitted to OpenDroid servers."
                )
            }

            item {
                PolicySection(
                    title = "4. ACCESSIBILITY SERVICE",
                    content = "OpenDroid uses Android's Accessibility Service to perform on-screen automations on your behalf. " +
                            "By enabling this service, you acknowledge that:\n\n" +
                            "• The service can interact with other apps on your device\n" +
                            "• It only acts when you explicitly give a command\n" +
                            "• You can disable it at any time from Android Settings\n" +
                            "• OpenDroid does not use this service to collect or transmit data"
                )
            }

            item {
                PolicySection(
                    title = "5. DISCLAIMER OF WARRANTIES",
                    content = "OpenDroid is provided \"AS IS\" without warranties of any kind, either express or implied.\n\n" +
                            "• We do not guarantee uninterrupted or error-free operation.\n" +
                            "• AI-generated responses may be inaccurate or incomplete.\n" +
                            "• Automated actions may not execute as intended in all scenarios.\n" +
                            "• You assume all risks associated with using the App."
                )
            }

            item {
                PolicySection(
                    title = "6. LIMITATION OF LIABILITY",
                    content = "To the maximum extent permitted by law, the OpenDroid developers shall not be liable for any " +
                            "direct, indirect, incidental, special, or consequential damages arising from:\n\n" +
                            "• Use or inability to use the App\n" +
                            "• Unauthorized access to your data\n" +
                            "• Actions performed by the AI assistant\n" +
                            "• Third-party service failures or charges"
                )
            }

            item {
                PolicySection(
                    title = "7. OPEN SOURCE",
                    content = "OpenDroid is open-source software. You are free to view, modify, and distribute the source code " +
                            "in accordance with the project's license terms. Contributions to the project are welcome and governed by the project's contribution guidelines."
                )
            }

            item {
                PolicySection(
                    title = "8. CHANGES TO TERMS",
                    content = "We may update these terms from time to time. Changes will be reflected in the App with an updated effective date. " +
                            "Continued use of OpenDroid after changes constitutes acceptance of the updated terms."
                )
            }

            item {
                PolicySection(
                    title = "9. CONTACT",
                    content = "For questions about these Terms of Use, please open an issue on our GitHub repository or contact the development team."
                )
            }
        }
    }
}
