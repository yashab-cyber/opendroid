package com.opendroid.ai.core.llm

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * Enumerates the on-device inference backends available.
 */
enum class OnDeviceBackend {
    /** Android AI Core (ML Kit GenAI Prompt API) – requires Google Play Services AI Core. */
    AI_CORE,
    /** LiteRT-LM (formerly TFLite LLM) – runs directly on-device via GPU/NPU delegates. */
    LITERT_LM
}

/**
 * Describes a single on-device model variant.
 *
 * To add a new model in the future, simply append an entry to
 * [OnDeviceModelRegistry.allModels]. No UI changes are required.
 */
@Serializable
data class OnDeviceModelSpec(
    /** Stable identifier persisted in settings (e.g. "gemma-4-e2b-it-litert"). */
    val id: String,
    /** Human-readable label shown in the model picker. */
    val displayName: String,
    /** Model family grouping (e.g. "Gemma 4", "Gemma 3n", "Qwen"). */
    val family: String,
    /** Approximate parameter size label (e.g. "2B", "4B", "0.5B"). */
    val sizeLabel: String,
    /** Which inference backend this model uses. Not serialized for display — used at runtime. */
    val backend: OnDeviceBackend,
    /**
     * For LiteRT-LM models: the Hugging Face repo path or local asset path to the
     * `.litertlm` or `.task` model file.  Ignored for AI_CORE models.
     */
    val modelPath: String = "",
    /** The actual model filename on Hugging Face (e.g. "gemma-4-E2B-it.litertlm"). */
    val modelFilename: String = "model.task",
    /** Model version identifier. */
    val version: String = "1.0.0",
    /** Expected SHA-256 hash checksum of the model file. */
    val sha256: String = "",
    /** Expected file size of the model file in bytes. */
    val expectedSize: Long = 0L,
    /** Gated repository license terms URL. */
    val licenseUrl: String = "",
    /** Whether downloading this model requires Hugging Face authentication. */
    val authRequired: Boolean = false,
    /** Whether this model is the recommended default for its backend. */
    val isRecommended: Boolean = false,
    /** Minimum Android SDK level required by this model variant. */
    val minSdk: Int = 26
)

/**
 * Single source of truth for every on-device model the app supports.
 *
 * ## Adding a new model
 * 1. Append an [OnDeviceModelSpec] entry to [allModels].
 * 2. That's it — the settings UI, model picker, and fallback logic will
 *    automatically pick up the new entry.
 */
object OnDeviceModelRegistry {

    // ── AI Core models (existing, unchanged behaviour) ─────────────────
    private val aiCoreModels = listOf(
        OnDeviceModelSpec(
            id = "gemma-4-on-device",
            displayName = "Gemma 4 (AI Core)",
            family = "Gemma 4",
            sizeLabel = "On-device",
            backend = OnDeviceBackend.AI_CORE,
            isRecommended = true
        ),
        OnDeviceModelSpec(
            id = "gemma-3n-multimodal",
            displayName = "Gemma 3n Multimodal (AI Core)",
            family = "Gemma 3n",
            sizeLabel = "On-device",
            backend = OnDeviceBackend.AI_CORE,
            minSdk = 26
        )
    )

