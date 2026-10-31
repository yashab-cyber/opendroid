package com.opendroid.ai.core.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * The selected LiteRT artifact could be read, but the bundled runtime could not
 * initialize it on this device. This is deliberately distinct from a malformed
 * artifact so callers can give users a safe, actionable error.
 */
class LiteRtRuntimeIncompatibilityException(cause: Throwable) : Exception(cause)

/**
 * Structural compatibility probe for LiteRT model artifacts: initializing an
 * [Engine] against the file is the only validation the runtime exposes, and it
 * throws when the artifact is not a loadable LiteRT model.
 */
object LiteRtCompatibility {

    private const val TAG = "LiteRtCompatibility"

    /**
     * Backends to try, in order. Gemma 4 LiteRT packages constrain their main
     * section to GPU, while older catalog models and arbitrary custom imports
     * may only load on CPU, and there is no per-model backend metadata to pick
     * from. Trying GPU first and falling back to CPU keeps both loadable.
     */
    val backendPreference: List<() -> Backend> = listOf({ Backend.GPU() }, { Backend.CPU() })

    /**
     * Probes [file] with the engine configuration the app will later run it with.
     * [maxNumTokens] must be the spec's `contextWindow`, the value
     * [com.opendroid.ai.core.llm.providers.LiteRTLMProvider] passes when it loads the
     * model. Probing with the runtime's default instead validated a configuration the
     * app never uses.
     */
    fun verify(file: File, cacheDir: File, maxNumTokens: Int) {
        verify(probeConfigs(file, cacheDir, maxNumTokens)) { config ->
            Engine(config).use { engine ->
                engine.initialize()
            }
        }
    }

    internal fun probeConfigs(file: File, cacheDir: File, maxNumTokens: Int): List<EngineConfig> =
        backendPreference.map { backend ->
            EngineConfig(
                modelPath = file.absolutePath,
                backend = backend(),
                maxNumTokens = maxNumTokens,
                cacheDir = cacheDir.absolutePath
            )
        }

    /**
     * Tries each config in order and returns on the first that initializes. Each
     * failure is logged as it happens. When every backend fails, the first failure is
     * thrown with the others attached as suppressed exceptions. The runtime's own
     * message is the only diagnosis available for a model that will not load (#57),
     * so none of them may be dropped.
     */
    internal fun verify(configs: List<EngineConfig>, initialize: (EngineConfig) -> Unit) {
        val failures = mutableListOf<Throwable>()
        for (config in configs) {
            try {
                initialize(config)
                return
            } catch (e: Throwable) {
                Log.w(TAG, "LiteRT probe failed on ${config.backend}: ${e.javaClass.name}: ${e.message}", e)
                failures += e
            }
        }
        // Every backend failed. Report runtime incompatibility when any failure names
        // a device/backend limitation (e.g. GPU delegate unsupported on this hardware,
        // model contains GPU-only ops, XNNPACK failure, out-of-memory, etc.).
        // Ordinary parse/corrupted artifact errors mean a malformed artifact and stay
        // classified as FORMAT_INVALID.
        val first = failures.first()
        failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
        if (failures.any { isBackendIncompatibility(it) }) {
            throw LiteRtRuntimeIncompatibilityException(first)
        }
        throw first
    }

    private val BACKEND_FAILURE_MARKERS = listOf(
        "gpu",
        "opencl",
        "vulkan",
        "delegate",
        "backend",
        "accelerator",
        "xnnpack",
        "subgraph",
        "unsupported",
        "not supported",
        "memory",
        "allocation",
        "hardware",
        "driver",
        "device",
        "shader",
        "opengl",
        "gles",
        "clcreate",
        "incompatible",
        "compatibility"
    )

    /**
     * The runtime does not expose typed failures, so the only available signal
     * that a failure is about the device rather than the file is the message.
     */
    internal fun isBackendIncompatibility(error: Throwable): Boolean {
        var current: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is LinkageError) return true
            val message = current.message?.lowercase()
            if (message != null && BACKEND_FAILURE_MARKERS.any { message.contains(it) }) return true
            current = current.cause
        }
        return false
    }
}
