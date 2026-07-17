# OpenDroid Releases

This document tracks release updates, changelogs, and binary verification checksums for the OpenDroid project.

---

## v1.0.1 — On-Device Model Management & Theme Update (Re-release)

### 🔄 Qwen 2.5 & Gemma 4 RAM Stability Update (July 14, 2026)
*   **Qwen 2.5 Verification Hash Fix**: Corrected the SHA-256 hash of the LiteRT-LM Qwen 2.5 0.5B-it model specification in the registry to prevent the download manager from reporting it as corrupt.
*   **On-Device RAM Compatibility Guard**: Implemented dynamic device RAM checks using `ActivityManager` to check total system RAM before downloading, importing, or initializing large models (like Gemma 4 E2B and E4B). Large models are safely blocked with a clear warning if the device has insufficient memory, preventing silent OS OOM crashes.
*   **Application Heap Optimization**: Enabled `android:largeHeap="true"` in the manifest to request a larger system memory budget.

### 🔄 Model Management & Secure Authentication Update (July 13, 2026)
*   **On-Demand Model Downloader & Manager**: Created a complete lifecycle manager (`ModelManager` / `ModelRepository`) that supports downloading on-device LiteRT-LM models in the background via WorkManager, pausing, resuming, or canceling downloads, and verifying integrity.
*   **Hugging Face Access Token Authentication**: Added secure token entry (masked password field with toggle, paste, and clear buttons) in Settings. Token is verified against HF `whoami-v2` API and stored securely using AES-256 via `EncryptedSharedPreferences`.
*   **Gated Model Gating**: Prompts user with a details dialog if they try to download a gated model (e.g. Gemma 3/4) without configuring a token.
*   **Diagnostics and Error Page Integration**: Dynamically displays network speed (MB/s), downloaded sizes, and ETA calculations during transfer. Displays exact error causes (unauthorized token, network offline, 404) and shows a quick-link "Open Model Page" button to let users easily accept gated repository license terms on failure.
*   **Integrity and JNI Loading Verifications**: Before marking a model as ready, the download worker validates the file size, checks the SHA-256 hash (if available), and attempts to load/initialize the model via the LiteRT C++ library to ensure compatibility and prevent archive errors.
*   **Offline Local Model Import**: Added direct local file selection support to import custom `.task` and `.litertlm` files, copy them to sandboxed app directories, run JNI engine compatibility tests, and register them as Ready.
*   **Dynamic Progress Tracking & Speed Indicator UI**: Replaced the static status placeholders in Settings with an interactive card for each LiteRT-LM model. Displays live progress percentage, download speed, ETA, and progress bar with pause/resume/cancel buttons.
*   **Automated Storage Cleanup**: Implemented on-device storage checks showing total/free device space and model space usage, plus a "Delete Unused Models" option to prune inactive models.
*   **LiteRT Runtime Caching**: Upgraded `LiteRTLMProvider` to cache the `LlmInference` engine across prompts instead of reinstantiating it every time, enabling seamless switching and sub-millisecond execution.
*   **Persistent Room State & Migrations**: Added the `models` table (`ModelEntity`) and `ModelDao` to track progress and status Reactively via Flow, with a safe database `MIGRATION_4_5` migration.

### 🔄 Re-release Updates (July 12, 2026)
*   **Gemma 3n Multimodal Support**: Added support for the Google on-device Gemma 3n Multimodal model alongside Gemma 4, utilizing the upgraded ML Kit GenAI Prompt API.
*   **Dual Model Status Check**: Upgraded the on-device AI card in Settings to display individual status indicators (Available, Download Needed, Downloading, or Unsupported) and separate download triggers for both Gemma 4 and Gemma 3n Multimodal.
*   **Toolchain Upgrades**: Upgraded Kotlin compiler to `2.4.0`, Hilt compiler/plugin to `2.58`, and Room compiler to `2.8.4` to support modern Kotlin 2.4 metadata compilation.
*   **Settings Provider Restored**: Resolved a bug introduced during Gemma 4 integration where cloud providers (such as OpenRouter, Copilot API, DeepSeek, and Together AI) were incorrectly omitted from the active provider selection dropdown.

