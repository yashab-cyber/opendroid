package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.MacroViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacrosScreen(
    viewModel: MacroViewModel,
    modifier: Modifier = Modifier,
    onNavigateToRoutines: () -> Unit = {}
) {
    val macros by viewModel.macros.collectAsState()
    
    var isAddingMacro by remember { mutableStateOf(false) }
    var newMacroName by remember { mutableStateOf("") }
    var newMacroTrigger by remember { mutableStateOf("") }
    
    // Steps for the macro currently being built
    val macroSteps = remember { mutableStateListOf<PlanStep>() }
    
    // Step inputs
    var stepDesc by remember { mutableStateOf("") }
    var stepAction by remember { mutableStateOf("") }
    var stepParamKey by remember { mutableStateOf("") }
    var stepParamVal by remember { mutableStateOf("") }
    var stepFallback by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "MACRO ENGINE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = { isAddingMacro = !isAddingMacro }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create Macro",
                            tint = TextPrimary
                        )
                    }
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Habit & Routine Detection Entry Banner
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .clickable { onNavigateToRoutines() },
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡", fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "HABIT & ROUTINE DETECTION",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Detect repeated daily habits (Gmail, Calendar, Slack) & automate morning routines.",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "View Routines",
                            tint = AccentCyan
                        )
                    }
                }
            }

            // Expandable Macro Creation Panel
            if (isAddingMacro) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "DEFINE CUSTOM WORKFLOW MACRO",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedTextField(
                                value = newMacroName,
                                onValueChange = { newMacroName = it },
                                label = { Text("Macro Name", fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newMacroTrigger,
                                onValueChange = { newMacroTrigger = it },
                                label = { Text("Voice TriggerPhrase", fontSize = 12.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = BorderColor)
                            Spacer(modifier = Modifier.height(12.dp))

                            // Steps in custom macro
                            Text("Macro Steps Sequence (${macroSteps.size})", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            macroSteps.forEachIndexed { idx, st ->
                                Text(
                                    text = buildString {
                                        append("Step ${idx + 1}: ${st.description} [${st.action}]")
                                        if (st.fallback.isNotBlank()) append(" → fallback: ${st.fallback}")
                                    },
                                    fontSize = 11.sp,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Add step details:", fontSize = 11.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            OutlinedTextField(
                                value = stepDesc,
                                onValueChange = { stepDesc = it },
                                label = { Text("Step Description", fontSize = 11.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = stepAction,
                                onValueChange = { stepAction = it },
                                label = { Text("Action Type (e.g. system/brightness)", fontSize = 11.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = stepParamKey,
                                    onValueChange = { stepParamKey = it },
                                    label = { Text("Param Key", fontSize = 11.sp) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentCyan,
                                        unfocusedBorderColor = BorderColor,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = stepParamVal,
                                    onValueChange = { stepParamVal = it },
                                    label = { Text("Param Value", fontSize = 11.sp) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentCyan,
                                        unfocusedBorderColor = BorderColor,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = stepFallback,
                                onValueChange = { stepFallback = it },
                                label = { Text("Fallback Action (optional)", fontSize = 11.sp) },
                                supportingText = {
                                    Text("Runs once if the primary action fails.", fontSize = 10.sp)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = BorderColor,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (stepDesc.isNotBlank() && stepAction.isNotBlank()) {
                                        val params = if (stepParamKey.isNotBlank()) mapOf(stepParamKey to stepParamVal) else emptyMap()
                                        macroSteps.add(
                                            PlanStep(
                                                stepId = UUID.randomUUID().toString(),
                                                order = macroSteps.size + 1,
                                                description = stepDesc,
                                                action = stepAction,
                                                params = params,
                                                fallback = stepFallback.trim(),
                                                status = StepStatus.PENDING
                                            )
                                        )
                                        stepDesc = ""
                                        stepAction = ""
                                        stepParamKey = ""
                                        stepParamVal = ""
                                        stepFallback = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple, contentColor = TextPrimary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Add Step to List", fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        isAddingMacro = false
                                        macroSteps.clear()
                                    }
                                ) {
                                    Text("Discard", color = AccentRed)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (newMacroName.isNotBlank() && newMacroTrigger.isNotBlank() && macroSteps.isNotEmpty()) {
                                            viewModel.saveMacro(
                                                Macro(
                                                    id = UUID.randomUUID().toString(),
                                                    name = newMacroName,
                                                    trigger = newMacroTrigger,
                                                    steps = macroSteps.toList()
                                                )
                                            )
                                            newMacroName = ""
                                            newMacroTrigger = ""
                                            macroSteps.clear()
                                            isAddingMacro = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = DarkBackground),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Save Macro", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Macros List Section
            if (macros.isNotEmpty()) {
                items(macros) { mc ->
                    MacroCard(
                        macro = mc,
                        onToggle = { isEnabled -> viewModel.toggleMacro(mc.id, isEnabled) },
                        onDelete = { viewModel.deleteMacro(mc.id) }
                    )
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No custom macros declared.",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MacroCard(
    macro: Macro,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = macro.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Trigger: \"${macro.trigger}\"",
                        fontSize = 12.sp,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = macro.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextPrimary,
                        checkedTrackColor = TextPrimary.copy(alpha = 0.5f)
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = BorderColor)
            Spacer(modifier = Modifier.height(10.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${macro.steps.size} scheduled steps in sequence",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand steps",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    macro.steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(AccentCyan)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${step.description} [${step.action}]",
                                fontSize = 11.sp,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!macro.isSystem) {
                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.1f), contentColor = AccentRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Macro", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
