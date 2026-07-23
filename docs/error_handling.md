# Error Handling Architecture & Self-Healing Guide - OpenDroid

This document provides an exhaustive reference on how errors, exceptions, network failures, schema hallucinations, and permission denials are handled across all layers of **OpenDroid**.

---

## 1. Error Handling Philosophy & Strategy

OpenDroid operates as an autonomous agentic system executing high-privilege Android device actions. Simple exception crashing or silent error masking is unacceptable. The application adheres to four core error handling principles:

1. **No Silent Failure Masking:** Errors are caught, explicitly classified, logged to diagnostic stores, and reported back to the user or agentic feedback loops.
2. **Pre-Execution Validation & Auto-Fixing:** Syntactic and semantic errors in LLM-generated plans are caught and auto-corrected *before* dispatching actions to the operating system.
3. **Agentic Self-Healing (Re-Evaluation Engine):** Execution step failures trigger LLM re-evaluation loops to repair parameters, inject missing steps, or execute alternative fallback actions.
4. **Memory Poisoning Prevention:** Execution errors and invalid action names are logged to isolated diagnostic tables (`UnknownActionDao`) and **never** saved to semantic memory, preventing the LLM from hallucinating broken actions in future sessions.

---

## 2. Error Taxonomy & System Boundaries

```
                               ┌───────────────────────────┐
                               │   User Input / AI Goal    │
                               └─────────────┬─────────────┘
                                             │
                       ┌─────────────────────┴─────────────────────┐
                       ▼                                           ▼
          ┌───────────────────────────┐               ┌───────────────────────────┐
          │ Pre-Execution Validation  │               │   Runtime Execution       │
          │ (PlanValidator.kt)        │               │   (ActionDispatcher.kt)   │
          └────────────┬──────────────┘               └────────────┬──────────────┘
                       │ Invalid Action / Dep                      │ Action Failed / Exception
                       ▼                                           ▼
          ┌───────────────────────────┐               ┌───────────────────────────┐
          │ Auto-Fix / Re-Map Step    │               │  ActionResult.Failure /   │
          │ (ASK_USER Injection)      │               │  UnknownAction            │
          └───────────────────────────┘               └────────────┬──────────────┘
                                                                   │
                                                                   ▼
                                                      ┌───────────────────────────┐
                                                      │   Re-Evaluation Engine    │
                                                      │   (CONTINUE / MODIFY /    │
                                                      │    ABANDON)               │
                                                      └───────────────────────────┘
```

---

## 3. Action Execution Sealed Result Hierarchy (`ActionResult`)

