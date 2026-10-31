package com.opendroid.ai.core.llm

import com.google.ai.edge.litertlm.Backend
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtCompatibilityTest {

    @Test
    fun `probes GPU first and falls back to CPU`() {
        val backends = LiteRtCompatibility.backendPreference.map { it()::class.simpleName }
        assertTrue("Expected GPU then CPU, got $backends", backends == listOf("GPU", "CPU"))
    }

    @Test
    fun `device and backend failures are classified as runtime incompatibility`() {
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalStateException("Failed to create GPU delegate for this device")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IOException("wrapper", IllegalStateException("OpenCL runtime unavailable"))
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                RuntimeException("failed to create XNNPACK runtime")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalStateException("Main section has no compatible subgraph")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                RuntimeException("Feature is not yet supported on this hardware")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                OutOfMemoryError("Failed to allocate memory for KV cache")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalStateException("Failed to compile compute shader on device driver")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                UnsatisfiedLinkError("dlopen failed: library \"liblitertlm_jni.so\" not found")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                LinkageError("Native symbol resolution failed")
            )
        )
    }

    @Test
    fun `malformed artifact failures are not classified as runtime incompatibility`() {
        assertFalse(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalArgumentException("Failed to parse model file: unexpected section header")
            )
        )
        assertFalse(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalArgumentException("Input is not valid flatbuffer model")
            )
        )
        assertFalse(LiteRtCompatibility.isBackendIncompatibility(IOException(null as String?)))
    }

    @Test
    fun `the probe sizes every backend with the token budget the runtime uses`() {
        val model = File("models/custom/model.task")
        val configs = LiteRtCompatibility.probeConfigs(model, File("cache"), maxNumTokens = 1280)

        assertEquals(listOf("GPU", "CPU"), configs.map { it.backend::class.simpleName })
        assertTrue(
            "Expected maxNumTokens = 1280 on every backend, got ${configs.map { it.maxNumTokens }}",
            configs.all { it.maxNumTokens == 1280 }
        )
        assertTrue(configs.all { it.modelPath == model.absolutePath })
    }

    @Test
    fun `the first backend that initializes ends the probe`() {
        val tried = mutableListOf<String?>()

        LiteRtCompatibility.verify(probeConfigs()) { config ->
            tried += config.backend::class.simpleName
            if (config.backend is Backend.GPU) throw IllegalStateException("OpenCL runtime unavailable")
        }

        assertEquals(listOf("GPU", "CPU"), tried)
    }

    @Test
    fun `when every backend fails the CPU failure is kept alongside the GPU one`() {
        val gpu = IllegalStateException("OpenCL runtime unavailable")
        val cpu = IllegalArgumentException("Failed to parse model file: unexpected section header")

        val thrown = assertThrows(LiteRtRuntimeIncompatibilityException::class.java) {
            LiteRtCompatibility.verify(probeConfigs()) { config ->
                throw if (config.backend is Backend.GPU) gpu else cpu
            }
        }

        assertSame(gpu, thrown.cause)
        assertEquals(listOf<Throwable>(cpu), gpu.suppressed.toList())
    }

    @Test
    fun `a format failure on every backend still carries each backend's failure`() {
        val gpu = IllegalArgumentException("Failed to parse model file: unexpected section header")
        val cpu = IllegalArgumentException("Input is not valid flatbuffer model")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            LiteRtCompatibility.verify(probeConfigs()) { config ->
                throw if (config.backend is Backend.GPU) gpu else cpu
            }
        }

        assertSame(gpu, thrown)
        assertEquals(listOf<Throwable>(cpu), thrown.suppressed.toList())
    }

    private fun probeConfigs() =
        LiteRtCompatibility.probeConfigs(File("model.task"), File("cache"), maxNumTokens = 1280)
}
