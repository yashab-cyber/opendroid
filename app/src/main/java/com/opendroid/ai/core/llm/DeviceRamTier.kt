package com.opendroid.ai.core.llm

/**
 * Whether a phone has enough RAM for an on-device model, judged against tiers named after
 * the RAM sizes phones are sold with.
 */
internal object DeviceRamTier {

    private const val GIB = 1024.0 * 1024 * 1024

    /** RAM sizes phones are sold with, in GB. */
    private val SOLD_SIZES_GB = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 18.0, 24.0, 32.0)

    /** System RAM, in GB, a model of [modelBytes] needs. */
    fun requiredGb(modelBytes: Long): Double = when {
        modelBytes >= 4.5 * GIB -> 12.0 // e.g. Gemma 3n E4B (~4.92 GB)
        modelBytes >= 3.0 * GIB -> 8.0 // e.g. Gemma 4 E4B (~3.66 GB), Gemma 3n E2B (~3.65 GB)
        modelBytes >= 2.0 * GIB -> 6.0 // e.g. Gemma 4 E2B (~2.58 GB)
        modelBytes >= 1.0 * GIB -> 4.0
        else -> 3.5 // Small models (e.g. Qwen 2.5 0.5B ~546 MB) still need >= 3.5 GB system RAM
    }

    /**
     * The RAM, in GB, a phone reporting [totalMemBytes] (`ActivityManager.MemoryInfo.totalMem`)
     * is sold with. totalMem excludes memory the kernel and firmware reserve, so an "8 GB"
     * phone reports about 7.2-7.6 GiB; it is rounded up to the nearest sold size (#114).
     */
    fun deviceGb(totalMemBytes: Long): Double {
        val reportedGb = totalMemBytes / GIB
        return SOLD_SIZES_GB.firstOrNull { it >= reportedGb } ?: reportedGb
    }

    fun meetsRequirement(totalMemBytes: Long, modelBytes: Long): Boolean =
        deviceGb(totalMemBytes) >= requiredGb(modelBytes)
}
