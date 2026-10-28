package com.opendroid.ai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.AutoApprovalPolicy
import com.opendroid.ai.core.agent.ChatErrorPrimaryAction
import com.opendroid.ai.core.agent.ChatErrorUiState
import com.opendroid.ai.core.agent.guidance
import com.opendroid.ai.core.agent.primaryAction
import com.opendroid.ai.core.agent.title
import com.opendroid.ai.core.voice.SpeechRecognitionEngine
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.effectiveGrantedActions
import com.opendroid.ai.data.models.resolvedAutoMode
import com.opendroid.ai.data.repository.ChatSession
import com.opendroid.ai.ui.components.ContactPickerCard
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by viewModel.conversationHistory.collectAsState()
    // Scoped to whichever chat is on screen right now - a task that's actually running
    // in a DIFFERENT chat (safe since the pinned-session fix: it keeps writing there,
    // not wherever the user has navigated to) must never be displayed as if it were
    // happening here. See ChatViewModel.visibleAgentState.
    val visibleAgentState by viewModel.visibleAgentState.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    // Id of whichever chat (if any) has a task actively running, regardless of which
    // chat is currently displayed - drives the chat-picker's "still running" indicator.
    val runningSessionId by viewModel.runningSessionId.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val llmConfig by viewModel.llmConfig.collectAsState()
    val currentSessionId = sessions.firstOrNull { it.isCurrent }?.id
    val runningElsewhere = runningSessionId != null && runningSessionId != currentSessionId

    val listState = rememberLazyListState()
    var inputQuery by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var transcriptionText by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var showChatMenu by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var sessionPendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var textBeforeEdit by remember { mutableStateOf("") }

    // Merges a piece of dictated text into whatever the user already typed, so review/edit
    // never clobbers text entered before dictation started.
    fun mergeDictatedText(newText: String) {
        if (newText.isBlank()) return
        inputQuery = if (inputQuery.isBlank()) {
            newText
        } else {
            inputQuery.trimEnd() + " " + newText.trimStart()
        }
    }

    // Loads a previously sent user message into the input field for editing, remembering
    // whatever was already typed so cancelling the edit can restore it untouched.
    fun startEditingMessage(message: ChatMessage) {
        if (editingMessageId == null) {
            textBeforeEdit = inputQuery
        }
        editingMessageId = message.id
        inputQuery = message.text
    }

    // Restores the input to whatever it held before the edit started; the conversation
    // itself is never touched until a resend is actually submitted.
    fun cancelEditingMessage() {
        editingMessageId = null
        inputQuery = textBeforeEdit
        textBeforeEdit = ""
    }

    // Single submit path for both a normal send and an edit-resend, wired to both the
    // keyboard "Send" action and the send button below.
    fun submitInput() {
        val text = inputQuery
        if (text.isBlank()) return
        val editing = editingMessageId
        if (editing != null) {
            viewModel.editAndResend(editing, text, context)
            editingMessageId = null
            textBeforeEdit = ""
        } else {
            viewModel.sendMessage(text, context)
        }
        inputQuery = ""
    }

    // Scroll to bottom on history change. Keyed on currentSessionId too: keying on
    // history.size alone left the scroll position from the PREVIOUS chat in place
    // whenever the newly switched-to chat happened to have the same message count -
    // including currentSessionId forces a re-anchor to the bottom on every chat switch.
    LaunchedEffect(currentSessionId, history.size, visibleAgentState, chatError) {
        val totalItems = history.size +
            (if (visibleAgentState is AgentState.Thinking) 1 else 0) +
            (if (chatError != null) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val speechRecognizer = remember { SpeechRecognitionEngine(context) }
    
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isListening = true
            voiceError = null
            transcriptionText = ""
            speechRecognizer.startListening(
                onResult = { text ->
                    isListening = false
                    mergeDictatedText(text)
                    transcriptionText = ""
                },
                onPartialResult = { partial ->
                    transcriptionText = partial
                },
                onError = { err ->
                    isListening = false
                    mergeDictatedText(transcriptionText)
                    transcriptionText = ""
                    voiceError = err
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "OPENDROID",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            letterSpacing = 2.sp
                        )
                        AgentStatusSubtitle(visibleAgentState, runningElsewhere)
                    }
                },
                actions = {
                    val autoMode = llmConfig.resolvedAutoMode()
                    val chipColor = when (autoMode) {
                        AutoMode.OFF -> TextSecondary
                        AutoMode.AUTO -> TextPrimary
                        AutoMode.YOLO -> AccentRed
                    }
                    OutlinedButton(
                        onClick = { viewModel.cycleAutoMode() },
                        border = BorderStroke(1.dp, chipColor.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = chipColor),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = when (autoMode) {
                                AutoMode.OFF -> "MANUAL"
                                AutoMode.AUTO -> "AUTO"
                                AutoMode.YOLO -> "YOLO"
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { viewModel.newChat() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New chat",
                            tint = TextSecondary
                        )
                    }
                    Box {
                        IconButton(onClick = { showChatMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = "Chats",
                                tint = TextSecondary
                            )
                        }
                        DropdownMenu(
                            expanded = showChatMenu,
                            onDismissRequest = { showChatMenu = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            if (sessions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No chats yet", color = TextSecondary, fontSize = 13.sp) },
                                    onClick = {},
                                    enabled = false
                                )
                            }
                            sessions.forEach { session ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = session.title,
                                                color = if (session.isCurrent) TextPrimary else TextSecondary,
                                                fontWeight = if (session.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp,
                                                maxLines = 1
                                            )
                                            // Small "still running" dot: a task can now keep
                                            // executing in a chat the user has switched away
                                            // from, so this is the only place that fact is
                                            // visible once its row scrolls out of the top bar.
                                            if (runningSessionId == session.id) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(AccentCyan)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = if (session.isCurrent) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = TextPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    renameText = session.title
                                                    sessionPendingRename = session
                                                    showChatMenu = false
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Rename chat",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    sessionPendingDelete = session
                                                    showChatMenu = false
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete chat",
                                                    tint = AccentRed,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        showChatMenu = false
                                        viewModel.switchToSession(session.id)
                                    }
                                )
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.clearChat() }) {
                        Text("Clear", color = TextSecondary, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp)
            ) {
                // Messages List
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
                ) {
                    items(history) { msg ->
                        ChatBubble(
                            message = msg,
                            viewModel = viewModel,
                            context = context,
                            onEditRequested = { startEditingMessage(it) }
                        )
                    }
                    
                    // Show a typing/thinking bubble if thinking - scoped to this chat, see
                    // visibleAgentState.
                    if (visibleAgentState is AgentState.Thinking) {
                        item {
                            ThinkingBubble()
                        }
                    }

                    chatError?.let { error ->
                        item(key = "chat-error-${error.requestId}-${error.runId}") {
                            ChatErrorRecoveryCard(
                                error = error,
                                onPrimary = {
                                    when (error.primaryAction()) {
                                        ChatErrorPrimaryAction.RETRY ->
                                            viewModel.retryAfterChatError(context)
                                        ChatErrorPrimaryAction.EDIT_MESSAGE -> {
                                            // Edit the exact message the error is about;
                                            // fall back to the last user message only if
                                            // the requestId no longer resolves to one.
                                            val target = history.firstOrNull {
                                                it.id == error.requestId &&
                                                    it.sender == ChatMessage.Sender.USER
                                            } ?: history.lastOrNull {
                                                it.sender == ChatMessage.Sender.USER
                                            }
                                            target?.let { startEditingMessage(it) }
                                            viewModel.dismissChatError()
                                        }
                                        else -> viewModel.dismissChatError()
                                    }
                                },
                                onDismiss = { viewModel.dismissChatError() }
                            )
                        }
                    }
                }

                // If agent proposed a plan for THIS chat, show a modal prompt to approve or
                // reject. A plan proposed for a different chat must never surface here - the
                // user could approve/reject the wrong chat's plan without realizing it.
                if (visibleAgentState is AgentState.PlanProposed) {
                    val proposedPlan = (visibleAgentState as AgentState.PlanProposed).plan
                    val blocked = if (llmConfig.resolvedAutoMode() == AutoMode.AUTO) {
                        AutoApprovalPolicy.blockedActions(
                            llmConfig.effectiveGrantedActions().keys, proposedPlan.steps
                        )
                    } else emptyList()
                    ProposedPlanPrompt(
                        planId = proposedPlan.planId,
                        goal = proposedPlan.goal,
                        stepsCount = proposedPlan.estimatedSteps,
                        blockedActions = blocked,
                        grantableActions = blocked.filter { AutoApprovalPolicy.isGrantable(it) }.toSet(),
                        onApprove = { grants -> viewModel.approvePlan(context, grants) },
                        onReject = { viewModel.rejectPlan() }
                    )
                }
            }

            // Bottom Input Section with Orb overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, DarkBackground),
                            startY = 0f,
                            endY = 50f
                        )
                    )
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (editingMessageId != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Editing message",
                                fontSize = 11.sp,
                                color = AccentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel edit",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { cancelEditingMessage() }
                            )
                        }
                    }
                    if (voiceError != null) {
                        Text(
                            text = voiceError.orEmpty(),
                            fontSize = 11.sp,
                            color = AccentRed,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Floating Orb for Speech
                        FloatingOrb(
                            isListening = isListening,
                            agentState = visibleAgentState,
                            onClick = {
                                if (isListening) {
                                    // True cancel: no final result will be delivered for this
                                    // session, so whatever partial transcript we already have is
                                    // handed back to the input field instead of being lost.
                                    speechRecognizer.cancel()
                                    isListening = false
                                    mergeDictatedText(transcriptionText)
                                    transcriptionText = ""
                                } else {
                                    val audioPerm = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                    if (audioPerm == PackageManager.PERMISSION_GRANTED) {
                                        isListening = true
                                        voiceError = null
                                        transcriptionText = ""
                                        speechRecognizer.startListening(
                                            onResult = { text ->
                                                isListening = false
                                                mergeDictatedText(text)
                                                transcriptionText = ""
                                            },
                                            onPartialResult = { partial ->
                                                transcriptionText = partial
                                            },
                                            onError = { err ->
                                                isListening = false
                                                mergeDictatedText(transcriptionText)
                                                transcriptionText = ""
                                                voiceError = err
                                            }
                                        )
                                    } else {
                                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Text Input Field / Voice Waveform Area
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp, max = 120.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(CardBackground)
                                .border(1.dp, BorderColor, RoundedCornerShape(28.dp))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (isListening) {
                                VoiceWaveform(
                                    text = transcriptionText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(vertical = 12.dp)
                                )
                            } else {
                                TextField(
                                    value = inputQuery,
                                    onValueChange = { inputQuery = it; voiceError = null },
                                    placeholder = { Text("Ask OpenDroid to run an autonomous task...", color = TextSecondary, fontSize = 14.sp) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    singleLine = false,
                                    maxLines = 4,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !isListening && inputQuery.isNotBlank(),
                            enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.8f),
                            exit = fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.8f)
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { submitInput() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(TextPrimary)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Send",
                                        tint = DarkBackground
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sessionPendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            containerColor = DarkSurface,
            title = { Text("Delete chat?", color = TextPrimary) },
            text = {
                Text(
                    "\"${session.title}\" and its messages will be permanently deleted.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChat(session.id)
                    sessionPendingDelete = null
                }) {
                    Text("Delete", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    sessionPendingRename?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingRename = null },
            containerColor = DarkSurface,
            title = { Text("Rename chat", color = TextPrimary) },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = AccentCyan,
                        unfocusedIndicatorColor = BorderColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(session.id, renameText)
                    sessionPendingRename = null
                }) {
                    Text("Save", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingRename = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
fun AgentStatusSubtitle(state: AgentState, runningElsewhere: Boolean = false) {
    // runningElsewhere means [state] has already been forced to Idle because the real
    // activity belongs to a different chat (see ChatViewModel.visibleAgentState) - say
    // so explicitly instead of showing a plain "Online & Ready" that would hide the
    // fact that a task is still going in the background.
    val text = if (runningElsewhere) {
        "Online & Ready · Task running in another chat"
    } else {
        when (state) {
            is AgentState.Idle -> "Online & Ready"
            is AgentState.Listening -> "Listening to voice input..."
            is AgentState.Thinking -> "Analyzing intent & planning..."
            is AgentState.PlanProposed -> "Requires Plan Approval"
            is AgentState.ExecutingPlan -> "Executing: ${state.currentStepDesc}"
            is AgentState.Speaking -> "Speaking: ${state.text.take(30)}..."
            is AgentState.Error -> "Execution Error"
        }
    }

    val color = if (runningElsewhere) {
        AccentPurple
    } else {
        when (state) {
            is AgentState.Idle -> TextSecondary
            is AgentState.Listening -> AccentRed
            is AgentState.Thinking -> AccentPurple
            is AgentState.PlanProposed -> AccentCyan
            is AgentState.ExecutingPlan -> AccentCyan
            is AgentState.Speaking -> AccentCyan
            is AgentState.Error -> AccentRed
        }
    }

    Text(
        text = text,
        fontSize = 11.sp,
        color = color,
        fontFamily = FontFamily.SansSerif
    )
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    viewModel: ChatViewModel? = null,
    context: android.content.Context? = null,
    onEditRequested: ((ChatMessage) -> Unit)? = null
) {
    val isAgent = message.sender == ChatMessage.Sender.AGENT
    val alignment = if (isAgent) Alignment.Start else Alignment.End
    val bubbleColor = if (isAgent) {
        CardBackground
    } else {
        if (AppTheme.colors.isDark) Color(0xFF1E1E22) else Color(0xFFF1F3F5)
    }
    val bubbleBorder = if (isAgent) {
        BorderColor
    } else {
        if (AppTheme.colors.isDark) Color(0xFF2E2E34) else Color(0xFFE2E8F0)
    }
    val textColor = TextPrimary
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    // If this is a contact picker message, render the ContactPickerCard instead
    if (isAgent && message.contactPickerData != null) {
        val matches: List<Map<String, String>> = try {
            Json { ignoreUnknownKeys = true }
                .decodeFromString<List<Map<String, String>>>(message.contactPickerData)
        } catch (_: Exception) {
            emptyList()
        }

        if (matches.isNotEmpty()) {
            // Extract query from text ("Which 'dad' do you mean?" ? "dad")
            val query = Regex("Which '(.*?)'").find(message.text)?.groupValues?.getOrNull(1) ?: "contact"

            ContactPickerCard(
                query = query,
                matches = matches,
                onContactSelected = { selected ->
                    val index = matches.indexOf(selected) + 1
                    if (viewModel != null && context != null) {
                        viewModel.sendMessage(index.toString(), context)
                    }
                }
            )
            return
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isAgent) Arrangement.Start else Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isAgent) 4.dp else 18.dp,
                            bottomEnd = if (isAgent) 18.dp else 4.dp
                        )
                    )
                    .background(bubbleColor)
                    .border(
                        1.dp,
                        bubbleBorder,
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isAgent) 4.dp else 18.dp,
                            bottomEnd = if (isAgent) 18.dp else 4.dp
                        )
                    )
                    .padding(14.dp)
            ) {
                if (isAgent && message.modelBadge != null) {
                    val displayName = when (message.modelBadge) {
                        "Gemma 4 (On-device)" -> "ON-DEVICE (AI CORE)"
                        "On-Device AI" -> "ON-DEVICE AI"
                        "LiteRT-LM (On-device)" -> "ON-DEVICE (LITERT)"
                        else -> message.modelBadge.uppercase(Locale.getDefault())
                    }
                    Text(
                        text = displayName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    color = textColor,
                    lineHeight = 20.sp
                )
                
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Edit affordance: user messages only, never on agent replies or the
                    // contact-picker card (which is always an agent message, so it's
                    // already excluded by the isAgent check above never reaching here).
                    if (!isAgent && onEditRequested != null) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit message",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(13.dp)
                                .clickable { onEditRequested(message) }
                        )
                    }
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        fontSize = 9.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dot1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp))
                .background(CardBackground)
                .border(1.dp, BorderColor, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TextPrimary.copy(alpha = dot1)))
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TextPrimary.copy(alpha = dot2)))
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TextPrimary.copy(alpha = dot3)))
            }
        }
    }
}

