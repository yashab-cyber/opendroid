# OpenDroid

<p align="center">
  <img src="assets/backgroundremoved.png" alt="OpenDroid Logo" width="180px">
</p>

> **"Your Open Autonomous Android Agent"**

OpenDroid is a production-ready, autonomous, self-planning AI assistant for Android. Rather than acting as a simple chat interface, OpenDroid is a fully agentic system capable of breaking complex goals into sequential sub-tasks, executing them through direct device controls and accessibility automation, monitoring execution results, and dynamically replanning when steps fail or environment conditions change.

---

## 🌟 Key Features

- 🧠 **Autonomous Planning & Re-evaluation**: Breaks down high-level user commands (e.g. *"Check if it's going to rain, and if so, text my wife that I'll be late and set an alarm for 6 PM"*) into logical steps, executing each sequentially, verifying the outcome, and adapting the remaining steps dynamically.
- 📱 **Full Device & System Control**: Supports a wide range of native system actions including brightness adjustments, toggling Wi-Fi/Bluetooth/Flashlight, locking the screen, scheduling alarms/timers, calendar management, and currency/language translation.
- 🤖 **Accessibility Automation**: Leveraging `JarvisAccessibilityService` to click, scroll, read screens, and automate apps (e.g. sending messages on WhatsApp or finding locations in maps) when API controls are unavailable.
- 🔌 **Unified Multi-LLM Layer**: Supports 10 major LLM providers interchangeably (Claude, OpenAI, Gemini, Mistral, Groq, Ollama, OpenRouter, Together AI, Cohere, DeepSeek) with automatic fallback chaining when API limits are hit.
- 🗄️ **Multi-Tier Persistent Memory**:
  - **Working Memory**: Manages temporary context and execution variables of the current plan.
  - **Episodic Memory**: Logs logs and results of past action sequences.
  - **Semantic Memory**: Stores structured, long-term personal facts and preferences extracted via an LLM fact-mining parser.
  - **Procedural Memory**: Manages custom user-defined macro workflows.
- 🎙️ **Local Wake-Word & Voice Interface**: Integrated hands-free listening using offline wake word detection, Android's speech recognition engine, and high-fidelity Text-to-Speech (with native ElevenLabs fallback support).
- 🎨 **Premium Glassmorphic Design**: Built using Jetpack Compose with a futuristic deep navy (`#080C10`) and neon green (`#00FF88`) design system, featuring custom pulsing audio orb indicators and live latency benchmarks.

---

## 🏗️ Architecture Overview

The system is split into modular components following clean architecture principles, managed by Dagger-Hilt for dependency injection:

```
com.opendroid.ai
│
├── accessibility/     # Accessibility services & third-party app automators (WhatsApp, etc.)
├── actions/           # Command execution modules (System, Communications, Productivity, etc.)
├── core/
│   ├── agent/         # PlanManager, ReEvaluationEngine, IntentClassifier
│   ├── llm/           # Providers, factory, prompt templates, fallback logic
│   ├── memory/        # Multi-tier memory, semantic fact extractor
│   ├── service/       # Foreground OpenDroidService, BootReceiver
│   └── voice/         # Audio engines: WakeWordDetector, SpeechRecognizer, TextToSpeech
│
├── data/
│   ├── db/            # Room Database, DAOs, Entities
│   ├── models/        # Unified data models (Plan, Memory, ChatMessage, LLMConfig)
│   └── repository/    # Local repositories backed by Room & DataStore Preferences
│
├── di/                # Hilt modules (AppModule, DatabaseModule, LLMModule)
└── ui/
    ├── theme/         # Color palettes, Typography, Custom Compose styles
    ├── screens/       # Chat, Plan, Settings, Memory, Macros, History, Benchmark
    └── Navigation.kt  # Compose destinations & main bottom navigation scaffold
```

---

## 🛠️ Setup & Configuration

### Prerequisites
- **JDK 17+**
- **Android SDK 34 (Android 14)**
- **Dagger Hilt Gradle Plugin**

### Building Manually
To build the debug APK, run:
```bash
./gradlew assembleDebug
```
The compiled APK will be available under:
`app/build/outputs/apk/debug/app-debug.apk`

### Permissions Required
Upon first launching, the app will guide you through granting the following required Android permissions:
1. **Accessibility Service**: Enables automated interactions, UI navigation, and screen inspections.
2. **Write Settings (`WRITE_SETTINGS`)**: Required to toggle Bluetooth, Wi-Fi, Adjust brightness, etc.
3. **Record Audio**: Required for hands-free wake word detection and speech-to-text.
4. **Post Notifications**: Enables foreground execution state and background planning status banners.

---

## ⚙️ Configuration & Environment Variables

Through the **Settings Screen** in the app, you can configure your LLM models and credentials:
- **API Keys**: Add keys for Anthropic, OpenAI, Gemini, Groq, Mistral, OpenRouter, Together AI, Cohere, DeepSeek, or ElevenLabs.
- **Ollama Host URL**: Configure a custom server IP/Port to run model completions completely offline.
- **Synthesizer Settings**: Configure ElevenLabs voice identifiers and models.

---

## 📜 License

```
Copyright 2026 OpenDroid Contributors

Licensed under the Apache License, Version 2.5 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.5

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
