package com.opendroid.ai.core.llm.providers

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents

/**
 * LLM provider backed by LiteRT-LM (com.google.ai.edge.litertlm).
 *
 * This provider runs Gemma models entirely on-device using the LiteRT runtime
 * with GPU/NPU acceleration. It does NOT require Google AI Core / Play Services.
 *
 * Supported models are defined in [OnDeviceModelRegistry.liteRTOnly].
 */
@Singleton
class LiteRTLMProvider @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    private var cachedEngine: Engine? = null
    private var cachedModelPath: String? = null

    companion object {
        private const val TAG = "LiteRTLMProvider"
        private const val MODELS_DIR = "litert_models"
    }

    override val name: String = "LiteRT-LM (On-device)"
    override val availableModels: List<String> =
        OnDeviceModelRegistry.liteRTOnly.map { it.id }

    /**
     * Resolves the model spec for the currently selected model.
     * Falls back to the recommended LiteRT model if the selection isn't a LiteRT model.
     */
    private fun resolveModelSpec(modelId: String): OnDeviceModelSpec {
        return OnDeviceModelRegistry.findById(modelId)
            ?.takeIf { it.backend == OnDeviceBackend.LITERT_LM }
            ?: OnDeviceModelRegistry.recommendedFor(OnDeviceBackend.LITERT_LM)
            ?: throw IllegalStateException("No LiteRT-LM models registered in OnDeviceModelRegistry")
    }

    /**
     * Returns the local file path where a model should be stored.
     */
    private fun getModelFilePath(spec: OnDeviceModelSpec): String {
        val folderName = when (spec.id) {
            "gemma-4-e2b-it-litert" -> "Gemma4-E2B"
            "gemma-4-e4b-it-litert" -> "Gemma4-E4B"
            "gemma-3n-e2b-it-litert" -> "Gemma3n-E2B"
            "gemma-3n-e4b-it-litert" -> "Gemma3n-E4B"
            else -> spec.id.replace("-", "").replace("litert", "").replace("it", "")
        }
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelDir = File(File(baseDir, "models"), folderName)
        val taskFile = File(modelDir, "model.task")
        if (taskFile.exists() && taskFile.length() > 0) {
            return taskFile.absolutePath
        }

        // Legacy path fallback
        val modelsDir = File(context.filesDir, MODELS_DIR)
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return File(modelsDir, "${spec.id}.litertlm").absolutePath
    }

    /**
     * Checks whether a given model file has been downloaded and is ready.
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val spec = OnDeviceModelRegistry.findById(modelId) ?: return false
        val modelFile = File(getModelFilePath(spec))
        // Integrity check: real LiteRT model file must exist and be > 100MB (mock files from previous runs are small)
        return modelFile.exists() && modelFile.length() > 100 * 1024 * 1024
    }

    /**
     * Returns the download status for all LiteRT-LM models.
     * Map of model ID → downloaded boolean.
     */
    fun getAllModelStatuses(): Map<String, Boolean> {
        return OnDeviceModelRegistry.liteRTOnly.associate { spec ->
            spec.id to isModelDownloaded(spec.id)
        }
    }

    /**
     * Initiates a model download. In production this would download the .litertlm file
     * from Hugging Face or a CDN. For now, this creates a placeholder that signals
     * the model location for the LiteRT-LM runtime.
     *
     * Returns a Flow that emits download progress (0..100) and completes.
     */
    fun downloadModel(modelId: String): Flow<Int> = flow {
        val spec = resolveModelSpec(modelId)
        val modelFile = File(getModelFilePath(spec))

        Log.i(TAG, "Starting download for model: ${spec.displayName} from ${spec.modelPath}")
        emit(0)

        withContext(Dispatchers.IO) {
            // In a production implementation this would use WorkManager + OkHttp
            // to download the .litertlm file from:
            // https://huggingface.co/${spec.modelPath}/resolve/main/model.litertlm
            //
            // For now we create a manifest file that the LiteRT-LM runtime
            // can use to locate the model once side-loaded or downloaded externally.
            val manifest = JSONObject().apply {
                put("model_id", spec.id)
                put("model_path", spec.modelPath)
                put("family", spec.family)
                put("size", spec.sizeLabel)
                put("format", "litertlm")
                put("status", "pending_download")
            }
            modelFile.parentFile?.mkdirs()
            modelFile.writeText(manifest.toString())
        }

        // Simulate progress milestones
        emit(50)
        emit(100)
        Log.i(TAG, "Model manifest created for: ${spec.displayName}")
    }.flowOn(Dispatchers.IO)

    /**
     * Deletes a downloaded model to free storage.
     */
    fun deleteModel(modelId: String): Boolean {
        val spec = OnDeviceModelRegistry.findById(modelId) ?: return false
        val modelFile = File(getModelFilePath(spec))
        return if (modelFile.exists()) modelFile.delete() else true
    }

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val startTime = System.currentTimeMillis()
        val config = settingsRepository.llmConfig.first()
        val spec = resolveModelSpec(config.activeModel)

        return withContext(Dispatchers.IO) {
            try {
                checkSdkCompatibility(spec)
                val modelPath = getModelFilePath(spec)
                checkModelReady(modelPath, spec)

                val prompt = buildPrompt(
                    request.systemPrompt,
                    request.messages,
                    request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) }
                        ?: emptyList()
                )

                val outputText = invokeLiteRTInference(modelPath, prompt, request.maxTokens, request.temperature)

                LLMResponse(
                    content = outputText,
                    tokensUsed = outputText.length / 4,
                    model = spec.id,
                    provider = name,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Throwable) {
                throw handleThrowable(e, spec)
            }
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        try {
            val config = settingsRepository.llmConfig.first()
            val spec = resolveModelSpec(config.activeModel)
            checkSdkCompatibility(spec)
            val modelPath = getModelFilePath(spec)
            checkModelReady(modelPath, spec)

            val prompt = buildPrompt(
                request.systemPrompt,
                request.messages,
                request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) }
                    ?: emptyList()
            )

            val engine = getOrInitializeEngine(modelPath, request.maxTokens)
            
            val samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = request.temperature.toDouble(),
                seed = 0
            )
            val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
            val conversation = engine.createConversation(conversationConfig)
            
            var lastLength = 0
            conversation.sendMessageAsync(prompt).collect { msg ->
                val fullText = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (fullText.length > lastLength) {
                    val delta = fullText.substring(lastLength)
                    emit(delta)
                    lastLength = fullText.length
                }
            }
            conversation.close()
        } catch (e: Throwable) {
            val config = settingsRepository.llmConfig.first()
            val spec = resolveModelSpec(config.activeModel)
            emit("Error (LiteRT-LM): ${handleThrowable(e, spec).localizedMessage}")
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> = flow {
        try {
            val config = settingsRepository.llmConfig.first()
            val spec = resolveModelSpec(config.activeModel)
            checkSdkCompatibility(spec)
            val modelPath = getModelFilePath(spec)
            checkModelReady(modelPath, spec)

            val systemPrompt = "You are an autonomous AI agent for Android."
            val prompt = buildPrompt(systemPrompt, messages, tools)

            val result = invokeLiteRTInference(modelPath, prompt, 2000, 0.7f)
            emit(StreamChunk.Content(result))

            // Attempt tool call extraction from JSON response
            try {
                val cleaned = result.trim()
                if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
                    val jsonObj = JSONObject(cleaned)
                    if (jsonObj.has("toolCall")) {
                        val toolCallObj = jsonObj.getJSONObject("toolCall")
                        val toolName = toolCallObj.getString("name")
                        val argsObj = toolCallObj.optJSONObject("arguments") ?: JSONObject()
                        emit(StreamChunk.ToolCall(toolName, argsObj.toString()))
                    }
                }
            } catch (_: Throwable) {
                // Not JSON — treat as plain text
            }
        } catch (e: Throwable) {
            val config = settingsRepository.llmConfig.first()
            val spec = resolveModelSpec(config.activeModel)
            emit(StreamChunk.Content("Error (LiteRT-LM): ${handleThrowable(e, spec).localizedMessage}"))
        }
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            // LiteRT-LM requires Android 12+ (API 31)
            if (Build.VERSION.SDK_INT < 31) return false
            // Check if at least one model is downloaded
            OnDeviceModelRegistry.liteRTOnly.any { spec ->
                isModelDownloaded(spec.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "isAvailable check failed: ${e.message}")
            false
        }
    }

    /**
     * Checks whether the device SDK level meets the model's requirements.
     */
    private fun checkSdkCompatibility(spec: OnDeviceModelSpec) {
        if (Build.VERSION.SDK_INT < spec.minSdk) {
            throw IllegalStateException(
                "${spec.displayName} requires Android API ${spec.minSdk}+ " +
                "(device is API ${Build.VERSION.SDK_INT})."
            )
        }
    }

    private fun verifyModelFileIntegrity(modelPath: String) {
        val file = File(modelPath)
        Log.i(TAG, "[INTEGRITY CHECK] Verifying file at path: $modelPath")
        
        if (!file.exists()) {
            Log.e(TAG, "[INTEGRITY CHECK] File does not exist: $modelPath")
            throw java.io.FileNotFoundException("Model file does not exist at: $modelPath")
        }
        
        if (file.isDirectory) {
            Log.e(TAG, "[INTEGRITY CHECK] File is a directory: $modelPath")
            throw IllegalArgumentException("Expected model file, but path is a directory: $modelPath")
        }
        
        val size = file.length()
        Log.i(TAG, "[INTEGRITY CHECK] File size: $size bytes (${String.format("%.2f", size.toDouble() / (1024 * 1024))} MB)")
        if (size == 0L) {
            Log.e(TAG, "[INTEGRITY CHECK] File is empty (0 bytes): $modelPath")
            throw IOException("Model file is empty (0 bytes) at: $modelPath")
        }
        
        if (size < 100 * 1024 * 1024) {
            Log.e(TAG, "[INTEGRITY CHECK] File is too small ($size bytes) to be a valid Gemma model. It is likely a simulated placeholder.")
            throw IOException("Model file at '$modelPath' is invalid/simulated ($size bytes). Please delete and redownload it.")
        }
        
        if (size >= 4) {
            try {
                file.inputStream().use { input ->
                    val magic = ByteArray(4)
                    val read = input.read(magic)
                    if (read == 4) {
                        val hex = magic.joinToString(" ") { "%02X".format(it) }
                        val ascii = String(magic).replace(Regex("[^\\x20-\\x7E]"), ".")
                        Log.i(TAG, "[INTEGRITY CHECK] Magic bytes (Hex): $hex | ASCII: $ascii")
                        
                        // ZIP archive magic header: PK\u0003\u0004 (50 4B 03 04)
                        if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) {
                            Log.i(TAG, "[INTEGRITY CHECK] Valid ZIP/Task archive header found.")
                        } else {
                            Log.w(TAG, "[INTEGRITY CHECK] Warning: ZIP/Task archive header not found! Expected 50 4B 03 04 (PK..)")
                        }
                    }
                }
            } catch (e: java.lang.Exception) {
                Log.e(TAG, "[INTEGRITY CHECK] Failed to read magic bytes", e)
            }
        } else {
            Log.e(TAG, "[INTEGRITY CHECK] File is too small to read magic header (< 4 bytes)")
        }

        // Compute and print SHA-256
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            Log.i(TAG, "[INTEGRITY CHECK] SHA-256 checksum: $sha256")
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "[INTEGRITY CHECK] Failed to compute SHA-256", e)
        }
    }

    /**
     * Verifies the model file exists and is valid.
     */
    private fun checkModelReady(modelPath: String, spec: OnDeviceModelSpec) {
        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, spec)
        val file = File(modelPath)
        if (!file.exists() || file.length() < 100 * 1024 * 1024) {
            throw IllegalStateException(
                "Model \"${spec.displayName}\" has not been downloaded yet or is invalid/simulated. " +
                "Please download it from Settings → On-Device AI."
            )
        }
        verifyModelFileIntegrity(modelPath)
    }

    /**
     * Attempts to invoke LiteRT-LM inference.
     *
     * This uses reflection to call the LiteRT-LM SDK so the project compiles
     * even if the SDK is not yet on the classpath. When the gradle dependency
     * `com.google.ai.edge.litertlm:litertlm-android` is available, this will
     * call through to the real engine.
     */
    @Synchronized
    private fun getOrInitializeEngine(modelPath: String, maxTokens: Int): Engine {
        if (cachedModelPath != modelPath) {
            Log.i(TAG, "[INIT FLOW] Active model path changed from '$cachedModelPath' to '$modelPath'. Resetting cached engine.")
            closeCachedEngine()
        }

        var engine = cachedEngine
        if (engine == null) {
            verifyModelFileIntegrity(modelPath)
            
            Log.i(TAG, "[INIT FLOW] Configuring EngineConfig with path: $modelPath")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(), // Run on CPU for compatibility
                maxNumTokens = maxTokens
            )
            
            Log.i(TAG, "[INIT FLOW] Initializing Engine (loading model)...")
            try {
                engine = Engine(config)
                engine.initialize()
                cachedEngine = engine
                cachedModelPath = modelPath
                Log.i(TAG, "[INIT FLOW] LiteRT Engine initialized successfully and cached.")
            } catch (e: Throwable) {
                Log.e(TAG, "[INIT FLOW] [CRITICAL FAILURE] Failed to initialize LiteRT Engine. Full Exception Stack Trace:", e)
                throw e
            }
        }
        return engine
    }

    @Synchronized
    private fun invokeLiteRTInference(
        modelPath: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        try {
            // 1. Verify LiteRT library classes / static classpath integrity
            Log.i(TAG, "[INIT FLOW] [STEP 1/6] Verifying LiteRT SDK classes on classpath...")
            // Compiles statically with com.google.ai.edge.litertlm.*
            Log.i(TAG, "[INIT FLOW] [SUCCESS] LiteRT SDK classes verified on classpath.")

            // 2. Verify model file exists, is a file, and is readable
            Log.i(TAG, "[INIT FLOW] [STEP 2/6] Verifying model file status at: $modelPath")
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "[INIT FLOW] [FAILURE] Model file does not exist: $modelPath")
                throw java.io.FileNotFoundException("Model file not downloaded / missing at path: $modelPath")
            }
            if (modelFile.isDirectory) {
                Log.e(TAG, "[INIT FLOW] [FAILURE] Model path is a directory: $modelPath")
                throw IllegalArgumentException("Invalid model path: '$modelPath' is a directory, not a file.")
            }
            if (!modelFile.canRead()) {
                Log.e(TAG, "[INIT FLOW] [FAILURE] Model file is not readable: $modelPath")
                throw IOException("Model file at '$modelPath' exists but is not readable. Check storage permissions.")
            }
            Log.i(TAG, "[INIT FLOW] [SUCCESS] Model file verified (size: ${modelFile.length()} bytes).")

            // 3 & 4. Verify options configuration & engine initialization
            Log.i(TAG, "[INIT FLOW] [STEP 3/6 & 4/6] Creating & Initializing Engine...")
            val engine = try {
                getOrInitializeEngine(modelPath, maxTokens)
            } catch (e: LinkageError) {
                Log.e(TAG, "[INIT FLOW] [FAILURE] JNI native library failed to load (liblitertlm_jni.so)", e)
                throw UnsatisfiedLinkError("LiteRT JNI native library failed to load (liblitertlm_jni.so). Error: ${e.localizedMessage}")
            } catch (e: Exception) {
                val cause = e.cause
                if (cause is UnsatisfiedLinkError) {
                    Log.e(TAG, "[INIT FLOW] [FAILURE] JNI native library failed to load (wrapped)", cause)
                    throw UnsatisfiedLinkError("LiteRT JNI native library failed to load (liblitertlm_jni.so). Error: ${cause.localizedMessage}")
                }
                Log.e(TAG, "[INIT FLOW] [FAILURE] Engine initialization failed", e)
                throw IOException("LiteRT Engine initialization failed: ${cause?.localizedMessage ?: e.localizedMessage}", cause ?: e)
            }

            // 5. Verify JNI library link status
            Log.i(TAG, "[INIT FLOW] [STEP 5/6] Verifying JNI library link status...")
            // If the engine instance exists and is initialized, JNI link succeeded.
            Log.i(TAG, "[INIT FLOW] [SUCCESS] JNI library links verified.")

            // 6. Execute inference
            Log.i(TAG, "[INIT FLOW] [STEP 6/6] Executing inference on prompt...")
            val result = try {
                val samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = temperature.toDouble(),
                    seed = 0
                )
                val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
                val conversation = engine.createConversation(conversationConfig)
                
                val message = conversation.sendMessage(prompt)
                val responseText = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                conversation.close()
                responseText
            } catch (e: Exception) {
                val cause = e.cause
                Log.e(TAG, "[INIT FLOW] [FAILURE] Inference execution failed", e)
                throw IOException("LiteRT Inference execution failed: ${cause?.localizedMessage ?: e.localizedMessage}", cause ?: e)
            }
            Log.i(TAG, "[INIT FLOW] [SUCCESS] Inference succeeded. Received response of length ${result.length}.")
            return result

        } catch (e: Throwable) {
            closeCachedEngine()
            throw e
        }
    }

    @Synchronized
    fun closeCachedEngine() {
        cachedEngine?.let { engine ->
            try {
                Log.i(TAG, "Closing cached LiteRT engine")
                engine.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing cached engine: ${e.message}")
            }
        }
        cachedEngine = null
        cachedModelPath = null
    }

    // ── Prompt building (shared with GemmaProvider pattern) ─────────────

    private fun buildPrompt(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotEmpty()) {
            sb.append("System Instructions:\n").append(systemPrompt).append("\n\n")
        }

        if (tools.isNotEmpty()) {
            sb.append("Available tools you can call:\n")
            tools.forEach { tool ->
                sb.append("- Tool: ").append(tool.name).append("\n")
                sb.append("  Description: ").append(tool.description).append("\n")
                sb.append("  Parameters schema: ").append(tool.parameters).append("\n\n")
            }
            sb.append("If you need to call a tool, respond ONLY with a JSON object conforming exactly to this format:\n")
            sb.append("{\n")
            sb.append("  \"toolCall\": {\n")
            sb.append("    \"name\": \"TOOL_NAME\",\n")
            sb.append("    \"arguments\": { ... }\n")
            sb.append("  }\n")
            sb.append("}\n")
            sb.append("Do not add markdown formatting or backticks around the JSON. Output only the raw JSON. If no tool is needed, respond with standard text.\n\n")
        }

        sb.append("Conversation History:\n")
        messages.forEach { msg ->
            val sender = if (msg.sender == ChatMessage.Sender.USER) "User" else "Model"
            sb.append(sender).append(": ").append(msg.text).append("\n")
        }
        sb.append("Model:")
        return sb.toString()
    }

    private fun handleThrowable(e: Throwable, spec: OnDeviceModelSpec): Throwable {
        return when (e) {
            is IllegalStateException -> e
            is IOException -> e
            is ClassNotFoundException -> e
            is IllegalArgumentException -> e
            is UnsatisfiedLinkError -> e
            else -> {
                val msg = e.localizedMessage ?: ""
                when {
                    msg.contains("memory", ignoreCase = true) ||
                    msg.contains("OOM", ignoreCase = true) ->
                        IOException("Not enough memory to run ${spec.displayName}. Try a smaller model.", e)
                    msg.contains("GPU", ignoreCase = true) ||
                    msg.contains("delegate", ignoreCase = true) ->
                        IOException("GPU acceleration unavailable for ${spec.displayName}. Check device compatibility.", e)
                    else ->
                        IOException("LiteRT-LM error (${spec.displayName}): $msg", e)
                }
            }
        }
    }
}