    // ── LiteRT-LM models ──────────────────────────────────────────────
    private val liteRTModels = listOf(
        OnDeviceModelSpec(
            id = "gemma-4-e2b-it-litert",
            displayName = "Gemma 4 E2B-it (LiteRT)",
            family = "Gemma 4",
            sizeLabel = "2B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFilename = "gemma-4-E2B-it.litertlm",
            expectedSize = 2588147712L,
            licenseUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            authRequired = true,
            isRecommended = true,
            minSdk = 31
        ),
        OnDeviceModelSpec(
            id = "gemma-4-e4b-it-litert",
            displayName = "Gemma 4 E4B-it (LiteRT)",
            family = "Gemma 4",
            sizeLabel = "4B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFilename = "gemma-4-E4B-it.litertlm",
            expectedSize = 3660000000L,
            licenseUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            authRequired = true,
            minSdk = 31
        ),
        OnDeviceModelSpec(
            id = "gemma-3n-e2b-it-litert",
            displayName = "Gemma 3n E2B-it (LiteRT)",
            family = "Gemma 3n",
            sizeLabel = "2B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "google/gemma-3n-E2B-it-litert-lm",
            modelFilename = "gemma-3n-E2B-it.litertlm",
            expectedSize = 2000000000L,
            licenseUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm",
            authRequired = true,
            minSdk = 31
        ),
        OnDeviceModelSpec(
            id = "gemma-3n-e4b-it-litert",
            displayName = "Gemma 3n E4B-it (LiteRT)",
            family = "Gemma 3n",
            sizeLabel = "4B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "google/gemma-3n-E4B-it-litert-lm",
            modelFilename = "gemma-3n-E4B-it.litertlm",
            expectedSize = 4000000000L,
            licenseUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm",
            authRequired = true,
            minSdk = 31
        ),
        OnDeviceModelSpec(
            id = "qwen-2.5-0.5b-it-litert",
            displayName = "Qwen 2.5 0.5B-it (LiteRT)",
            family = "Qwen",
            sizeLabel = "0.5B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "litert-community/Qwen2.5-0.5B-Instruct",
            modelFilename = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            expectedSize = 546660344L,
            sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
            licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct",
            authRequired = false,
            minSdk = 26
        )
    )

    /** Every model the app knows about, across all backends. */
    val allModels: List<OnDeviceModelSpec> = aiCoreModels + liteRTModels

    /** Only AI Core models. */
    val aiCoreOnly: List<OnDeviceModelSpec> get() = allModels.filter { it.backend == OnDeviceBackend.AI_CORE }

    /** Only LiteRT-LM models. */
    val liteRTOnly: List<OnDeviceModelSpec> get() = allModels.filter { it.backend == OnDeviceBackend.LITERT_LM }

    /** Look up a model spec by its stable [id]. */
    fun findById(id: String): OnDeviceModelSpec? = allModels.find { it.id == id }

    /** Returns the recommended model for the given [backend], or the first available. */
    fun recommendedFor(backend: OnDeviceBackend): OnDeviceModelSpec? =
        allModels.filter { it.backend == backend }.let { models ->
            models.find { it.isRecommended } ?: models.firstOrNull()
        }

    /** Convert all models to [AIModel] instances for the model picker UI. */
    fun toAIModels(): List<AIModel> = allModels.map { spec ->
        AIModel(
            id = spec.id,
            displayName = spec.displayName,
            provider = "On-Device AI",
            isFree = true,
            isRecommended = spec.isRecommended
        )
    }

    /**
     * Checks if the device has enough system RAM to load the model.
     * Throws IllegalStateException if the device memory is insufficient.
     */
    fun checkDeviceMemoryCompatibility(context: Context, spec: OnDeviceModelSpec) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (activityManager != null) {
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRam = memoryInfo.totalMem
            val totalRamGb = totalRam.toDouble() / (1024 * 1024 * 1024)
            
            val requiredRamGb = when {
                spec.expectedSize >= 3.0 * 1024 * 1024 * 1024L -> 8.0  // e.g. Gemma 4 E4B (~3.66 GB)
                spec.expectedSize >= 2.0 * 1024 * 1024 * 1024L -> 6.0  // e.g. Gemma 4 E2B (~2.58 GB)
                spec.expectedSize >= 1.0 * 1024 * 1024 * 1024L -> 4.0
                else -> 0.0
            }
            
            if (requiredRamGb > 0.0 && totalRamGb < requiredRamGb) {
                val modelName = spec.displayName
                throw IllegalStateException(
                    "Insufficient device memory: $modelName requires at least ${String.format("%.1f", requiredRamGb)} GB of system RAM, but your device only has ${String.format("%.1f", totalRamGb)} GB RAM. " +
                    "Running this model will cause the system to crash. Please use a smaller model like Qwen 2.5."
                )
            }
        }
    }
}
