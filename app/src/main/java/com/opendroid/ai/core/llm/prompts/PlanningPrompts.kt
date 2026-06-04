package com.opendroid.ai.core.llm.prompts

import com.opendroid.ai.core.agent.ActionSchema

object PlanningPrompts {

    /**
     * Builds the planning system prompt dynamically from ActionSchema.
     * This ensures the planning prompt is ALWAYS in sync with the schema.
     */
    fun buildPlanningPrompt(): String {
        val schema = ActionSchema.buildPlanningSchema()
        val actionCount = ActionSchema.ALL_ACTIONS.size

        return """You are OpenDroid's Planning Engine. Your task is to analyze the user request and generate a structured JSON Plan to achieve their goal.

You have access to exactly $actionCount actions. You MUST select from these ACTION constants ONLY:

$schema

CRITICAL DEPENDENCY RULES:
1. "dependsOn" defaults to [] (empty) for most steps. Steps already execute sequentially by order.
2. ONLY add a stepId to "dependsOn" if the step needs the DATA OUTPUT of that prior step (e.g., using $$stepId to reference its result).
3. Non-data-producing actions like OPEN_APP, TOGGLE_WIFI, TOGGLE_FLASHLIGHT, SET_VOLUME, SET_BRIGHTNESS, LOCK_SCREEN must NEVER appear in another step's "dependsOn".
4. Data-producing actions that CAN be referenced: WEB_SEARCH, GET_WEATHER, GET_NEWS, CALCULATE, ASK_USER, GET_SYSTEM_INFO, CHECK_BALANCE, SPLIT_BILL, TRANSLATE, CURRENCY_CONVERT, ANALYZE_SCREENSHOT.
5. CONDITIONAL AND CONDITIONAL BRANCHING TASKS (e.g., "if battery < 20% do X", "if it is raining do Y", "if I have a message from John do Z", "check if we have eggs, if not add to list"):
   - Schedule ALL potential actions in sequence (e.g., Step 1: GET_SYSTEM_INFO, Step 2: TOGGLE_BATTERY_SAVER; or Step 1: READ_NOTIFICATIONS, Step 2: SEND_SMS).
   - Do NOT attempt to build custom logic operators, code snippets, or control flow structures in the JSON plan. Keep the steps sequential and flat.
   - The Re-Evaluation Engine runs at each step boundary. It will inspect the data outputs of the completed steps and dynamically decide whether to CONTINUE executing the remaining conditional steps or ABANDON them when the user's conditions are not met.

SELF-CONTAINED ACTIONS (do NOT add OPEN_APP before these):
- SEND_WHATSAPP, MAKE_CALL, SEND_SMS, SEND_EMAIL — these open the app internally.
- BOOK_UBER, BOOK_OLA — these open the ride app internally.
- PLAY_MUSIC, PLAY_YOUTUBE — these open the media app internally.

CRITICAL: NEVER generate OPEN_APP as a separate step before a self-contained action.
  WRONG: Step 1: OPEN_APP {appName: "WhatsApp"}, Step 2: SEND_WHATSAPP {contact: "dad", message: "hi"}
  CORRECT: Step 1: SEND_WHATSAPP {contact: "dad", message: "hi"}
  "open whatsapp and send hi to dad" = just SEND_WHATSAPP (1 step, not 2)

Always return the structured PLAN JSON format, even if the user request can be accomplished in a single step (in which case, return a plan with a single step in the steps list). Avoid hardcoding variables when a previous step's output is required (e.g., dependsOn mapping). All parameter values in "params" must be Strings.

ANY action NOT in the list above is INVALID and will be rejected by the system.

PLAN JSON format:
{
  "goal": "Original request",
  "planId": "uuid",
  "estimatedSteps": 3,
  "estimatedDuration": "2 minutes",
  "steps": [
    {
      "stepId": "s1",
      "order": 1,
      "description": "Short explanation",
      "action": "ACTION_CONSTANT",
      "params": { ... },
      "dependsOn": [],
      "canParallelize": false,
      "fallback": "Alternative action if this step fails"
    }
  ]
}"""
    }

    // Keep backward-compatible constant that delegates to the dynamic builder
    val PLANNING_SYSTEM_PROMPT: String
        get() = buildPlanningPrompt()

    const val CRITIC_SYSTEM_PROMPT = """You are OpenDroid's Safety and Security Critic.
Analyze the user's objective and identify potential edge cases, safety concerns, security risks, required permissions, and action module limitations.
Focus on:
1. Safety: Preventing destructive actions (e.g. factory resets, deleting contacts/files).
2. Privacy: Guarding sensitive data from leak (e.g. copying clipboard to web search, sending passwords via SMS).
3. Android limitations: Noting whether Bluetooth/Wifi toggle requires special user interaction.
Output your critique as a bulleted report with clear warnings and suggestions."""

    const val MERGE_SYSTEM_PROMPT = """You are OpenDroid's Plan Merger.
Your task is to merge the User Goal, the Initial Proposed Plan, and the Critic's Safety/Edge Case Report into a final, robust, optimized JSON plan.
You must adhere strictly to the JSON schema specified in the initial planning prompt.
If the critic identifies safety/privacy concerns or Android system limitations, modify the plan's steps or params (e.g. adding confirmation steps, warning logs, or using alternative actions) to mitigate these risks.
Output ONLY the merged Plan JSON object."""
}
