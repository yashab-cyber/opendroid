package com.opendroid.ai.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Centralized secure preferences using Android Keystore + EncryptedSharedPreferences.
 * All API keys and sensitive user data are AES-256 encrypted at rest.
 *
 * There is deliberately NO plaintext fallback: if the encrypted store cannot be
 * initialized (e.g. the keystore entry was invalidated), the corrupt store is
 * discarded and recreated; if that also fails, a SecurityException is thrown
 * rather than silently downgrading secrets to plaintext storage.
 */
object SecurePrefs {

    private const val PREFS_NAME = "opendroid_secure_prefs"
    private const val TAG = "SecurePrefs"

    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createEncryptedPrefs(context).also { instance = it }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (first: Exception) {
            // Most common cause: the master key was invalidated (device credential
            // reset, backup restore onto a new device). The stored ciphertext is
            // unrecoverable, so discard it and start a fresh encrypted store.
            Log.e(TAG, "EncryptedSharedPreferences init failed, recreating store: ${first.localizedMessage}")
            context.deleteSharedPreferences(PREFS_NAME)
            try {
                buildEncryptedPrefs(context)
            } catch (second: Exception) {
                // Never fall back to plaintext storage for secrets.
                throw SecurityException(
                    "Unable to initialize encrypted preferences; refusing plaintext fallback",
                    second
                )
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * One-time migration from old plaintext "opendroid_prefs" to encrypted store.
     * Call this once at app startup. Automatically deletes the old file after migration.
     */
    fun migrateFromPlaintext(context: Context) {
        val oldPrefs = context.getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        val securePrefs = get(context)

        // Only migrate if old prefs have data and secure prefs don't yet
        if (oldPrefs.all.isNotEmpty() && !securePrefs.contains("migration_done")) {
            val editor = securePrefs.edit()
            for ((key, value) in oldPrefs.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.putBoolean("migration_done", true)
            editor.apply()

            // Capture count before clearing
            val migratedCount = oldPrefs.all.size

            // Wipe the old plaintext prefs
            oldPrefs.edit().clear().apply()
            Log.d(TAG, "Migrated $migratedCount entries from plaintext to encrypted prefs")
        }
    }
}