### 🤖 On-Device Gemma 4 Integration
*   **Google ML Kit GenAI Prompt API**: Complemented the offline LLM providers with Google's on-device Gemma 4 (Gemini Nano) running via Android AI Core (AICore).
*   **AI Core Status & Model Downloader**: Added a real-time AICore capability checker and downloader card in Settings to monitor model status (available, downloading, or unsupported) and trigger downloads directly from the UI.
*   **Structured Tool Calling & Streaming**: Supports native streaming of responses and maps available actions (like toggle flashlight, take screenshot, lock phone) to structured JSON output parsing to perform autonomous device actions.
*   **Build Toolchain Upgrades**: Upgraded the project build configurations to Kotlin 2.0.21 and Dagger/Hilt 2.51.1 to resolve Room/Kapt annotation processor metadata incompatibilities.
*   **Ollama Preservation**: Retained full backward compatibility for Ollama as an optional offline provider.

### 🐛 Bug Fixes & Improvements
*   **Ollama Host & Endpoint Normalization**: Corrected `OllamaProvider` to read from the dedicated `ollamaUrl` config field rather than ignoring it. Implemented automatic URL normalization to prepend `http://` and remove trailing slashes for Ollama, Copilot, and Custom OpenAI Compatible endpoints to support formats like `127.0.0.1:11434` or `localhost`.
*   **Settings Screen Race Condition & Saving Debounce**: Fixed a critical race condition where active keystroke inputs in Settings (API keys, URLs, etc.) were overwritten by background model-cache and latency benchmark updates before they could save. Also reduced saving debounce delays from 1000ms to 500ms for faster, more responsive updates.
*   **Auto-Reply Loop Prevention**: Tracks recently auto-replied contacts with a 60-second cooldown window in `AutoReplyEngine` to suppress bounceback notifications. Also ignores self-sent notifications (e.g. WhatsApp notifications starting with `"You:"`).
*   **Intent Classifier Complexity Heuristics**: Added a fast-path whitelist of single-intent commands (e.g. `"set brightness to 50"`, `"set volume to 70"`) in `IntentClassifier` to classify them as `SIMPLE` instead of `MEDIUM`. This prevents them from bypassing the local `AliasResolver` and causing LLM hallucinations.
*   **Missing Parameter Prompt Loop**: Fully supports prompting the user for missing required action parameters (e.g. `"to"`, `"subject"`, and `"body"` for `SEND_EMAIL`) sequentially via chat and re-executing actions upon receipt.

### 🔔 Notification Intelligence & Auto-Reply
*   **NotificationListenerService**: Intercepts all system notifications in real-time, persists them to a local Room database for analysis and recall.
*   **AI Auto-Reply Engine**: Automatically generates contextual replies for WhatsApp, SMS, and Email using the active LLM provider.
    *   Configurable 1–60 minute reply delay (default: 15 minutes).
    *   Per-app toggles (WhatsApp, SMS, Email) and global master toggle.
    *   Rate-limiting (max replies per contact per hour).
    *   Contact blacklist/whitelist support.
    *   Custom reply tone/style prompt.
*   **Reply Dispatcher**: Dispatches replies via Android `RemoteInput` (WhatsApp inline reply) and `SmsManager` (SMS).
*   **Pattern Learning**: `NotificationIntelligence` analyzes communication patterns (top contacts, peak hours, app usage) and stores them as semantic memories for adaptive agent behavior.
*   **New Actions**: `READ_NOTIFICATIONS` and `AUTO_REPLY_TOGGLE` added to ActionSchema, accessible via natural language ("read my notifications", "turn on auto reply").

### 🎨 Light & Dark Theme
*   **Dynamic Theme System**: Added `OpenDroidColors` palette with `CompositionLocal` provider for runtime theme switching.
*   **Light Mode**: Clean, GitHub-inspired light palette with proper contrast and readability.
*   **Dark Mode**: Existing dark theme preserved as default.
*   **Live Toggle**: Settings → Planning & Automation → Dark/Light Mode switch. Changes apply instantly without restart.
*   **Status Bar Adaptation**: Status bar and navigation bar icons automatically adjust for light/dark appearance.

