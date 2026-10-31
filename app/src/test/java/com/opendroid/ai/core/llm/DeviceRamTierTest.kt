package com.opendroid.ai.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActivityManager.MemoryInfo.totalMem` excludes memory the kernel and firmware reserve, so
 * a phone reports less than the RAM it is sold with: an "8 GB" phone reports about 7.2–7.6
 * GiB. The tiers are named after the sold sizes, so comparing them with the raw figure
 * refused every 8 GB phone the 8 GB tier (#114).
 */
class DeviceRamTierTest {

    private val gemma4E4b = 3_659_530_240L
    private val gemma3nE4b = 4_919_541_760L
    private val gemma4E2b = 2_588_147_712L
    private val qwen = 546_660_344L

    @Test
    fun `an 8 GB phone can run an 8 GB tier model`() {
        assertTrue(DeviceRamTier.meetsRequirement(totalMemBytes = 7_824_412L * KIB, modelBytes = gemma4E4b))
        assertTrue(DeviceRamTier.meetsRequirement(totalMemBytes = gib(7.2), modelBytes = gemma4E4b))
    }

    @Test
    fun `a 12 GB phone can run a 12 GB tier model`() {
        assertTrue(DeviceRamTier.meetsRequirement(totalMemBytes = gib(11.2), modelBytes = gemma3nE4b))
    }

    @Test
    fun `a 6 GB phone can run a 6 GB tier model`() {
        assertTrue(DeviceRamTier.meetsRequirement(totalMemBytes = gib(5.5), modelBytes = gemma4E2b))
    }

    @Test
    fun `a 4 GB phone can run a small model`() {
        assertTrue(DeviceRamTier.meetsRequirement(totalMemBytes = gib(3.6), modelBytes = qwen))
    }

    @Test
    fun `a 6 GB phone is still refused an 8 GB tier model`() {
        assertFalse(DeviceRamTier.meetsRequirement(totalMemBytes = gib(5.7), modelBytes = gemma4E4b))
    }

    @Test
    fun `a 3 GB phone is still refused a small model`() {
        assertFalse(DeviceRamTier.meetsRequirement(totalMemBytes = gib(2.8), modelBytes = qwen))
    }

    private fun gib(value: Double): Long = (value * 1024 * 1024 * 1024).toLong()

    private companion object {
        const val KIB = 1024L
    }
}
