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
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.models.LLMConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Outcome for a configuration save that needs direct credential-store persistence. */
sealed interface ProviderCredentialPersistenceState {
    data object Ready : ProviderCredentialPersistenceState
    data object StorageUnavailable : ProviderCredentialPersistenceState
    data object CredentialsMustBeReentered : ProviderCredentialPersistenceState
}

@Singleton
class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val providerCredentialStore: ProviderCredentialStore,
    private val runStartupMigration: Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        providerCredentialStore: ProviderCredentialStore
    ) : this(context.dataStore, providerCredentialStore, runStartupMigration = true)

    private val json = Json { ignoreUnknownKeys = true }
    private val llmConfigKey = stringPreferencesKey("llm_config")

    /** A UI-safe recovery signal; it never contains credential or ciphertext data. */
    val providerCredentialRecoveryState = providerCredentialStore.recoveryState

    private val mutableProviderCredentialPersistenceState =
        MutableStateFlow<ProviderCredentialPersistenceState>(ProviderCredentialPersistenceState.Ready)
    /** Observable save result for callers that need to retry a transient storage failure. */
    val providerCredentialPersistenceState: StateFlow<ProviderCredentialPersistenceState> =
        mutableProviderCredentialPersistenceState.asStateFlow()

    init {
        if (runStartupMigration) {
            // Legacy encrypted-preference credentials are imported before DataStore
            // secrets are stripped. If either store is unavailable, updateConfig still strips
            // plaintext rather than using it as a recovery fallback.
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    providerCredentialStore.migrateLegacyCredentials()
                    updateConfig { it }
                } catch (_: Exception) {
                    // The credential store has no plaintext fallback; a later update retries.
                }
            }
        }
    }

    /**
     * Reads only authenticated direct-store values. Persisted JSON credentials are migration
     * input, never a runtime credential fallback.
     */
    private fun mergeSecretsForRead(persisted: LLMConfig): LLMConfig {
        val snapshot = (readCredentialSnapshot() as? CredentialSnapshotResult.Success)?.snapshot
            ?: return persisted.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
        return persisted.copy(
            apiKeys = snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey.orEmpty()
        )
    }

    /**
     * Supplies legacy DataStore values to a write only when direct credential reads are healthy.
     * This gives the one-time DataStore migration a source without exposing it to callers.
     */
    private fun mergeSecretsForUpdate(persisted: LLMConfig): LLMConfig {
        val snapshot = (readCredentialSnapshot() as? CredentialSnapshotResult.Success)?.snapshot
            ?: return persisted.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
        return persisted.copy(
            apiKeys = persisted.apiKeys + snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey ?: persisted.elevenLabsApiKey
        )
    }

    /**
     * Commits direct credentials before returning a stripped DataStore configuration.
     * Any failed direct-store mutation rolls back successful earlier mutations and aborts the
     * DataStore transaction, preserving the previously persisted configuration as retry input.
     */
    private fun storeSecretsAndStrip(config: LLMConfig): CredentialStripResult {
        val snapshot = when (val snapshotResult = readCredentialSnapshot()) {
            is CredentialSnapshotResult.Success -> snapshotResult.snapshot
            is CredentialSnapshotResult.Failure -> return CredentialStripResult.Failure(snapshotResult.state)
        }
        val desiredProviderApiKeys = linkedMapOf<String, String>()
        config.apiKeys.forEach { (provider, key) ->
            // Invalid historical JSON keys are deliberately stripped, never materialized as
            // direct records, and cannot block a safe migration.
            val credential = runCatching { ProviderCredentialId.ApiKey(provider) }.getOrNull()
                ?: return@forEach
            if (key.isNotBlank()) desiredProviderApiKeys[credential.providerName] = key
        }
        val attemptedCredentials = linkedSetOf<ProviderCredentialId>()
        val providerNames = linkedSetOf<String>().apply {
            addAll(snapshot.providerApiKeys.keys)
            addAll(desiredProviderApiKeys.keys)
        }

        for (providerName in providerNames) {
            val previousValue = snapshot.providerApiKeys[providerName]
            val desiredValue = desiredProviderApiKeys[providerName]
            if (previousValue == desiredValue) continue
            val credential = ProviderCredentialId.ApiKey(providerName)
            attemptedCredentials += credential
            val result = persistCredential(credential, desiredValue)
            if (result !is CredentialStoreResult.Success) {
                return CredentialStripResult.Failure(
                    restoreSnapshot(snapshot, attemptedCredentials) ?: result.toPersistenceState()
                )
            }
        }

        val desiredElevenLabsKey = config.elevenLabsApiKey.takeUnless(String::isBlank)
        if (snapshot.elevenLabsApiKey != desiredElevenLabsKey) {
            val credential = ProviderCredentialId.ElevenLabsApiKey
            attemptedCredentials += credential
            val result = persistCredential(credential, desiredElevenLabsKey)
            if (result !is CredentialStoreResult.Success) {
                return CredentialStripResult.Failure(
                    restoreSnapshot(snapshot, attemptedCredentials) ?: result.toPersistenceState()
                )
            }
        }

        return CredentialStripResult.Success(config.copy(apiKeys = emptyMap(), elevenLabsApiKey = ""))
    }

    private fun persistCredential(
        credential: ProviderCredentialId,
        value: String?
    ): CredentialStoreResult<Unit> = if (value == null) {
        providerCredentialStore.remove(credential)
    } else {
        providerCredentialStore.write(credential, value)
    }

    /** Restores the pre-update semantic credential snapshot after a partial direct-store write. */
    private fun restoreSnapshot(
        snapshot: CredentialSnapshot,
        attemptedCredentials: Set<ProviderCredentialId>
    ): ProviderCredentialPersistenceState? {
        for (credential in attemptedCredentials) {
            val previousValue = when (credential) {
                is ProviderCredentialId.ApiKey -> snapshot.providerApiKeys[credential.providerName]
                ProviderCredentialId.ElevenLabsApiKey -> snapshot.elevenLabsApiKey
                ProviderCredentialId.HuggingFaceToken -> null
            }
            val result = persistCredential(credential, previousValue)
            if (result !is CredentialStoreResult.Success) return result.toPersistenceState()
        }
        return null
    }

    private fun CredentialStoreResult<*>.toPersistenceState(): ProviderCredentialPersistenceState = when (this) {
        CredentialStoreResult.CredentialsMustBeReentered ->
            ProviderCredentialPersistenceState.CredentialsMustBeReentered
        CredentialStoreResult.StorageUnavailable -> ProviderCredentialPersistenceState.StorageUnavailable
        is CredentialStoreResult.Success -> ProviderCredentialPersistenceState.Ready
    }

    private fun readCredentialSnapshot(): CredentialSnapshotResult {
        if (providerCredentialStore.recoveryState.value ==
            ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            return CredentialSnapshotResult.Failure(
                ProviderCredentialPersistenceState.CredentialsMustBeReentered
            )
        }
        val providerApiKeys = providerCredentialStore.readProviderApiKeys()
        val elevenLabsApiKey = providerCredentialStore.read(ProviderCredentialId.ElevenLabsApiKey)
        if (providerApiKeys !is CredentialStoreResult.Success ||
            elevenLabsApiKey !is CredentialStoreResult.Success ||
            providerCredentialStore.recoveryState.value ==
                ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            val failure = when {
                providerCredentialStore.recoveryState.value ==
                    ProviderCredentialRecoveryState.CredentialsMustBeReentered ->
                    ProviderCredentialPersistenceState.CredentialsMustBeReentered
                providerApiKeys !is CredentialStoreResult.Success ->
                    providerApiKeys.toPersistenceState()
                else -> elevenLabsApiKey.toPersistenceState()
            }
            return CredentialSnapshotResult.Failure(failure)
        }
        return CredentialSnapshotResult.Success(
            CredentialSnapshot(providerApiKeys.value, elevenLabsApiKey.value)
        )
    }

    suspend fun resetProviderCredentialsForReentry(): CredentialStoreResult<Unit> =
        withContext(Dispatchers.IO) {
            providerCredentialStore.resetForReentry().also { result ->
                mutableProviderCredentialPersistenceState.value = result.toPersistenceState()
            }
        }

    private data class CredentialSnapshot(
        val providerApiKeys: Map<String, String>,
        val elevenLabsApiKey: String?
    )

    private sealed interface CredentialSnapshotResult {
        data class Success(val snapshot: CredentialSnapshot) : CredentialSnapshotResult
        data class Failure(val state: ProviderCredentialPersistenceState) : CredentialSnapshotResult
    }

    private sealed interface CredentialStripResult {
        data class Success(val strippedConfig: LLMConfig) : CredentialStripResult
        data class Failure(val state: ProviderCredentialPersistenceState) : CredentialStripResult
    }

    private class CredentialPersistenceAborted(
        val state: ProviderCredentialPersistenceState
    ) : RuntimeException(null, null, false, false)

    // Auto-reply preference keys
    private val autoReplyGlobalKey = booleanPreferencesKey("auto_reply_global")
    private val autoReplyWhatsAppKey = booleanPreferencesKey("auto_reply_whatsapp")
    private val autoReplyTelegramKey = booleanPreferencesKey("auto_reply_telegram")
    private val autoReplyDiscordKey = booleanPreferencesKey("auto_reply_discord")
    private val autoReplySmsKey = booleanPreferencesKey("auto_reply_sms")
    private val autoReplyEmailKey = booleanPreferencesKey("auto_reply_email")
    private val autoReplyDelayKey = intPreferencesKey("auto_reply_delay_minutes")
    private val autoReplyBlacklistKey = stringSetPreferencesKey("auto_reply_blacklist")
    private val autoReplyWhitelistKey = stringSetPreferencesKey("auto_reply_whitelist")
    private val autoReplyCustomPromptKey = stringPreferencesKey("auto_reply_custom_prompt")
    private val autoReplyCustomReplyKey = stringPreferencesKey("auto_reply_custom_reply")
    private val autoReplyMaxPerHourKey = intPreferencesKey("auto_reply_max_per_hour")

    val llmConfig: Flow<LLMConfig> = dataStore.data
        .map { preferences -> mergeSecretsForRead(decodeConfig(preferences[llmConfigKey])) }
        .flowOn(Dispatchers.IO)

    val autoReplyConfig: Flow<AutoReplyConfig> = dataStore.data.map { preferences ->
        AutoReplyConfig(
            // Auto-reply is opt-in (see AutoReplyConfig): default OFF until the
            // user explicitly enables each channel.
            globalEnabled = preferences[autoReplyGlobalKey] ?: false,
            whatsappEnabled = preferences[autoReplyWhatsAppKey] ?: false,
            telegramEnabled = preferences[autoReplyTelegramKey] ?: false,
            discordEnabled = preferences[autoReplyDiscordKey] ?: false,
            smsEnabled = preferences[autoReplySmsKey] ?: false,
            emailEnabled = preferences[autoReplyEmailKey] ?: false,
            replyDelayMinutes = preferences[autoReplyDelayKey] ?: 15,
            blacklistedContacts = preferences[autoReplyBlacklistKey] ?: emptySet(),
            whitelistedContacts = preferences[autoReplyWhitelistKey] ?: emptySet(),
            customPrompt = preferences[autoReplyCustomPromptKey],
            customReplyText = preferences[autoReplyCustomReplyKey],
            maxRepliesPerContactPerHour = preferences[autoReplyMaxPerHourKey] ?: 3
        )
    }

    suspend fun updateConfig(
        update: (LLMConfig) -> LLMConfig
    ): ProviderCredentialPersistenceState = withContext(Dispatchers.IO) {
        try {
            dataStore.edit { preferences ->
                val currentConfig = decodeConfig(preferences[llmConfigKey])
                val newConfig = update(mergeSecretsForUpdate(currentConfig))
                when (val result = storeSecretsAndStrip(newConfig)) {
                    is CredentialStripResult.Success -> {
                        preferences[llmConfigKey] = json.encodeToString(result.strippedConfig)
                    }
                    is CredentialStripResult.Failure -> throw CredentialPersistenceAborted(result.state)
                }
            }
            mutableProviderCredentialPersistenceState.value = ProviderCredentialPersistenceState.Ready
            ProviderCredentialPersistenceState.Ready
        } catch (aborted: CredentialPersistenceAborted) {
            mutableProviderCredentialPersistenceState.value = aborted.state
            aborted.state
        } catch (_: IOException) {
            mutableProviderCredentialPersistenceState.value =
                ProviderCredentialPersistenceState.StorageUnavailable
            ProviderCredentialPersistenceState.StorageUnavailable
        } catch (_: SecurityException) {
            mutableProviderCredentialPersistenceState.value =
                ProviderCredentialPersistenceState.StorageUnavailable
            ProviderCredentialPersistenceState.StorageUnavailable
        }
    }

    private fun decodeConfig(configStr: String?): LLMConfig = if (configStr != null) {
        try {
            json.decodeFromString<LLMConfig>(configStr)
        } catch (_: Exception) {
            LLMConfig()
        }
    } else {
        LLMConfig()
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
        dataStore.edit { preferences ->
            preferences[autoReplyGlobalKey] = config.globalEnabled
            preferences[autoReplyWhatsAppKey] = config.whatsappEnabled
            preferences[autoReplyTelegramKey] = config.telegramEnabled
            preferences[autoReplyDiscordKey] = config.discordEnabled
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
            if (config.customReplyText != null) {
                preferences[autoReplyCustomReplyKey] = config.customReplyText
            } else {
                preferences.remove(autoReplyCustomReplyKey)
            }
            preferences[autoReplyMaxPerHourKey] = config.maxRepliesPerContactPerHour
        }
    }
}