Every action executed via [`Action.kt`](file:///workspaces/opendroid/app/src/main/java/com/opendroid/ai/actions/base/Action.kt) returns a strongly-typed sealed result defined in [`ActionResult.kt`](file:///workspaces/opendroid/app/src/main/java/com/opendroid/ai/actions/base/ActionResult.kt):

```kotlin
@Serializable
sealed class ActionResult {
    abstract val success: Boolean
    abstract val data: String?
    abstract val error: String?

    // 1. Successful execution
    @Serializable
    data class Success(val dataMap: Map<String, String> = emptyMap()) : ActionResult()

    // 2. Controlled action execution failure
    @Serializable
    data class Failure(val errorMsg: String, val fallback: String = "") : ActionResult()

    // 3. LLM hallucinated an unregistered action name
    @Serializable
    data class UnknownAction(
        val attemptedAction: String,
        val availableActions: List<String>
    ) : ActionResult()

    // 4. Action requires user parameter input (e.g. missing phone number or prompt choice)
    @Serializable
    data class NeedsInput(
        val question: String,
        val options: List<String> = emptyList(),
        val metadata: Map<String, String> = emptyMap()
    ) : ActionResult()
}
```

---

## 4. Pre-Execution Validation & Auto-Fixing Engine

Before any LLM-generated plan is dispatched for execution, it is passed through [`PlanValidator.kt`](file:///workspaces/opendroid/app/src/main/java/com/opendroid/ai/core/agent/PlanValidator.kt). This layer catches invalid action names, resolves contact aliases, and removes illegal step dependencies.

### 4.1. Unregistered Action Auto-Mapping

If the LLM generates deprecated or unmapped action names, [`PlanValidator.kt`](file:///workspaces/opendroid/app/src/main/java/com/opendroid/ai/core/agent/PlanValidator.kt) automatically rewrites the step to a valid schema target:

| Hallucinated / Unregistered Action | Auto-Fixed Action Replacement |
|:---|:---|
| `VERIFY_APP`, `SECURITY_CHECK` | `GET_SYSTEM_INFO` |
| `LAUNCH_APP` | `OPEN_APP` (with `appName` param extraction) |
| `OPEN_APP_OR_WEBSITE` (URL detected) | `SUMMARIZE_URL` (with `url` param mapping) |
| `OPEN_APP_OR_WEBSITE` (App detected) | `OPEN_APP` (with `appName` param mapping) |

### 4.2. Contact Resolution & `ASK_USER` Step Injection

When a communication action (`SEND_WHATSAPP`, `MAKE_CALL`, `SEND_SMS`) specifies a contact name rather than a phone number:
1. `PlanValidator` queries system contacts via `ContactsContract`.
2. If exact or partial contact match is found, `contact` parameter is replaced with the resolved phone number.
3. If no matching contact exists, `PlanValidator` automatically injects an `ASK_USER` step asking the user for the phone number before executing the communication step, updating step order and dependency arrays accordingly.

### 4.3. Invalid Dependency Stripping

Non-data producing steps (such as `OPEN_APP`, `TOGGLE_WIFI`, `TOGGLE_FLASHLIGHT`) are stripped from `dependsOn` arrays. Only data-producing actions (`WEB_SEARCH`, `GET_WEATHER`, `ASK_USER`, `CALCULATE`) are permitted as step dependencies.

---

## 5. Agentic Self-Healing & Re-Evaluation Engine

When a step execution returns `ActionResult.Failure` or `ActionResult.UnknownAction`, control is yielded to [`ReEvaluationEngine.kt`](file:///workspaces/opendroid/app/src/main/java/com/opendroid/ai/core/agent/ReEvaluationEngine.kt).

### 5.1. Re-Evaluation Decision Matrix

The `ReEvaluationEngine` analyzes completed steps, failure error messages, and remaining queued steps to produce a `ReEvalResult`:

| Re-Evaluation Decision | System Action Taken |
|:---|:---|
| **`CONTINUE`** | Step failure is non-fatal. Execution proceeds to the next step in the queue. |
| **`MODIFY`** | LLM regenerates remaining steps (e.g. updating parameters or inserting alternative steps). |
| **`ABANDON`** | Goal is unachievable or conditional prerequisite evaluated to FALSE. Remaining steps are cancelled. |

### 5.2. Unknown Action Replanning Engine

If a step fails due to an unregistered action name, `ReEvaluationEngine.replanAfterUnknownAction()` fetches the strict action whitelist, provides the failure context to the LLM, and requests a rewritten plan using **whitelisted actions only**.

---

## 6. Memory Poisoning Prevention Architecture

> [!IMPORTANT]
> **Critical Architectural Rule:** Execution errors, failed steps, and hallucinated action names must **NEVER** be saved to Semantic Memory (`SemanticFactEntity`).

If invalid action names or error strings are saved into semantic memory, the LLM reads them in future conversation context windows, causing **Memory Poisoning** (where the LLM continuously re-hallucinates the broken action name).

- All unknown actions and plan failures are routed exclusively to `UnknownActionDao` (`UnknownActionEntity`).
- Semantic Memory (`SemanticFactEntity`) stores only verified user facts extracted from successful conversations.

---

## 7. Network & LLM Provider Error Resilience

### 7.1. HTTP Timeout & Rate Limit Retry Interceptors

Cloud network requests (OpenAI, Anthropic, Gemini) enforce:
- Maximum 15-second connection, read, and write timeouts.
- Exponential backoff retry logic for `HTTP 429` (Rate Limited) and `5xx` server error responses.

### 7.2. Provider Failover Cascade

If an active cloud provider fails after retries, `LLMProviderFactory` catches the exception and attempts failover to secondary cloud providers or local LiteRT-LM / Ollama offline engines.

---

## 8. Background Model Downloader & File System Resilience

In [`ModelDownloadWorker.kt`](file:///workspaces/opendroid/app/src/main/java/com/opendroid/ai/core/llm/ModelDownloadWorker.kt):

- **HTTP Range Resume:** Interrupted model downloads resume from the last saved byte offset without corrupting existing disk chunks.
- **SHA-256 Checksum Validation:** Checksum mismatch deletes temporary `.tmp` files immediately and updates database state to `ModelStatus.FAILED`.
- **LiteRT C++ JNI Load Test:** Models are tested via C++ JNI instantiation before being marked `READY`. JNI load failure deletes the invalid binary and marks status as `FAILED`.

---

## 9. Cryptographic & Security Fail-Safe Policy

In [`SecurePrefs.kt`](file:///workspaces/opendroid/app/src/main/java/com/opendroid/ai/core/security/SecurePrefs.kt):

- Unrecoverable KeyStore errors (e.g., master key invalidation post-device reset) trigger preference deletion and re-creation.
- If re-creation fails, a `SecurityException` is thrown to halt execution rather than falling back to unencrypted plaintext storage.

---

## 10. UI & State Error Handling

- **ViewModel Coroutine Containment:** All asynchronous UI actions operate inside `viewModelScope` using `runCatching` blocks.
- **Visual Error Indicators:** UI screens (`ChatScreen`, `PlanScreen`) render dedicated state cards for errors, featuring dynamic retry buttons and user feedback overlays.
