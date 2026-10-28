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
    /**
     * Registry-owned metadata for a model that the app may download itself.
     * A partial record deliberately makes managed download unavailable.
     */
    val managedArtifact: ManagedModelArtifactMetadata? = null,
    /** Expected SHA-256 hash checksum, retained for existing display callers. */
    val sha256: String = managedArtifact?.sha256.orEmpty(),
    /** Expected file size in bytes, retained for memory sizing and display callers. */
    val expectedSize: Long = managedArtifact?.expectedSize ?: 0L,
    /** Gated repository license terms URL. */
    val licenseUrl: String = "",
    /** Whether downloading this model requires Hugging Face authentication. */
    val authRequired: Boolean = false,
    /** Whether this model is the recommended default for its backend. */
    val isRecommended: Boolean = false,
    /** Minimum Android SDK level required by this model variant. */
    val minSdk: Int = 26,
    /**
     * Total token capacity (input + output) of the model's KV cache, e.g. 1280
     * for a `...ekv1280.task` file. The LiteRT engine's `maxNumTokens` must be
     * sized from this — exceeding it crashes natively.
     */
    val contextWindow: Int = 1280
) {
    /** True only when the catalog has a complete, publisher-verifiable artifact record. */
    val isManagedDownloadAvailable: Boolean
        get() = backend == OnDeviceBackend.LITERT_LM && managedArtifact?.isComplete == true
}

/**
 * Single source of truth for every on-device model the app supports.
 *
 * ## Adding a new model
 * 1. Append an [OnDeviceModelSpec] entry to [allModels].
 * 2. That's it — the settings UI, model picker, and fallback logic will
 *    automatically pick up the new entry.
 */
object OnDeviceModelRegistry {

    /** Id prefix for user-imported LiteRT models that are not in the static catalog. */
    const val CUSTOM_ID_PREFIX = "custom-"

    /**
     * Conservative default for unknown custom imports. Under-sizing [OnDeviceModelSpec.contextWindow]
     * is safer than over-sizing: LiteRT aborts natively if input+output exceeds maxNumTokens.
     */
    const val CUSTOM_DEFAULT_CONTEXT_WINDOW = 1280

    fun isCustomId(id: String): Boolean = id.startsWith(CUSTOM_ID_PREFIX)

