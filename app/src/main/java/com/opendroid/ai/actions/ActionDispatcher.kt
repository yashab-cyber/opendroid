package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central action execution engine.
 *
 * Pipeline for every action:
 *   1. Normalize the raw action name
 *   2. Check internet pre-requisites
 *   3. Validate against ActionSchema (with default param injection)
 *   4. If not in schema → resolve via ActionAutoMapper (alias + semantic)
 *   5. Execute the resolved handler
 *   6. Log all resolutions for analytics
 *
 * NEVER crashes on unknown actions — always returns graceful failure.
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val systemActions: SystemActions,
    private val communicationActions: CommunicationActions,
    private val calendarActions: CalendarActions,
    private val transportActions: TransportActions,
    private val informationActions: InformationActions,
    private val mediaActions: MediaActions,
    private val foodShoppingActions: FoodShoppingActions,
    private val smartHomeActions: SmartHomeActions,
    private val financeActions: FinanceActions,
    private val macroActions: MacroActions,
    private val advancedControlActions: AdvancedControlActions,
    private val notificationActions: NotificationActions,
    private val autoMapper: ActionAutoMapper,
    private val unknownActionDao: UnknownActionDao,
    private val deviceStateProvider: DeviceStateProvider
) {

    companion object {
        private const val TAG = "ActionDispatcher"

        // Actions that require internet connectivity
        private val internetRequiredActions = setOf(
            "WEB_SEARCH",
            "GET_WEATHER",
            "GET_NEWS",
            "OPEN_BROWSER",
            "OPEN_URL",
            "BOOK_UBER",
            "BOOK_OLA",
            "GET_DIRECTIONS",
            "CURRENCY_CONVERT",
            "TRANSLATE",
            "PLAY_YOUTUBE",
            "CHECK_STOCK",
            "SUMMARIZE_URL",
            "FACT_CHECK"
        )
    }

    // ── Centralized Action Registry ─────────────────────────────────
    private val actionsMap: Map<String, Action> = buildMap {
        putAll(systemActions.getActions().associateBy { it.name })
        putAll(communicationActions.getActions().associateBy { it.name })
        putAll(calendarActions.getActions().associateBy { it.name })
        putAll(transportActions.getActions().associateBy { it.name })
        putAll(informationActions.getActions().associateBy { it.name })
        putAll(mediaActions.getActions().associateBy { it.name })
        putAll(foodShoppingActions.getActions().associateBy { it.name })
        putAll(smartHomeActions.getActions().associateBy { it.name })
        putAll(financeActions.getActions().associateBy { it.name })
        putAll(macroActions.getActions().associateBy { it.name })
        putAll(advancedControlActions.getActions().associateBy { it.name })
        putAll(notificationActions.getActions().associateBy { it.name })
    }

    fun hasAction(actionName: String): Boolean =
        actionsMap.containsKey(actionName) || actionsMap.containsKey(autoMapper.normalizeActionName(actionName))

    fun isRegistered(actionName: String): Boolean = hasAction(actionName)

    fun getAllRegisteredActions(): List<String> = actionsMap.keys.toList()

    fun getActionCount(): Int = actionsMap.size

    // ════════════════════════════════════════════════════════════════
    //  EXECUTE — full resolution + execution pipeline
    // ════════════════════════════════════════════════════════════════

    suspend fun execute(actionName: String, params: Map<String, String>, context: Context): ActionResult {

        // ── STEP 0: Normalize the raw action name ──
        val normalized = autoMapper.normalizeActionName(actionName)

        Log.d(TAG, "╔══ Action Execution Pipeline ══════════")
        Log.d(TAG, "║ Raw input:    $actionName")
        Log.d(TAG, "║ Normalized:   $normalized")

        // ── STEP 1: Internet pre-check ──
        if (internetRequiredActions.contains(normalized)) {
            if (!deviceStateProvider.isInternetAvailable()) {
                Log.d(TAG, "║ BLOCKED — no internet for $normalized")
                Log.d(TAG, "╚═══════════════════════════════════════")
                return ActionResult.Failure(
                    errorMsg = "No internet connection available",
                    fallback = "Connect to WiFi or mobile data to use $normalized"
                )
            }
        }

        // ── STEP 2: Try direct schema validation + execution ──
        val directResult = trySchemaExecution(normalized, params, context)
        if (directResult != null) {
            Log.d(TAG, "║ Resolved via: SCHEMA_DIRECT")
            Log.d(TAG, "║ Final action: $normalized")
            Log.d(TAG, "╚═══════════════════════════════════════")
            return directResult
        }

        // ── STEP 3: Resolve via ActionAutoMapper (alias + semantic) ──
        val mapping = autoMapper.mapAction(
            action = actionName,
            params = params,
            registeredActions = actionsMap.keys
        )

        // Handle SKIP
        if (mapping.mappedAction == null && mapping.wasMapped) {
            Log.d(TAG, "║ Resolved via: SKIP (hallucinated step)")
            Log.d(TAG, "╚═══════════════════════════════════════")
            logUnknownAction(actionName, "AUTO_FIXED", wasAutoFixed = true, fixedWith = "SKIP")
            return ActionResult.Success(
                dataMap = mapOf(
                    "message" to "Step skipped (unnecessary)",
                    "skipped" to "true"
                )
            )
        }

        // Handle successful mapping
        if (mapping.mappedAction != null && mapping.wasMapped) {
            val finalAction = mapping.mappedAction
            val finalParams = mapping.mappedParams

            Log.d(TAG, "║ Resolved via: ${if (actionName != finalAction) "ALIAS/SEMANTIC" else "DIRECT"}")
            Log.d(TAG, "║ Final action: $finalAction")
            Log.d(TAG, "║ Mapped:       $actionName → $finalAction")

            logUnknownAction(actionName, "AUTO_FIXED", wasAutoFixed = true, fixedWith = finalAction)

            // Internet check on the RESOLVED action
            if (internetRequiredActions.contains(finalAction) && !deviceStateProvider.isInternetAvailable()) {
                Log.d(TAG, "║ BLOCKED — no internet for $finalAction")
                Log.d(TAG, "╚═══════════════════════════════════════")
                return ActionResult.Failure(
                    errorMsg = "No internet connection available",
                    fallback = "Connect to WiFi or mobile data to use $finalAction"
                )
            }

            // Try schema execution on the resolved action
            val resolvedResult = trySchemaExecution(finalAction, finalParams, context)
            if (resolvedResult != null) {
                Log.d(TAG, "╚═══════════════════════════════════════")
                return resolvedResult
            }

            // Direct handler execution (for non-schema actions like CHAT)
            val handler = actionsMap[finalAction]
            if (handler != null) {
                Log.d(TAG, "║ Executing:    $finalAction (direct handler)")
                Log.d(TAG, "╚═══════════════════════════════════════")
                return safeExecute(handler, finalParams, context, finalAction)
            }
        }

        // Handle unmapped passthrough (was already valid)
        if (mapping.mappedAction != null && !mapping.wasMapped) {
            val handler = actionsMap[mapping.mappedAction]
            if (handler != null) {
                Log.d(TAG, "║ Resolved via: PASSTHROUGH")
                Log.d(TAG, "║ Final action: ${mapping.mappedAction}")
                Log.d(TAG, "╚═══════════════════════════════════════")
                return safeExecute(handler, mapping.mappedParams, context, mapping.mappedAction)
            }
        }

        // ── STEP 4: Truly unknown — graceful failure ──
        Log.e(TAG, "║ FAILED — no handler for: $actionName (normalized: $normalized)")
        Log.e(TAG, "║ Closest:     ${suggestClosest(normalized)}")
        Log.e(TAG, "╚═══════════════════════════════════════")

        logUnknownAction(actionName, "FAILED", wasAutoFixed = false, fixedWith = null)

        return ActionResult.UnknownAction(
            attemptedAction = actionName,
            availableActions = ActionSchema.getAllActionNames()
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  Schema-based execution (validates + applies defaults)
    // ════════════════════════════════════════════════════════════════

    private suspend fun trySchemaExecution(
        actionName: String,
        params: Map<String, String>,
        context: Context
    ): ActionResult? {
        val (validation, enrichedParams) = ActionSchema.validateParams(actionName, params)

        return when (validation) {
            is ActionSchema.ValidationResult.Valid -> {
                val handler = actionsMap[actionName] ?: return null
                val stringParams = enrichedParams.mapValues { it.value.toString() }
                safeExecute(handler, stringParams, context, actionName)
            }

            is ActionSchema.ValidationResult.MissingParams -> {
                val definition = ActionSchema.getAction(actionName)
                val allHaveDefaults = validation.params.all { paramName ->
                    definition?.params?.find { it.name == paramName }?.defaultValue != null
                }

                if (allHaveDefaults) {
                    val handler = actionsMap[actionName] ?: return null
                    val withDefaults = ActionSchema.applyDefaults(actionName, params)
                    val stringParams = withDefaults.mapValues { it.value.toString() }
                    safeExecute(handler, stringParams, context, actionName)
                } else {
                    val firstMissing = validation.params.first()
                    val paramDef = definition?.params?.find { it.name == firstMissing }
                    Log.d(TAG, "║ Missing:      $firstMissing for $actionName")
                    ActionResult.NeedsInput(
                        question = "I need the $firstMissing to complete this. ${paramDef?.description ?: ""}",
                        options = paramDef?.enumValues ?: emptyList(),
                        metadata = mapOf("param" to firstMissing)
                    )
                }
            }

            is ActionSchema.ValidationResult.InvalidAction -> null
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Safe execution wrapper
    // ════════════════════════════════════════════════════════════════

    private suspend fun safeExecute(
        handler: Action,
        params: Map<String, String>,
        context: Context,
        actionName: String
    ): ActionResult {
        return try {
            handler.execute(params, context)
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed for $actionName: ${e.message}")
            ActionResult.Failure(
                errorMsg = e.message ?: "Execution failed",
                fallback = "Try alternative approach"
            )
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Utilities
    // ════════════════════════════════════════════════════════════════

    private fun suggestClosest(normalized: String): String {
        val allActions = ActionSchema.getAllActionNames()
        val words = normalized.split("_")
        return allActions
            .maxByOrNull { action -> words.count { word -> action.contains(word) } }
            ?: "CHAT"
    }

    private suspend fun logUnknownAction(
        attemptedAction: String,
        fixStatus: String,
        wasAutoFixed: Boolean,
        fixedWith: String?
    ) {
        try {
            unknownActionDao.insertUnknownAction(
                UnknownActionEntity(
                    attemptedAction = attemptedAction,
                    goal = "",
                    timestamp = System.currentTimeMillis(),
                    fixStatus = fixStatus,
                    wasAutoFixed = wasAutoFixed,
                    fixedWith = fixedWith
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log unknown action: ${e.message}")
        }
    }
}
