package com.opendroid.ai

import android.app.Application
import com.opendroid.ai.core.memory.MemoryManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OpenDroidApp : Application() {

    @Inject
    lateinit var memoryManager: MemoryManager

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // One-time startup cleanup: remove any poisoned memory entries
        // that may have been stored by previous versions of the app
        appScope.launch {
            try {
                memoryManager.cleanPoisonedMemories()
            } catch (e: Exception) {
                // Silently ignore cleanup errors to not block app startup
            }
        }
    }
}