@Composable
fun ProposedPlanPrompt(
    planId: String,
    goal: String,
    stepsCount: Int,
    blockedActions: List<String> = emptyList(),
    grantableActions: Set<String> = emptySet(),
    onApprove: (Set<String>) -> Unit,
    onReject: () -> Unit
) {
    // Keyed on both: `planId` because a new plan must not inherit the previous
    // plan's ticked grants even when the two happen to block the same actions,
    // and `blockedActions` because the checkboxes are drawn from that list, so a
    // change to it would otherwise leave ticks referring to rows that are gone.
    var checkedGrants by remember(planId, blockedActions) { mutableStateOf(setOf<String>()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .border(1.dp, AccentCyan, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Plan Proposed",
                    tint = AccentCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AUTONOMOUS PLAN PROPOSED",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = AccentCyan
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Goal: \"$goal\"",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "OpenDroid has formulated a sequence of $stepsCount steps to complete this goal. Review the steps in the PLAN tab or approve below to execute.",
                fontSize = 12.sp,
                color = TextSecondary
            )
            if (blockedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "BLOCKED AUTO-RUN — these steps aren't in your allowlist:",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AccentRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                blockedActions.forEach { action ->
                    if (action in grantableActions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = action in checkedGrants,
                                onCheckedChange = { checked ->
                                    checkedGrants = if (checked) checkedGrants + action else checkedGrants - action
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentCyan)
                            )
                            Text(
                                text = "Always allow $action",
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                    } else {
                        Text(
                            text = "• $action (always asks)",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onReject,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reject", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { onApprove(checkedGrants) },
                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = DarkBackground),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Approve & Run", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FloatingOrb(
    isListening: Boolean,
    agentState: AgentState,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val colorPulse by animateColorAsState(
        targetValue = when {
            isListening -> AccentRed
            agentState is AgentState.Thinking -> AccentPurple
            agentState is AgentState.ExecutingPlan -> AccentCyan
            agentState is AgentState.Speaking -> AccentCyan
            else -> TextPrimary.copy(alpha = 0.25f)
        },
        animationSpec = tween(500),
        label = "color"
    )

    val shadowSize = if (isListening || agentState !is AgentState.Idle) pulseScale else 1f

    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(shadowSize)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(colorPulse, Color.Transparent),
                    radius = 120f
                )
            )
            .clickable { onClick() }
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colorPulse, colorPulse.copy(alpha = 0.6f))
                    )
                )
                .border(2.dp, TextPrimary.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(DarkBackground)
            )
        }
    }
}

