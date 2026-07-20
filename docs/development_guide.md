# Developer Development Guide - OpenDroid

Welcome to the OpenDroid development guide. This document serves as the technical onboarding guide for developers, contributors, and maintainers looking to extend and modify the OpenDroid agent.

---

## 1. Prerequisites & Environment Setup

To build and compile OpenDroid, ensure your workstation meets the following requirements:
* **JDK:** Version 21+ (Java SE Development Kit)
* **Android SDK:** API Level 35 (Android 15) installed
* **Gradle Tooling:** Gradle 8.10.2 (wrapped in project)
* **Kotlin Compiler:** Version 2.4.0
* **Dagger-Hilt:** Version 2.58

### Import to Android Studio
1. Open Android Studio (Iguana / Jellyfish or newer).
2. Choose **File > Open** and select the root directory of `opendroid`.
3. Allow Gradle to sync and resolve dependencies.
4. Verify that Android Studio is configured to use JDK 17 for gradle compilations (**Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK**).

---

## 2. Extending Actions (How-to Guide)

OpenDroid routes agent instructions to specific executable action classes. If you need to expose a new device capability to the agent (e.g. "Take screenshot", "Toggle NFC"), you must implement a new action class.

### Step 1: Define the Action type in the enum
First, add your new action identifier to the unified action listing (e.g., `ActionType.kt` or `Actions.kt` depending on where the list resides).

### Step 2: Implement the Action class
Create a class that implements the execution interface. Inject dependencies via Hilt.

```kotlin
package com.opendroid.ai.actions.impl

import android.content.Context
import com.opendroid.ai.actions.BaseAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ToggleNfcAction @Inject constructor(
    @ApplicationContext private val context: Context
) : BaseAction {
    override suspend fun execute(params: Map<String, String>): ActionResult {
        val enable = params["enable"]?.toBoolean() ?: true
        return try {
            // Implement system call logic here...
            ActionResult.Success("NFC successfully configured.")
        } catch (e: Exception) {
            ActionResult.Failure("Failed to modify NFC state: ${e.localizedMessage}")
        }
    }
}
```

### Step 3: Register in ActionDispatcher
Bind your new action class inside the Hilt action injection module and register it in `ActionDispatcher.kt` so the agent can route instructions to it.

---

## 3. Creating a Custom LLM Provider

If you wish to integrate a new LLM provider (e.g., Anthropic, Groq, or a custom hosting solution):

1. **Implement `LLMProvider`:**
   Implement the unified client interface:
   ```kotlin
   class CustomLLMProvider(private val okHttpClient: OkHttpClient) : LLMProvider {
       override suspend fun generateCompletion(prompt: String, systemPrompt: String?): Result<String> {
           // Execute HTTP Call
       }
       override suspend fun fetchModels(): Result<List<AIModel>> {
           // Return models
       }
   }
   ```
2. **Register in LLMModule:**
   Expose the provider in `com.opendroid.ai.di.LLMModule` or bind it dynamically based on the user's active provider selection in Datastore Preferences.

---

## 4. Debugging & Testing

### Read Developer Logs
OpenDroid records detailed agent operation and plan step processing logs directly to logcat under the tags `OpenDroidAgent` or `PlanManager`:

```bash
# Filter for agent execution logs
adb logcat -s OpenDroidAgent:D PlanManager:D ActionDispatcher:D
```

### Automated UI / Unit Testing
* **Unit Tests:** Located in `app/src/test/java/`. Run via `./gradlew test`.
* **Instrumented/UI Tests:** Located in `app/src/androidTest/java/`. Run via `./gradlew connectedAndroidTest`.
