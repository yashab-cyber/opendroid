package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.effectiveGrantedActions
import com.opendroid.ai.data.models.resolvedAutoMode
import com.opendroid.ai.core.llm.OnDeviceModelRegistry
import com.opendroid.ai.core.llm.OnDeviceBackend
import com.opendroid.ai.core.llm.ConnectionTestState
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.data.repository.ProviderCredentialPersistenceState
import com.google.mlkit.genai.prompt.*
import com.google.mlkit.genai.common.FeatureStatus
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.SettingsViewModel
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToBenchmark: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit = {},
    onNavigateToTermsOfUse: () -> Unit = {},
    onNavigateToHelpCenter: () -> Unit = {},
    onNavigateToLicense: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToAutoReply: () -> Unit = {},
    onNavigateToNotificationHistory: () -> Unit = {},
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    onNavigateToRoutines: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val config by viewModel.llmConfig.collectAsState()
    val connectionResults by viewModel.connectionResults.collectAsState()
    val dbModels by viewModel.allModels.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val hfToken by viewModel.huggingFaceToken.collectAsState()
    val providerCredentialRecoveryState by viewModel.providerCredentialRecoveryState.collectAsState()
    val providerCredentialPersistenceState by viewModel.providerCredentialPersistenceState.collectAsState()

    val providers = listOf(
        "Google Gemini",
        "OpenAI",
        "Anthropic Claude",
        "Groq",
        "Mistral AI",
        "OpenRouter",
        "Requesty",
        "Together AI",
        "Cohere",
        "DeepSeek",
        "Copilot API",
        "Custom OpenAI Compatible",
        "Ollama",
        "On-Device AI"
    )

    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var keysSectionExpanded by remember { mutableStateOf(false) }
    var voiceSectionExpanded by remember { mutableStateOf(false) }
    var planningSectionExpanded by remember { mutableStateOf(false) }

    var showAuthRequiredDialog by remember { mutableStateOf<String?>(null) }
    var licenseUrlForDialog by remember { mutableStateOf("") }
    var showCellularWarningDialog by remember { mutableStateOf<String?>(null) }
    var pendingCellularResumeModelId by remember { mutableStateOf<String?>(null) }
    var activeImportModelId by remember { mutableStateOf<String?>(null) }
    var importAsCustomModel by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            when {
                importAsCustomModel -> viewModel.importCustomLocalModel(uri)
                activeImportModelId != null -> viewModel.importLocalModel(activeImportModelId!!, uri)
            }
        }
        activeImportModelId = null
        importAsCustomModel = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AGENT PREFERENCES",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (providerCredentialRecoveryState == ProviderCredentialRecoveryState.CredentialsMustBeReentered) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFFF9800), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "CREDENTIALS MUST BE RE-ENTERED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF9800)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Saved provider credentials cannot be read on this device. " +
                                    "Clear unavailable records, then enter your API keys again.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = viewModel::resetProviderCredentialsForReentry,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                            ) {
                                Text("Clear unavailable credentials", color = DarkBackground)
                            }
                        }
                    }
                }
            }

            if (providerCredentialPersistenceState ==
                ProviderCredentialPersistenceState.StorageUnavailable
            ) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFFF9800), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "CREDENTIALS WERE NOT SAVED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF9800)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Secure credential storage is unavailable. Existing settings " +
                                    "were preserved; check device storage and try again.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Active LLM Provider Selection Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ACTIVE BRAIN PROVIDER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Dropdown menu trigger
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBackground)
                                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                                .clickable { providerDropdownExpanded = true }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (config.activeProvider == "On-Device AI" || config.activeProvider == "Gemma 4 (On-device)") "On-Device AI" else config.activeProvider,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Dropdown",
                                    tint = TextPrimary
                                )
                            }

                            DropdownMenu(
                                expanded = providerDropdownExpanded,
                                onDismissRequest = { providerDropdownExpanded = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .background(CardBackground)
                                    .border(1.dp, BorderColor)
                            ) {
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = "OFFLINE AI", 
                                            color = AccentCyan, 
                                            fontWeight = FontWeight.Bold, 
                                            fontSize = 11.sp, 
                                            fontFamily = FontFamily.Monospace
                                        ) 
                                    },
                                    enabled = false,
                                    onClick = {}
                                )
                                DropdownMenuItem(
                                    text = { Text("On-Device AI", color = TextPrimary, modifier = Modifier.padding(start = 8.dp)) },
                                    onClick = {
                                        viewModel.updateActiveProvider("On-Device AI")
                                        providerDropdownExpanded = false
                                    }
                                )
                                
                                Divider(color = BorderColor, thickness = 1.dp)

                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = "CLOUD AI", 
                                            color = AccentCyan, 
                                            fontWeight = FontWeight.Bold, 
                                            fontSize = 11.sp, 
                                            fontFamily = FontFamily.Monospace
                                        ) 
                                    },
                                    enabled = false,
                                    onClick = {}
                                )
                                val cloudProvidersList = providers.filter { it != "On-Device AI" }
                                cloudProvidersList.forEach { name ->
                                    val displayName = when (name) {
                                        "Google Gemini" -> "Gemini"
                                        "Anthropic Claude" -> "Claude"
                                        else -> name
                                    }
                                    DropdownMenuItem(
                                        text = { Text(displayName, color = TextPrimary, modifier = Modifier.padding(start = 8.dp)) },
                                        onClick = {
                                            viewModel.updateActiveProvider(name)
                                            providerDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        val modelsLoading by viewModel.modelsLoading.collectAsState()
                        val modelFetchNotice by viewModel.modelFetchNotice.collectAsState()
                        val fetchedModels = config.modelCache[config.activeProvider] ?: emptyList()
                        var modelDropdownExpanded by remember { mutableStateOf(false) }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ACTIVE MODEL",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                            if (modelsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = TextPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(
                                    onClick = { viewModel.refreshModels(force = true) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh models",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = config.activeModel,
                                onValueChange = { viewModel.updateActiveModel(it) },
                                label = { Text("Active LLM Model", fontSize = 12.sp) },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { modelDropdownExpanded = !modelDropdownExpanded }) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Show models dropdown",
                                            tint = TextPrimary
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                        if (fetchedModels.isNotEmpty()) {
                                DropdownMenu(
                                    expanded = modelDropdownExpanded,
                                    onDismissRequest = { modelDropdownExpanded = false },
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .background(CardBackground)
                                        .border(1.dp, BorderColor)
                                ) {
                                    fetchedModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = model.displayName,
                                                        color = TextPrimary,
                                                        fontSize = 14.sp
                                                    )
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (model.isRecommended) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(
                                                                        AccentCyan.copy(alpha = 0.15f),
                                                                        RoundedCornerShape(4.dp)
                                                                    )
                                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = "REC",
                                                                    color = AccentCyan,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                        if (model.isFree) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(
                                                                        AccentCyan.copy(alpha = 0.15f),
                                                                        RoundedCornerShape(4.dp)
                                                                    )
                                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = "FREE",
                                                                    color = AccentCyan,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                        if (model.isPremium) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(
                                                                        Color(0xFFFFD700).copy(alpha = 0.15f),
                                                                        RoundedCornerShape(4.dp)
                                                                    )
                                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = "PRO",
                                                                    color = Color(0xFFFFD700),
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            onClick = {
                                                viewModel.updateActiveModel(model.id)
                                                modelDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "EXPLICIT PLANNING FALLBACKS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Text(
                            text = "Only selected providers may receive a retry after an unusable low-impact local plan. High-impact plans never switch automatically.",
                            fontSize = 10.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                        providers
                            .filter { it != config.activeProvider && it != "On-Device AI" }
                            .forEach { fallbackProvider ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = config.fallbackProviders.contains(fallbackProvider),
                                        onCheckedChange = { enabled ->
                                            viewModel.updateFallbackProvider(fallbackProvider, enabled)
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AccentCyan,
                                            uncheckedColor = BorderColor,
                                            checkmarkColor = DarkBackground
                                        )
                                    )
                                    Text(
                                        text = fallbackProvider,
                                        color = TextPrimary,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                        // Model names are never bundled with the app, so when the
                        // live list is unavailable the reason is shown rather than
                        // a list that quietly went out of date.
                        modelFetchNotice?.let { notice ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = notice,
                                fontSize = 11.sp,
                                color = AccentRed,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Benchmark latency report card link
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable { onNavigateToBenchmark() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Benchmark",
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LLM RESPONSIVENESS REPORT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "View live charts comparing speeds & latency.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Ollama Endpoint Config Card (Visible only when Ollama is selected)
            if (config.activeProvider == "Ollama") {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "OLLAMA LOCAL ENDPOINT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = config.ollamaUrl,
                                onValueChange = { viewModel.updateOllamaUrl(it) },
                                label = { Text("Ollama Server URL", fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Use local LAN IP (e.g. http://192.168.1.50:11434) if testing from a physical Android device.",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // On-Device AI Status Card (Visible when On-Device AI or legacy Gemma provider is selected)
            if (config.activeProvider == "On-Device AI" || config.activeProvider == "Gemma 4 (On-device)") {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "ON-DEVICE AI STATUS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            // Show which model is active
                            val activeSpec = OnDeviceModelRegistry.findById(config.activeModel)
                            Text(
                                text = "Active: ${activeSpec?.displayName ?: config.activeModel}",
                                fontSize = 12.sp,
                                color = AccentCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (activeSpec != null) {
                                Text(
                                    text = "Backend: ${if (activeSpec.backend == OnDeviceBackend.AI_CORE) "Android AI Core" else "LiteRT-LM"}",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // ─── AI Core Backend Section ───
                            Text(
                                text = "ANDROID AI CORE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            var gemma4Status by remember { mutableStateOf("Checking...") }
                            var showGemma4Download by remember { mutableStateOf(false) }
                            var gemma3nStatus by remember { mutableStateOf("Checking...") }
                            var showGemma3nDownload by remember { mutableStateOf(false) }
                            
                            LaunchedEffect(Unit) {
                                // Check Gemma 4 (default/stable)
                                try {
                                    val client = Generation.getClient()
                                    val status = client.checkStatus()
                                    gemma4Status = when (status) {
                                        FeatureStatus.AVAILABLE -> "Available and ready"
                                        FeatureStatus.DOWNLOADABLE -> {
                                            showGemma4Download = true
                                            "Download needed"
                                        }
                                        FeatureStatus.DOWNLOADING -> "Downloading..."
                                        FeatureStatus.UNAVAILABLE -> "Not supported on this device"
                                        else -> "Unknown"
                                    }
                                } catch (e: Exception) {
                                    gemma4Status = "Not supported on this device"
                                }
                                
                                // Check Gemma 3n (preview/fast)
                                try {
                                    val previewConfig = generationConfig {
                                        modelConfig = modelConfig {
                                            releaseStage = ModelReleaseStage.PREVIEW
                                            preference = ModelPreference.FAST
                                        }
                                    }
                                    val client3n = Generation.getClient(previewConfig)
                                    val status3n = client3n.checkStatus()
                                    gemma3nStatus = when (status3n) {
                                        FeatureStatus.AVAILABLE -> "Available and ready"
                                        FeatureStatus.DOWNLOADABLE -> {
                                            showGemma3nDownload = true
                                            "Download needed"
                                        }
                                        FeatureStatus.DOWNLOADING -> "Downloading..."
                                        FeatureStatus.UNAVAILABLE -> "Not supported on this device"
                                        else -> "Unknown"
                                    }
                                } catch (e: Exception) {
                                    gemma3nStatus = "Not supported on this device"
                                }
                            }
                            
                            // Gemma 4 AI Core row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Gemma 4", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = gemma4Status,
                                    fontSize = 11.sp,
                                    color = if (gemma4Status.contains("ready")) AccentCyan else TextSecondary
                                )
                            }
                            if (showGemma4Download) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                val client = Generation.getClient()
                                                gemma4Status = "Downloading..."
                                                showGemma4Download = false
                                                client.download().collect { }
                                                gemma4Status = "Download complete"
                                            } catch (e: Exception) {
                                                gemma4Status = "Download failed: ${e.localizedMessage}"
                                                showGemma4Download = true
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Download Gemma 4 (AI Core)", color = DarkBackground)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Gemma 3n AI Core row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Gemma 3n Multimodal", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = gemma3nStatus,
                                    fontSize = 11.sp,
                                    color = if (gemma3nStatus.contains("ready")) AccentCyan else TextSecondary
                                )
                            }
                            if (showGemma3nDownload) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                val previewConfig = generationConfig {
                                                    modelConfig = modelConfig {
                                                        releaseStage = ModelReleaseStage.PREVIEW
                                                        preference = ModelPreference.FAST
                                                    }
                                                }
                                                val client3n = Generation.getClient(previewConfig)
                                                gemma3nStatus = "Downloading..."
                                                showGemma3nDownload = false
                                                client3n.download().collect { }
                                                gemma3nStatus = "Download complete"
                                            } catch (e: Exception) {
                                                gemma3nStatus = "Download failed: ${e.localizedMessage}"
                                                showGemma3nDownload = true
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Download Gemma 3n (AI Core)", color = DarkBackground)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(color = BorderColor, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))

                            // ─── Hugging Face Section ───
                            Text(
                                text = "HUGGING FACE TOKEN (GATED MODELS ONLY)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF9800)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Needed only for gated Hugging Face downloads (the Google-hosted Gemma 3n LiteRT builds). Public models such as Qwen 2.5 and the Gemma 4 community mirrors download without a token. Not used for cloud API providers.",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, BorderColor, RoundedCornerShape(10.dp)),
                                colors = CardDefaults.cardColors(containerColor = CardBackground.copy(alpha = 0.3f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val validationStatus by viewModel.huggingFaceValidationStatus.collectAsState()
                                    val lastVerified by viewModel.huggingFaceLastVerified.collectAsState()
                                    var showToken by remember { mutableStateOf(false) }

                                    OutlinedTextField(
                                        value = hfToken,
                                        onValueChange = { viewModel.updateHuggingFaceToken(it) },
                                        label = { Text("Hugging Face Access Token", fontSize = 12.sp) },
                                        singleLine = true,
                                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                                        placeholder = { Text("hf_...", fontSize = 12.sp, color = TextSecondary) },
                                        trailingIcon = {
                                            IconButton(onClick = { showToken = !showToken }) {
                                                Icon(
                                                    imageVector = if (showToken) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                    contentDescription = "Toggle Token Visibility",
                                                    tint = TextSecondary
                                                )
                                            }
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFFF9800),
                                            unfocusedBorderColor = BorderColor,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val context = LocalContext.current
                                        val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                onClick = {
                                                    val clip = clipboardManager.primaryClip
                                                    if (clip != null && clip.itemCount > 0) {
                                                        val pasted = clip.getItemAt(0).text?.toString() ?: ""
                                                        viewModel.updateHuggingFaceToken(pasted)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("📋 Paste", fontSize = 11.sp, color = Color(0xFFFF9800))
                                            }

                                            TextButton(
                                                onClick = { viewModel.updateHuggingFaceToken("") },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("❌ Clear", fontSize = 11.sp, color = Color.Red)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Status display
                                    val statusDisplay = when (validationStatus) {
                                        "Valid" -> "✓ Token Valid"
                                        "Invalid" -> "✗ Invalid Token"
                                        "Verifying..." -> "Checking token..."
                                        "Unable to verify" -> "Unable to verify token."
                                        else -> "⚠ Token Required"
                                    }

                                    val statusColor = when (validationStatus) {
                                        "Valid" -> AccentCyan
                                        "Invalid" -> Color.Red
                                        "Verifying..." -> AccentCyan
                                        "Unable to verify" -> Color.Yellow
                                        else -> TextSecondary
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Status: $statusDisplay", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                                            Text("Last Verified: $lastVerified", fontSize = 9.sp, color = TextSecondary)
                                            Text("Storage: Encrypted", fontSize = 9.sp, color = TextSecondary)
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { viewModel.validateHuggingFaceToken() },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("Validate Token", fontSize = 10.sp, color = DarkBackground, fontWeight = FontWeight.Bold)
                                            }

                                            if (hfToken.isNotBlank()) {
                                                Button(
                                                    onClick = { viewModel.removeHuggingFaceToken() },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Remove Token", fontSize = 10.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(color = BorderColor, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // ─── LiteRT-LM Backend Section ───
                            Text(
                                text = "LITERT-LM (FALLBACK)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF9800)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Runs without Google AI Core. Models tagged PUBLIC (Qwen, the Gemma 4 community mirrors) need no HF token; models tagged GATED (the Google-hosted Gemma 3n builds) do. Or import your own .task / .litertlm file.",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Dynamically list all LiteRT-LM models from database
                            val liteRTModels = OnDeviceModelRegistry.liteRTOnly
                            liteRTModels.forEach { spec ->
                                val modelEntity = dbModels.find { it.id == spec.id }
                                val status = modelEntity?.status ?: ModelStatus.NOT_DOWNLOADED
                                val progress = modelEntity?.downloadProgress ?: 0
                                val downloadedSize = modelEntity?.downloadedSize ?: 0L
                                val totalSize = modelEntity?.size ?: spec.expectedSize
                                val speed = modelEntity?.downloadSpeed ?: ""
                                val eta = modelEntity?.etaString ?: ""
                                
                                var expanded by remember { mutableStateOf(false) }
                                val isApiCompatible = android.os.Build.VERSION.SDK_INT >= spec.minSdk
                                val managedDownloadAvailable = spec.isManagedDownloadAvailable
                                
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .border(
                                            1.dp,
                                            if (config.activeModel == spec.id) AccentCyan.copy(alpha = 0.5f) else BorderColor,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { if (isApiCompatible) expanded = !expanded },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (config.activeModel == spec.id) CardBackground.copy(alpha = 0.8f) else CardBackground.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = spec.displayName,
                                                        fontSize = 13.sp,
                                                        color = if (isApiCompatible) TextPrimary else TextSecondary,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    if (spec.isRecommended) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .background(Color(0xFFFF9800).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = "REC",
                                                                color = Color(0xFFFF9800),
                                                                fontSize = 8.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                if (spec.authRequired) Color(0xFFFF9800).copy(alpha = 0.12f)
                                                                else AccentCyan.copy(alpha = 0.12f),
                                                                RoundedCornerShape(4.dp)
                                                            )
                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = if (spec.authRequired) "GATED · HF TOKEN" else "PUBLIC · NO TOKEN",
                                                            color = if (spec.authRequired) Color(0xFFFF9800) else AccentCyan,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = if (managedDownloadAvailable) {
                                                        if (spec.authRequired) {
                                                            "Backend: LiteRT-LM · Gated Hugging Face download"
                                                        } else {
                                                            "Backend: LiteRT-LM · Public download (no token)"
                                                        }
                                                    } else {
                                                        "Backend: LiteRT-LM · In-app download unavailable; local import only"
                                                    },
                                                    fontSize = 10.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                            
                                            val badgeColor = when (status) {
                                                ModelStatus.READY -> AccentCyan
                                                ModelStatus.DOWNLOADING -> Color(0xFFFF9800)
                                                ModelStatus.PAUSED -> Color.Yellow
                                                ModelStatus.LOADING -> AccentCyan
                                                ModelStatus.FAILED -> Color.Red
                                                else -> TextSecondary
                                            }
                                            
                                            val statusText = when {
                                                !isApiCompatible -> "API ${spec.minSdk}+ Req"
                                                status == ModelStatus.READY -> "Downloaded"
                                                !managedDownloadAvailable -> "In-app unavailable"
                                                status == ModelStatus.DOWNLOADING -> "${progress}%"
                                                status == ModelStatus.PAUSED -> "Paused"
                                                status == ModelStatus.LOADING -> "Loading..."
                                                status == ModelStatus.FAILED -> "Failed"
                                                else -> "Not Downloaded"
                                            }
                                            
                                            Text(
                                                text = statusText,
                                                fontSize = 10.sp,
                                                color = badgeColor,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                        
                                        if (isApiCompatible && (status == ModelStatus.DOWNLOADING || status == ModelStatus.PAUSED)) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { progress.toFloat() / 100f },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp)),
                                                color = Color(0xFFFF9800),
                                                trackColor = BorderColor
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${formatBytes(downloadedSize)} / ${formatBytes(totalSize)}" +
                                                           (if (status == ModelStatus.DOWNLOADING && speed.isNotEmpty()) " @ $speed" else ""),
                                                    fontSize = 9.sp,
                                                    color = TextSecondary
                                                )
                                                if (status == ModelStatus.DOWNLOADING && eta.isNotEmpty()) {
                                                    Text(
                                                        text = "ETA: $eta",
                                                        fontSize = 9.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                            }
                                            
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                if (status == ModelStatus.DOWNLOADING) {
                                                    Button(
                                                        onClick = { viewModel.pauseDownload(spec.id) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = BorderColor),
                                                        modifier = Modifier.height(28.dp).padding(horizontal = 4.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(12.dp), tint = TextPrimary)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Pause", fontSize = 10.sp, color = TextPrimary)
                                                    }
                                                } else if (status == ModelStatus.PAUSED) {
                                                    Button(
                                                        onClick = {
                                                            if (viewModel.isCellularNetwork()) {
                                                                pendingCellularResumeModelId = spec.id
                                                            } else {
                                                                viewModel.resumeDownload(spec.id)
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                                        modifier = Modifier.height(28.dp).padding(horizontal = 4.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(12.dp), tint = DarkBackground)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Resume", fontSize = 10.sp, color = DarkBackground)
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Button(
                                                    onClick = { viewModel.cancelDownload(spec.id) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.6f)),
                                                    modifier = Modifier.height(28.dp).padding(horizontal = 4.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Text("Cancel", fontSize = 10.sp, color = Color.White)
                                                }
                                            }
                                        }

                                        if (isApiCompatible && status == ModelStatus.FAILED) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            val errorText = modelEntity?.etaString ?: "Download failed"
                                            Text(
                                                text = errorText,
                                                fontSize = 10.sp,
                                                color = Color.Red,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (spec.licenseUrl.isNotEmpty() && (errorText.contains("permission", ignoreCase = true) || errorText.contains("license", ignoreCase = true))) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                                Button(
                                                    onClick = { uriHandler.openUri(spec.licenseUrl) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.2f)),
                                                    modifier = Modifier.fillMaxWidth().height(28.dp),
                                                    contentPadding = PaddingValues(vertical = 2.dp)
                                                ) {
                                                    Text("Open Model Page", color = Color(0xFFFF9800), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        
                                        AnimatedVisibility(visible = expanded) {
                                            Column {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Divider(color = BorderColor, thickness = 0.5.dp)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    if (status == ModelStatus.NOT_DOWNLOADED || status == ModelStatus.FAILED) {
                                                        if (managedDownloadAvailable) {
                                                            Button(
                                                                onClick = {
                                                                    val hfTokenVal = hfToken
                                                                    if (spec.authRequired && hfTokenVal.isBlank()) {
                                                                        showAuthRequiredDialog = spec.displayName
                                                                        licenseUrlForDialog = spec.licenseUrl
                                                                    } else if (viewModel.isCellularNetwork()) {
                                                                        showCellularWarningDialog = spec.id
                                                                    } else {
                                                                        viewModel.downloadModel(spec.id)
                                                                    }
                                                                },
                                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                                                modifier = Modifier.weight(1f).height(32.dp),
                                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                                            ) {
                                                                Text("Download", fontSize = 11.sp, color = DarkBackground)
                                                            }
                                                        }

                                                        Button(
                                                            onClick = {
                                                                importAsCustomModel = false
                                                                activeImportModelId = spec.id
                                                                importLauncher.launch("*/*")
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = BorderColor),
                                                            modifier = Modifier.weight(1f).height(32.dp),
                                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                                        ) {
                                                            Text("Import", fontSize = 11.sp, color = TextPrimary)
                                                        }
                                                    }
                                                    
                                                    if (status == ModelStatus.READY) {
                                                         Button(
                                                             onClick = { viewModel.loadModel(spec.id) },
                                                             colors = ButtonDefaults.buttonColors(
                                                                 containerColor = if (config.activeModel == spec.id) TextPrimary else AccentCyan
                                                             ),
                                                             modifier = Modifier.weight(1f).height(32.dp),
                                                             contentPadding = PaddingValues(horizontal = 4.dp)
                                                         ) {
                                                             Icon(
                                                                 if (config.activeModel == spec.id) Icons.Default.Check else Icons.Default.ArrowForward,
                                                                 contentDescription = null,
                                                                 modifier = Modifier.size(12.dp),
                                                                 tint = DarkBackground
                                                             )
                                                             Spacer(modifier = Modifier.width(4.dp))
                                                             Text(if (config.activeModel == spec.id) "Active" else "Load Model", fontSize = 11.sp, color = DarkBackground)
                                                         }
                                                         
                                                         Button(
                                                             onClick = { viewModel.deleteModel(spec.id) },
                                                             colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                                                             modifier = Modifier.height(32.dp),
                                                             contentPadding = PaddingValues(horizontal = 8.dp)
                                                         ) {
                                                             Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(14.dp), tint = Color.Red)
                                                         }
                                                    }
                                                    
                                                    Button(
                                                        onClick = {
                                                            // Info clicked (No-op or log details)
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = BorderColor),
                                                        modifier = Modifier.height(32.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                                    ) {
                                                        Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(14.dp), tint = TextSecondary)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // ─── Custom LiteRT imports ───
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = BorderColor, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "CUSTOM LITERT MODELS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF9800)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Import any .task or .litertlm file as its own model (not tied to a catalog slot). GGUF is not supported yet.",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    importAsCustomModel = true
                                    activeImportModelId = null
                                    importLauncher.launch("*/*")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text("Import custom LiteRT model", fontSize = 12.sp, color = DarkBackground)
                            }

                            val customModels = dbModels.filter {
                                OnDeviceModelRegistry.isCustomId(it.id) &&
                                    it.status == ModelStatus.READY
                            }
                            customModels.forEach { entity ->
                                var expanded by remember(entity.id) { mutableStateOf(false) }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .border(
                                            1.dp,
                                            if (config.activeModel == entity.id) AccentCyan.copy(alpha = 0.5f) else BorderColor,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { expanded = !expanded },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (config.activeModel == entity.id) {
                                            CardBackground.copy(alpha = 0.8f)
                                        } else {
                                            CardBackground.copy(alpha = 0.3f)
                                        }
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entity.name,
                                                    fontSize = 13.sp,
                                                    color = TextPrimary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "Custom LiteRT · ${formatBytes(entity.size)} · no token",
                                                    fontSize = 10.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                            Text(
                                                text = if (config.activeModel == entity.id) "Active" else "Ready",
                                                fontSize = 10.sp,
                                                color = AccentCyan,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        AnimatedVisibility(visible = expanded) {
                                            Column {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Divider(color = BorderColor, thickness = 0.5.dp)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = { viewModel.loadModel(entity.id) },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = if (config.activeModel == entity.id) {
                                                                TextPrimary
                                                            } else {
                                                                AccentCyan
                                                            }
                                                        ),
                                                        modifier = Modifier.weight(1f).height(32.dp),
                                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                                    ) {
                                                        Text(
                                                            if (config.activeModel == entity.id) "Active" else "Load Model",
                                                            fontSize = 11.sp,
                                                            color = DarkBackground
                                                        )
                                                    }
                                                    Button(
                                                        onClick = { viewModel.deleteModel(entity.id) },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = Color.Red.copy(alpha = 0.2f)
                                                        ),
                                                        modifier = Modifier.height(32.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            contentDescription = "Delete",
                                                            modifier = Modifier.size(14.dp),
                                                            tint = Color.Red
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = BorderColor, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))

                            // ─── Storage Cleanup Section ───
                            Text(
                                text = "STORAGE CLEANUP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF9800)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            val totalSpace = storageInfo.totalBytes
                            val freeSpace = storageInfo.freeBytes
                            val usedByApp = storageInfo.usedByAppBytes
                            val usedPercentage = if (totalSpace > 0) ((totalSpace - freeSpace).toFloat() / totalSpace.toFloat()) else 0f
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Used: ${formatBytes(totalSpace - freeSpace)} / ${formatBytes(totalSpace)}",
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${((totalSpace - freeSpace) * 100 / (totalSpace.coerceAtLeast(1L)))}% Used",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                             }
                             Spacer(modifier = Modifier.height(4.dp))
                             LinearProgressIndicator(
                                 progress = { usedPercentage },
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .height(6.dp)
                                     .clip(RoundedCornerShape(3.dp)),
                                 color = Color(0xFFFF9800),
                                 trackColor = BorderColor
                             )
                             Spacer(modifier = Modifier.height(6.dp))
                             Text(
                                 text = "OpenDroid models occupy ${formatBytes(usedByApp)} of on-device storage.",
                                 fontSize = 10.sp,
                                 color = TextSecondary
                             )
                             Spacer(modifier = Modifier.height(8.dp))
                             Button(
                                 onClick = { viewModel.deleteUnusedModels() },
                                 colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                                 modifier = Modifier.fillMaxWidth(),
                                 shape = RoundedCornerShape(8.dp)
                             ) {
                                 Text("Delete Unused Models", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                             }
                        }
                    }
                }
            }

            // Copilot Endpoint Config Card (Visible only when Copilot API is selected)
            if (config.activeProvider == "Copilot API") {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "COPILOT LOCAL ENDPOINT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = config.copilotUrl,
                                onValueChange = { viewModel.updateCopilotUrl(it) },
                                label = { Text("Copilot Server URL", fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Use local LAN IP (e.g. http://192.168.1.50:4141) if testing from a physical Android device.",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Custom OpenAI Compatible Endpoint Config Card (Visible only when Custom OpenAI Compatible is selected)
            if (config.activeProvider == "Custom OpenAI Compatible") {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "CUSTOM OPENAI ENDPOINT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = config.customEndpoints["Custom OpenAI Compatible"] ?: "",
                                onValueChange = { viewModel.updateCustomEndpoint("Custom OpenAI Compatible", it) },
                                label = { Text("Base URL (e.g. https://api.openai.com/v1)", fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Provide the custom OpenAI-compatible API base URL (e.g. from Pollination, Aqua Dev, Portkey, etc.)",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // Provider API Keys Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { keysSectionExpanded = !keysSectionExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PROVIDER API KEYS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Icon(
                                imageVector = if (keysSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Keys Section",
                                tint = AccentCyan
                            )
                        }

                        AnimatedVisibility(visible = keysSectionExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val inputProviders = providers.filter { it != "Ollama" && it != "On-Device AI" }
                                inputProviders.forEach { providerName ->
                                    val keyVal = config.apiKeys[providerName] ?: ""
                                    val connectionState = connectionResults[providerName]
                                    SecureApiKeyField(
                                        value = keyVal,
                                        onValueChange = { viewModel.updateApiKey(providerName, it) },
                                        label = "$providerName API Key"
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = connectionStatusLabel(connectionState),
                                            fontSize = 10.sp,
                                            color = TextSecondary,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { viewModel.testConnection(providerName) }
                                        ) {
                                            Text("Test connection", fontSize = 11.sp)
                                        }
                                    }
                                    Text(
                                        text = "Sends one minimal request to $providerName; provider charges may apply.",
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ElevenLabs Voice Synthesis Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { voiceSectionExpanded = !voiceSectionExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ELEVENLABS VOICE SYNTHESIS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Icon(
                                imageVector = if (voiceSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Voice Section",
                                tint = AccentCyan
                            )
                        }

                        AnimatedVisibility(visible = voiceSectionExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SecureApiKeyField(
                                    value = config.elevenLabsApiKey,
                                    onValueChange = { viewModel.updateElevenLabsApiKey(it) },
                                    label = "ElevenLabs API Key"
                                )
                                OutlinedTextField(
                                    value = config.elevenLabsVoiceId,
                                    onValueChange = { viewModel.updateElevenLabsVoiceId(it) },
                                    label = { Text("ElevenLabs Voice ID", fontSize = 12.sp) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentCyan,
                                        unfocusedBorderColor = BorderColor,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "If ElevenLabs key is not set, OpenDroid automatically falls back to native offline Android Text-to-Speech.",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Planning & Automation Preferences Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { planningSectionExpanded = !planningSectionExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PLANNING & AUTOMATION",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Icon(
                                imageVector = if (planningSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Planning Section",
                                tint = AccentCyan
                            )
                        }

                        AnimatedVisibility(visible = planningSectionExpanded) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {

                        var showYoloWarning by remember { mutableStateOf(false) }
                        val autoMode = config.resolvedAutoMode()

                        Text(
                            text = "Auto Mode",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Auto runs plans whose every step you've allowed. YOLO runs everything without asking.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AutoMode.entries.forEach { mode ->
                                val selected = autoMode == mode
                                val accent = if (mode == AutoMode.YOLO) AccentRed else AccentCyan
                                OutlinedButton(
                                    onClick = {
                                        if (mode == AutoMode.YOLO && !selected) showYoloWarning = true
                                        else viewModel.setAutoMode(mode)
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (selected) accent else TextSecondary
                                    ),
                                    border = BorderStroke(1.dp, if (selected) accent else BorderColor),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = when (mode) {
                                            AutoMode.OFF -> "Off"
                                            AutoMode.AUTO -> "Auto"
                                            AutoMode.YOLO -> "YOLO"
                                        },
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        if (showYoloWarning) {
                            AlertDialog(
                                onDismissRequest = { showYoloWarning = false },
                                containerColor = DarkSurface,
                                title = { Text("Enable YOLO mode?", color = AccentRed, fontWeight = FontWeight.Bold) },
                                text = {
                                    Text(
                                        "YOLO runs EVERY plan without asking — including actions that " +
                                        "spend money (UPI payments, food and cab orders) and irreversible " +
                                        "ones (installing apps, deleting files, restarting the device). " +
                                        "No approval gate remains.",
                                        color = TextPrimary
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showYoloWarning = false
                                        viewModel.setAutoMode(AutoMode.YOLO)
                                    }) { Text("I understand, enable", color = AccentRed) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showYoloWarning = false }) {
                                        Text("Cancel", color = TextSecondary)
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        val grantedActions = config.effectiveGrantedActions()
                        Text(
                            text = "ALLOWED ACTIONS (${grantedActions.size})",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val dateFormat = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }
                        grantedActions.entries
                            .sortedBy { it.key }
                            .forEach { (action, grantedAt) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = action, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                                        Text(
                                            text = if (grantedAt == 0L) "Default" else "Granted ${dateFormat.format(java.util.Date(grantedAt))}",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                    TextButton(onClick = { viewModel.revokeGrant(action) }) {
                                        Text("Revoke", color = AccentRed, fontSize = 12.sp)
                                    }
                                }
                            }

                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BorderColor)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Multi-Agent Planning Mode",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Use critic and plan merger agents for safer, more robust plan generation.",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = config.multiAgentModeEnabled,
                                onCheckedChange = { viewModel.updateMultiAgentMode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = TextPrimary.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BorderColor)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Show Floating Button",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Show a tiny floating bubble to launch the app or record commands directly.",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = config.showFloatingButton,
                                onCheckedChange = { viewModel.updateShowFloatingButton(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = TextPrimary.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BorderColor)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (config.isDarkMode) "Dark Mode" else "Light Mode",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Switch between dark and light appearance.",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = config.isDarkMode,
                                onCheckedChange = { viewModel.updateDarkMode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = TextPrimary.copy(alpha = 0.5f)
                                )
                            )
                        }
                        }
                        }
                    }
                }
            }

            // Auto-Reply Settings Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AccentPurple.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable { onNavigateToAutoReply() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AUTO-REPLY SETTINGS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentPurple
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Configure AI auto-reply for WhatsApp, SMS & Email.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Notification History Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable { onNavigateToNotificationHistory() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔔", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NOTIFICATION HISTORY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "View captured notifications and auto-reply log.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Permissions link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToPermissions() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Permissions",
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PERMISSIONS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Review and grant microphone, storage, accessibility & other permissions.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Habits & Routines link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToRoutines() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "HABITS & ROUTINES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Detect repeated daily patterns & automate morning routines.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Crash Log link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AccentRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable { onNavigateToCrashLog() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💥", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CRASH LOG",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentRed
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "View and share crashes recorded on this device.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Privacy Policy link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToPrivacyPolicy() },
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
                            contentDescription = "Privacy Policy",
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PRIVACY POLICY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "How OpenDroid handles your data and privacy.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Terms of Use link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToTermsOfUse() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Terms of Use",
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TERMS OF USE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Usage terms and conditions for OpenDroid.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Help Center link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToHelpCenter() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Help Center",
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "HELP CENTER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Guides, FAQs, and troubleshooting.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // License link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToLicense() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "License",
                            tint = AccentPurple,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LICENSE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentPurple
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Open-source license and third-party credits.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // About link card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onNavigateToAbout() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About",
                            tint = AccentPurple,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ABOUT OPENDROID",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentPurple
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Version info, features, and technology stack.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "Go",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // System integration info card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "SYSTEM INTEGRATION PERMISSIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "To allow OpenDroid to operate other applications autonomously (e.g. WhatsApp, Calendar), verify that the accessibility service 'OpenDroid' is active in Settings -> Accessibility -> Installed Services.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }

    val localImportStatus by viewModel.localImportStatus.collectAsState()

    if (showAuthRequiredDialog != null) {
        AlertDialog(
            onDismissRequest = { showAuthRequiredDialog = null },
            title = { Text("Authentication Required", color = TextPrimary) },
            text = {
                Text(
                    text = "This model is gated on Hugging Face and needs an Access Token to download.\n\n" +
                        "Models tagged PUBLIC (for example Qwen 2.5 and the Gemma 4 community mirrors) do not need a token — only the ones tagged GATED do. " +
                        "Add a read-only token in the Hugging Face section above, or pick a PUBLIC model.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { showAuthRequiredDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("OK", color = DarkBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAuthRequiredDialog = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    if (showCellularWarningDialog != null) {
        val modelIdToDownload = showCellularWarningDialog!!
        AlertDialog(
            onDismissRequest = { showCellularWarningDialog = null },
            title = { Text("Cellular Network Warning", color = TextPrimary) },
            text = {
                Text(
                    text = "You are downloading model on cellular network, data charges may apply.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCellularWarningDialog = null
                        viewModel.downloadModel(modelIdToDownload)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Download", color = DarkBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCellularWarningDialog = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    if (pendingCellularResumeModelId != null) {
        val modelIdToResume = pendingCellularResumeModelId!!
        AlertDialog(
            onDismissRequest = { pendingCellularResumeModelId = null },
            title = { Text("Cellular Network Warning", color = TextPrimary) },
            text = {
                Text(
                    text = "You are downloading model on cellular network, data charges may apply.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCellularResumeModelId = null
                        viewModel.resumeDownload(modelIdToResume)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Resume", color = DarkBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCellularResumeModelId = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    if (localImportStatus != null) {
        val isImporting = localImportStatus == "Importing..."
        val isSuccess = localImportStatus == "Success"
        AlertDialog(
            onDismissRequest = {
                if (!isImporting) {
                    viewModel.clearImportStatus()
                }
            },
            title = {
                Text(
                    text = when {
                        isImporting -> "Importing Model"
                        isSuccess -> "Import Successful"
                        else -> "Import Failed"
                    },
                    color = TextPrimary
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    when {
                        isImporting -> {
                            CircularProgressIndicator(color = Color(0xFFFF9800))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Copying and verifying the model file. This may take a minute...", color = TextSecondary)
                        }
                        isSuccess -> {
                            Text("The model was imported and verified successfully. You can now load it.", color = TextSecondary)
                        }
                        else -> {
                            Text(
                                text = localImportStatus
                                    ?: "Failed to import model. Please make sure it is a valid LiteRT model file (.task or .litertlm) and is not corrupted.",
                                color = Color.Red
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isImporting) {
                    Button(
                        onClick = { viewModel.clearImportStatus() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("OK", color = DarkBackground)
                    }
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

private fun connectionStatusLabel(state: ConnectionTestState?): String = when (state) {
    is ConnectionTestState.Testing -> "Testing…"
    is ConnectionTestState.Connected ->
        "Connected with ${state.model} · ${state.latencyMs} ms"
    is ConnectionTestState.Failed -> when (state.error) {
        LLMError.AuthInvalid -> "Key rejected"
        LLMError.AuthMissing -> "Key required"
        LLMError.QuotaExhausted -> "Quota exhausted"
        LLMError.RateLimited -> "Rate limited"
        LLMError.Network -> "Network error"
        else -> "Connection failed"
    }
    is ConnectionTestState.ConfigMissing -> when (state.reason) {
        LLMError.AuthMissing -> "Key required"
        else -> "Configuration required"
    }
    else -> "Not tested"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(
        Locale.getDefault(),
        "%.1f %s",
        bytes / Math.pow(1024.0, digitGroups.toDouble()),
        units[digitGroups]
    )
}

@Composable
private fun SecureApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide API key" else "Show API key",
                    tint = TextSecondary
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = BorderColor,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        modifier = modifier.fillMaxWidth()
    )
}