### 📱 New UI Screens
*   **Auto-Reply Settings Screen**: Full configuration UI with toggles, delay slider, rate limit, and custom tone prompt.
*   **Notification History Screen**: View all captured notifications with filter chips (All/Message/Email/Social/Replied), stats dashboard, and auto-reply log.
*   **Settings Navigation**: Two new cards in Settings for "Auto-Reply Settings" and "Notification History".

### 🛠️ Technical Changes
*   **Database**: Room migration v2→v3 adding `notifications` table.
*   **DI**: `NotificationDao`, `AutoReplyEngine`, `NotificationIntelligence`, `NotificationActions` registered in Hilt.
*   **Manifest**: Registered `OpenDroidNotificationListener` service with `BIND_NOTIFICATION_LISTENER_SERVICE` permission.
*   **MemoryManager**: Now includes notification context and learned communication patterns in LLM context window.
*   **ActionDispatcher**: Registered `NotificationActions` (READ_NOTIFICATIONS, AUTO_REPLY_TOGGLE).

### 📦 Release Assets
*   **`app-debug.apk`** — Debug build APK (for testing and logging).
*   **`app-release.apk`** — Signed production APK.
*   **`app-release.aab`** — Signed Android App Bundle.

### 🔑 Build Configuration
*   **Package**: `com.opendroid.ai`
*   **Version Code**: 2
*   **Version Name**: 1.0.1
*   **Min SDK**: 26 (Android 8.0)
*   **Target SDK**: 35 (Android 15)

---

## v1.0.0 — Production Release

First official production release of OpenDroid, targeting Google Play Store, Amazon Appstore, Samsung Galaxy Store, and other Android app marketplaces.

### 🚀 Key Features

#### 🤖 Multi-Provider LLM Agent
*   Supports **11 LLM providers**: OpenAI, Claude, Gemini, Mistral, DeepSeek, Groq, Cohere, Together AI, OpenRouter, Ollama (local), and Copilot.
*   Autonomous multi-step task planning with schema-enforced action execution.
*   Real-time plan visualization and re-evaluation engine.

#### 📸 Multimodal Vision Engine & Screenshot Fallback
*   Integrated **`ANALYZE_SCREENSHOT`** to capture active layouts.
*   **Dual-Tier fallback framework**: hardware screen capture → layout text-scraping fallback.
*   Guides the user with clear instructions to re-enable accessibility services if both methods fail.

#### 🛡️ Intent Safeguards & Compound Phrase Guard
*   **AliasResolver Guard**: word-guarding to prevent partial alias matching.
*   **ActionSchema enforcement**: hardcoded action schema system eliminates LLM action hallucinations.

#### 📞 Hardened Call & SMS Intents (Zero-Refusal Policies)
*   **`SEND_SMS` Fallback**: carrier sending → SMS composer intent fallback.
*   **`MAKE_CALL` Fallback**: direct dialing → dialer screen fallback.
*   **Contact Resolver Safety**: informative errors when contacts not found.

#### 🔦 Device Control
*   Flashlight toggle with hardware state tracking via `TorchCallback`.
*   Bluetooth, WiFi, brightness, volume, and Do Not Disturb controls.
*   Alarm, timer, reminder, and calendar event management.

#### 🏠 Smart Home & Transport
*   Smart home device control (lights, thermostat, door locks).
*   Ride booking (Uber, Ola) and navigation/directions.

#### 🧠 Memory & Macros
*   Persistent memory system for learning user preferences.
*   Macro recording and scheduled execution.

#### 🔐 Security
*   Encrypted API key storage using AndroidX Security Crypto.
*   Scoped network security — cleartext HTTP restricted to localhost only.
*   Backup exclusion for encrypted preferences.

### 📦 Release Assets
*   **`app-release.apk`** — Signed production APK (for sideloading and non-Play stores).
*   **`app-release.aab`** — Signed Android App Bundle (for Google Play Store upload).

### 🔑 Build Configuration
*   **Package**: `com.opendroid.ai`
*   **Version Code**: 1
*   **Version Name**: 1.0.0
*   **Min SDK**: 26 (Android 8.0)
*   **Target SDK**: 34 (Android 14)
*   **R8 minification**: Enabled
*   **Resource shrinking**: Enabled
*   **Signing**: APK Signature Scheme v2
