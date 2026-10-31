package com.opendroid.ai.core.llm

/**
 * Whether a phone has enough RAM for an on-device model, judged against tiers named after
 * the RAM sizes phones are sold with.
 */
internal object DeviceRamTier {

    private const val GIB = 1024.0 * 1024 * 1024

    /** System RAM, in GB, a model of [modelBytes] needs. */
    fun requiredGb(modelBytes: Long): Double = when {
        modelBytes >= 4.5 * GIB -> 12.0 // e.g. Gemma 3n E4B (~4.92 GB)
        modelBytes >= 3.0 * GIB -> 8.0 // e.g. Gemma 4 E4B (~3.66 GB), Gemma 3n E2B (~3.65 GB)
        modelBytes >= 2.0 * GIB -> 6.0 // e.g. Gemma 4 E2B (~2.58 GB)
        modelBytes >= 1.0 * GIB -> 4.0
        else -> 3.5 // Small models (e.g. Qwen 2.5 0.5B ~546 MB) still need >= 3.5 GB system RAM
    }

    /** The phone's RAM in GB, from `ActivityManager.MemoryInfo.totalMem`. */
    fun deviceGb(totalMemBytes: Long): Double = totalMemBytes / GIB

    fun meetsRequirement(totalMemBytes: Long, modelBytes: Long): Boolean =
        deviceGb(totalMemBytes) >= requiredGb(modelBytes)
}