@Composable
fun VoiceWaveform(text: String, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val heightScale1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 32f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h1"
    )
    val heightScale2 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h2"
    )
    val heightScale3 by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 40f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 50, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h3"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            modifier = Modifier.width(60.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(4.dp).height(heightScale1.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale2.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale3.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale2.dp).clip(CircleShape).background(AccentRed))
            Box(modifier = Modifier.width(4.dp).height(heightScale1.dp).clip(CircleShape).background(AccentRed))
        }
        Spacer(modifier = Modifier.width(8.dp))
        // No maxLines cap - long dictation wraps across multiple lines and the container
        // (see the input Box in ChatScreen) scrolls once it exceeds its bounded max height.
        Text(
            text = text,
            fontSize = 13.sp,
            color = TextPrimary,
            fontFamily = FontFamily.SansSerif,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ChatErrorRecoveryCard(
    error: ChatErrorUiState,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    // Countdown for rate-limited errors: while the provider's retry-after window is
    // open, the Retry button is disabled and the remaining seconds tick down here.
    val phase = error.phase
    var waitSecondsLeft by remember(phase) {
        mutableLongStateOf(
            if (phase is ChatErrorUiState.Phase.WaitingUntil) {
                ((phase.epochMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }
        )
    }
    if (phase is ChatErrorUiState.Phase.WaitingUntil) {
        LaunchedEffect(phase) {
            while (true) {
                val remainingMillis = phase.epochMillis - System.currentTimeMillis()
                waitSecondsLeft = (remainingMillis / 1000L).coerceAtLeast(0L)
                if (remainingMillis <= 0L) break
                delay(1000L)
            }
        }
    }
    val retryHeld = phase is ChatErrorUiState.Phase.Retrying ||
        (phase is ChatErrorUiState.Phase.WaitingUntil && waitSecondsLeft > 0L)
    val actionLabel = when (error.primaryAction()) {
        ChatErrorPrimaryAction.OPEN_SETTINGS -> "Open Settings"
        ChatErrorPrimaryAction.CHOOSE_PROVIDER -> "Choose provider"
        ChatErrorPrimaryAction.CHOOSE_MODEL -> "Choose model"
        ChatErrorPrimaryAction.EDIT_MESSAGE -> "Edit message"
        ChatErrorPrimaryAction.RETRY -> "Retry"
        ChatErrorPrimaryAction.NONE -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = error.title(),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            if (error.partialMessageId != null) {
                Text(
                    text = "Incomplete response",
                    color = AccentCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(text = error.guidance(), color = TextSecondary, fontSize = 13.sp)
            if (phase is ChatErrorUiState.Phase.WaitingUntil && waitSecondsLeft > 0L) {
                Text(
                    text = "Retry available in ${waitSecondsLeft}s",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actionLabel != null) {
                    Button(
                        onClick = onPrimary,
                        enabled = !(retryHeld && error.primaryAction() == ChatErrorPrimaryAction.RETRY),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TextPrimary,
                            contentColor = DarkBackground
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(actionLabel)
                    }
                }
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(if (detailsExpanded) "Hide details" else "Technical details")
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (detailsExpanded) {
                val detail = buildString {
                    append(error.category.code)
                    append(" · ")
                    append(error.provider)
                    error.httpStatus?.let { append(" · HTTP "); append(it) }
                    error.model.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                    error.redactedDetail?.toString()?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                }
                Text(
                    text = detail,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
