package com.opendroid.ai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opendroid.ai.core.security.SecurePrefs
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val llmConfigKey = stringPreferencesKey("llm_config")

    // API keys live in EncryptedSharedPreferences (Android Keystore), never in the
    // DataStore JSON blob, which is plaintext on disk.
    private val securePrefs by lazy { SecurePrefs.get(context) }

    companion object {
        private const val PROVIDER_KEY_PREFIX = "llm_api_key_"
        private const val ELEVENLABS_KEY = "elevenlabs_api_key"
    }

    init {
        // One-time migration: move any API keys still embedded in the persisted
        // JSON into the encrypted store and rewrite the JSON without them.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                updateConfig { it }
            } catch (_: Exception) {
                // Migration retried on next config write.
            }
        }
    }

    /** Overlay secrets from the encrypted store onto a persisted (stripped) config. */
    private fun mergeSecrets(persisted: LLMConfig): LLMConfig {
        val secureKeys = securePrefs.all
            .filterKeys { it.startsWith(PROVIDER_KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? String)?.let { key.removePrefix(PROVIDER_KEY_PREFIX) to it }
            }
            .toMap()
        val elevenLabsKey = securePrefs.getString(ELEVENLABS_KEY, null)
        return persisted.copy(
            // Legacy keys still in the JSON are kept until migration strips them;
            // the encrypted store wins on conflict.
            apiKeys = persisted.apiKeys + secureKeys,
            elevenLabsApiKey = elevenLabsKey ?: persisted.elevenLabsApiKey
        )
    }

    /** Write secrets to the encrypted store and return the config with secrets removed. */
    private fun storeSecretsAndStrip(config: LLMConfig): LLMConfig {
        val editor = securePrefs.edit()
        // Remove provider keys that were deleted from the config
        securePrefs.all.keys
            .filter { it.startsWith(PROVIDER_KEY_PREFIX) }
            .map { it.removePrefix(PROVIDER_KEY_PREFIX) }
            .filterNot { config.apiKeys.containsKey(it) }
            .forEach { editor.remove(PROVIDER_KEY_PREFIX + it) }
        config.apiKeys.forEach { (provider, key) ->
            editor.putString(PROVIDER_KEY_PREFIX + provider, key)
        }
        if (config.elevenLabsApiKey.isBlank()) {
            editor.remove(ELEVENLABS_KEY)
        } else {
            editor.putString(ELEVENLABS_KEY, config.elevenLabsApiKey)
        }
        editor.apply()
        return config.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
    }

    // Auto-reply preference keys
    private val autoReplyGlobalKey = booleanPreferencesKey("auto_reply_global")
    private val autoReplyWhatsAppKey = booleanPreferencesKey("auto_reply_whatsapp")
    private val autoReplySmsKey = booleanPreferencesKey("auto_reply_sms")
    private val autoReplyEmailKey = booleanPreferencesKey("auto_reply_email")
    private val autoReplyDelayKey = intPreferencesKey("auto_reply_delay_minutes")
    private val autoReplyBlacklistKey = stringSetPreferencesKey("auto_reply_blacklist")
    private val autoReplyWhitelistKey = stringSetPreferencesKey("auto_reply_whitelist")
    private val autoReplyCustomPromptKey = stringPreferencesKey("auto_reply_custom_prompt")
    private val autoReplyMaxPerHourKey = intPreferencesKey("auto_reply_max_per_hour")

    val llmConfig: Flow<LLMConfig> = context.dataStore.data.map { preferences ->
        val configStr = preferences[llmConfigKey]
        val persisted = if (configStr != null) {
            try {
                json.decodeFromString<LLMConfig>(configStr)
            } catch (e: Exception) {
                LLMConfig()
            }
        } else {
            LLMConfig()
        }
        mergeSecrets(persisted)
    }

    val autoReplyConfig: Flow<AutoReplyConfig> = context.dataStore.data.map { preferences ->
        AutoReplyConfig(
            // Auto-reply is opt-in (see AutoReplyConfig): default OFF until the
            // user explicitly enables each channel.
            globalEnabled = preferences[autoReplyGlobalKey] ?: false,
            whatsappEnabled = preferences[autoReplyWhatsAppKey] ?: false,
            smsEnabled = preferences[autoReplySmsKey] ?: false,
            emailEnabled = preferences[autoReplyEmailKey] ?: false,
            replyDelayMinutes = preferences[autoReplyDelayKey] ?: 15,
            blacklistedContacts = preferences[autoReplyBlacklistKey] ?: emptySet(),
            whitelistedContacts = preferences[autoReplyWhitelistKey] ?: emptySet(),
            customPrompt = preferences[autoReplyCustomPromptKey],
            maxRepliesPerContactPerHour = preferences[autoReplyMaxPerHourKey] ?: 3
        )
    }

    suspend fun updateConfig(update: (LLMConfig) -> LLMConfig) {
        context.dataStore.edit { preferences ->
            val currentStr = preferences[llmConfigKey]
            val currentConfig = if (currentStr != null) {
                try {
                    json.decodeFromString<LLMConfig>(currentStr)
                } catch (e: Exception) {
                    LLMConfig()
                }
            } else {
                LLMConfig()
            }
            val newConfig = update(mergeSecrets(currentConfig))
            preferences[llmConfigKey] = json.encodeToString(storeSecretsAndStrip(newConfig))
        }
    }

    suspend fun saveModelCache(provider: String, models: List<com.opendroid.ai.core.llm.AIModel>) {
        updateConfig { current ->
            val cache = current.modelCache.toMutableMap()
            cache[provider] = models
            val fetchMap = current.lastModelFetch.toMutableMap()
            fetchMap[provider] = System.currentTimeMillis()
            current.copy(modelCache = cache, lastModelFetch = fetchMap)
        }
    }

    suspend fun updateAutoReplyConfig(config: AutoReplyConfig) {
        context.dataStore.edit { preferences ->
            preferences[autoReplyGlobalKey] = config.globalEnabled
            preferences[autoReplyWhatsAppKey] = config.whatsappEnabled
            preferences[autoReplySmsKey] = config.smsEnabled
            preferences[autoReplyEmailKey] = config.emailEnabled
            preferences[autoReplyDelayKey] = config.replyDelayMinutes
            preferences[autoReplyBlacklistKey] = config.blacklistedContacts
            preferences[autoReplyWhitelistKey] = config.whitelistedContacts
            if (config.customPrompt != null) {
                preferences[autoReplyCustomPromptKey] = config.customPrompt
            } else {
                preferences.remove(autoReplyCustomPromptKey)
            }
            preferences[autoReplyMaxPerHourKey] = config.maxRepliesPerContactPerHour
        }
    }
}