    /**
     * Builds a LiteRT spec for a freestanding user import. Not managed-downloadable and never
     * requires Hugging Face auth.
     */
    fun customSpec(
        id: String,
        displayName: String,
        modelFilename: String,
        expectedSize: Long = 0L,
        contextWindow: Int = CUSTOM_DEFAULT_CONTEXT_WINDOW,
        minSdk: Int = 26
    ): OnDeviceModelSpec {
        require(isCustomId(id)) { "Custom on-device model ids must start with $CUSTOM_ID_PREFIX" }
        return OnDeviceModelSpec(
            id = id,
            displayName = displayName,
            family = "Custom",
            sizeLabel = "Import",
            backend = OnDeviceBackend.LITERT_LM,
            modelFilename = modelFilename,
            expectedSize = expectedSize,
            authRequired = false,
            minSdk = minSdk,
            contextWindow = contextWindow
        )
    }

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
            managedArtifact = ManagedModelArtifactMetadata(
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it.litertlm",
                sourceRevision = "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94",
                expectedSize = 2588147712L,
                // Publisher LFS sha256 recorded in #129, agreed by two independent Hugging Face
                // endpoints (model-info `?blobs=true` and the resolve `X-Linked-ETag` header) for
                // the pinned revision; not yet re-hashed from downloaded bytes.
                sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
            ),
            licenseUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            // The litert-community mirror is ungated: anonymous resolve returns the artifact.
            authRequired = false,
            minSdk = 31,
            contextWindow = 4096
        ),
        OnDeviceModelSpec(
            id = "gemma-4-e4b-it-litert",
            displayName = "Gemma 4 E4B-it (LiteRT)",
            family = "Gemma 4",
            sizeLabel = "4B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFilename = "gemma-4-E4B-it.litertlm",
            managedArtifact = ManagedModelArtifactMetadata(
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
                sourceRevision = "2eee7ac325f20eb8c9ac1d0e972f7c84663062da",
                // The previous 3_660_000_000 constant was an estimate; the real artifact is
                // 469,760 bytes smaller, which an exact-size check would have rejected.
                expectedSize = 3659530240L,
                // Publisher LFS sha256 recorded in #129, agreed by two independent Hugging Face
                // endpoints (model-info `?blobs=true` and the resolve `X-Linked-ETag` header) for
                // the pinned revision; not yet re-hashed from downloaded bytes.
                sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
            ),
            licenseUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            // The litert-community mirror is ungated: anonymous resolve returns the artifact.
            authRequired = false,
            minSdk = 31,
            contextWindow = 4096
        ),
        OnDeviceModelSpec(
            id = "gemma-3n-e2b-it-litert",
            displayName = "Gemma 3n E2B-it (LiteRT)",
            family = "Gemma 3n",
            sizeLabel = "2B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "google/gemma-3n-E2B-it-litert-lm",
            modelFilename = "gemma-3n-E2B-it-int4.litertlm",
            managedArtifact = ManagedModelArtifactMetadata(
                downloadUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/ba9ca88da013b537b6ed38108be609b8db1c3a16/gemma-3n-E2B-it-int4.litertlm",
                sourceRevision = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
                expectedSize = 3655827456L,
                // Publisher LFS sha256 recorded in #82 from the Hugging Face revision
                // endpoint for the pinned revision; not yet re-hashed from downloaded bytes.
                sha256 = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6"
            ),
            licenseUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm",
            authRequired = true,
            minSdk = 31,
            contextWindow = 4096
        ),
        OnDeviceModelSpec(
            id = "gemma-3n-e4b-it-litert",
            displayName = "Gemma 3n E4B-it (LiteRT)",
            family = "Gemma 3n",
            sizeLabel = "4B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "google/gemma-3n-E4B-it-litert-lm",
            modelFilename = "gemma-3n-E4B-it-int4.litertlm",
            managedArtifact = ManagedModelArtifactMetadata(
                downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/297ed75955702dec3503e00c2c2ecbbf475300bc/gemma-3n-E4B-it-int4.litertlm",
                sourceRevision = "297ed75955702dec3503e00c2c2ecbbf475300bc",
                expectedSize = 4919541760L,
                // Publisher LFS sha256 recorded in #82 from the Hugging Face revision
                // endpoint for the pinned revision; not yet re-hashed from downloaded bytes.
                sha256 = "2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4"
            ),
            licenseUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm",
            authRequired = true,
            minSdk = 31,
            contextWindow = 4096
        ),
        OnDeviceModelSpec(
            id = "qwen-2.5-0.5b-it-litert",
            displayName = "Qwen 2.5 0.5B-it (LiteRT)",
            family = "Qwen",
            sizeLabel = "0.5B",
            backend = OnDeviceBackend.LITERT_LM,
            modelPath = "litert-community/Qwen2.5-0.5B-Instruct",
            modelFilename = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            managedArtifact = ManagedModelArtifactMetadata(
                downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/6c237a59eedeb06a821b21f0a59b03d346ac8bc3/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                sourceRevision = "6c237a59eedeb06a821b21f0a59b03d346ac8bc3",
                expectedSize = 546660344L,
                sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2"
            ),
            licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct",
            authRequired = false,
            // Public HF artifact + complete integrity metadata — default LiteRT pick for
            // devices that cannot (or choose not to) configure a Hugging Face token.
            isRecommended = true,
            minSdk = 26,
            contextWindow = 1280
        )
    )

    /** Every model the app knows about, across all backends. */
    val allModels: List<OnDeviceModelSpec> = aiCoreModels + liteRTModels

    /** Only AI Core models. */
    val aiCoreOnly: List<OnDeviceModelSpec> get() = allModels.filter { it.backend == OnDeviceBackend.AI_CORE }

    /** Only LiteRT-LM models. */
    val liteRTOnly: List<OnDeviceModelSpec> get() = allModels.filter { it.backend == OnDeviceBackend.LITERT_LM }

    /** Look up a catalog model spec by its stable [id]. Custom imports are resolved via [ModelRepository]. */
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
     * Checks if the device has enough system RAM and available memory to load the model safely.
     * Throws IllegalStateException if the device memory is insufficient or under critical pressure.
     */
    fun checkDeviceMemoryCompatibility(context: Context, spec: OnDeviceModelSpec) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (activityManager != null) {
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRam = memoryInfo.totalMem
            val totalRamGb = totalRam.toDouble() / (1024 * 1024 * 1024)
            val modelName = spec.displayName
            
            // Critical system memory pressure check
            if (memoryInfo.lowMemory) {
                throw IllegalStateException(
                    "Device is currently under critical memory pressure. " +
                    "Free up RAM by closing background apps before running $modelName."
                )
            }

            val requiredRamGb = when {
                spec.expectedSize >= 4.5 * 1024 * 1024 * 1024L -> 12.0 // e.g. Gemma 3n E4B (~4.92 GB)
                spec.expectedSize >= 3.0 * 1024 * 1024 * 1024L -> 8.0  // e.g. Gemma 4 E4B (~3.66 GB), Gemma 3n E2B (~3.65 GB)
                spec.expectedSize >= 2.0 * 1024 * 1024 * 1024L -> 6.0  // e.g. Gemma 4 E2B (~2.58 GB)
                spec.expectedSize >= 1.0 * 1024 * 1024 * 1024L -> 4.0
                else -> 3.5 // Small models (e.g. Qwen 2.5 0.5B ~546 MB) still need >= 3.5 GB system RAM
            }
            
            if (totalRamGb < requiredRamGb) {
                throw IllegalStateException(
                    "Insufficient device memory: $modelName requires at least ${String.format(java.util.Locale.US, "%.1f", requiredRamGb)} GB of system RAM, but your device only has ${String.format(java.util.Locale.US, "%.1f", totalRamGb)} GB RAM. " +
                    "Running this model will cause the system to crash. Please use a smaller model like Qwen 2.5."
                )
            }

            // Headroom check: ensure available RAM can accommodate runtime working memory and OS LMK threshold
            val requiredWorkingMem = when {
                spec.expectedSize >= 4.5 * 1024 * 1024 * 1024L -> (2200L * 1024 * 1024)
                spec.expectedSize >= 2.8 * 1024 * 1024 * 1024L -> (1500L * 1024 * 1024)
                spec.expectedSize >= 1.0 * 1024 * 1024 * 1024L -> (900L * 1024 * 1024)
                spec.expectedSize > 0L -> (400L * 1024 * 1024)
                else -> (500L * 1024 * 1024)
            }
            val safetyBuffer = 150L * 1024 * 1024 // 150 MB safety margin above LMK threshold
            val requiredAvailMem = memoryInfo.threshold + requiredWorkingMem + safetyBuffer
            if (memoryInfo.availMem < requiredAvailMem) {
                val availMb = memoryInfo.availMem / (1024 * 1024)
                val neededMb = requiredAvailMem / (1024 * 1024)
                throw IllegalStateException(
                    "Insufficient available memory: $modelName requires ~${neededMb} MB free RAM to run safely, but only ${availMb} MB is available right now. " +
                    "Close background apps or switch to a cloud provider to avoid freezing the device."
                )
            }
        }
    }
}
