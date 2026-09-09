package com.mj.assistant.brain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage for the Local AI Brain's Bearer API key.
 *
 * Requirement from the integration spec: "API key source code mein hardcode
 * mat karo. Android secure storage/configuration se load karo. API key logs
 * mein print mat karo." This class satisfies all three without pulling in
 * androidx.security:security-crypto (this project deliberately avoids new
 * Gradle dependencies — see ChatHistoryStore's doc comment — since the build
 * toolchain here can't be verified against a network). Instead it uses the
 * Android Keystore directly (built into the platform since API 18) to hold
 * an AES-256-GCM key that never leaves hardware/OS-backed storage, and only
 * the ciphertext + IV are persisted in SharedPreferences.
 *
 * The key is entered once by the user in Settings (see SettingsScreen) and
 * is never written into source, build config, or logs anywhere in this
 * integration.
 */
class BrainSecureStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** Persists the API key encrypted at rest. Returns false (and stores nothing) for a blank key. */
    fun saveApiKey(apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(cipherText, Base64.NO_WRAP))
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Returns the decrypted API key, or null if none is configured / decryption fails. Never logged by callers. */
    fun getApiKey(): String? {
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        val ctB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun hasApiKey(): Boolean = prefs.contains(KEY_CIPHERTEXT)

    fun clearApiKey() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_ENABLED, enabled).apply() }

    /** Base URL is configuration, not a secret — kept in plain SharedPreferences, defaulting to the spec's loopback endpoint. */
    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_URL) ?: DEFAULT_URL
    fun setBaseUrl(url: String) {
        val trimmed = url.trim()
        prefs.edit().putString(KEY_BASE_URL, trimmed.ifEmpty { DEFAULT_URL }).apply()
    }

    companion object {
        private const val PREFS = "mj_brain_secure"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mj_brain_api_key_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        const val DEFAULT_URL = "http://127.0.0.1:8090/v1/command"
    }
}
