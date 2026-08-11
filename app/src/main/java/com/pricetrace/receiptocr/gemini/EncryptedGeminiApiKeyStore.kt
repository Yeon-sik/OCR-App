package com.pricetrace.receiptocr.gemini

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface GeminiApiKeyStore {
    fun isConfigured(): Boolean
    fun read(): String?
    fun save(rawApiKey: String): Boolean
    fun clear(): Boolean
}

internal class EncryptedGeminiApiKeyStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : GeminiApiKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    override fun isConfigured(): Boolean = read() != null

    @Synchronized
    override fun read(): String? = runCatching {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return@runCatching null
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return@runCatching null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadSecretKey() ?: return@runCatching null,
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
        )
        val plainText = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            .toString(StandardCharsets.UTF_8)
        plainText.takeIf(::isValidApiKey)
    }.getOrNull()

    @Synchronized
    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun save(rawApiKey: String): Boolean {
        val apiKey = rawApiKey.trim()
        if (!isValidApiKey(apiKey)) return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateSecretKey())
            val ciphertext = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
            // The caller reports success only after both encrypted values are durably committed.
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
        }.getOrDefault(false)
    }

    @Synchronized
    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun clear(): Boolean {
        // Remove the encrypted payload before deleting the key that can decrypt it.
        val payloadCleared = preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()
        val keyCleared = runCatching {
            keyStore().apply {
                if (containsAlias(keyAlias)) deleteEntry(keyAlias)
            }
            true
        }.getOrDefault(false)
        return payloadCleared && keyCleared
    }

    private fun loadOrCreateSecretKey(): SecretKey = loadSecretKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }
        .generateKey()

    private fun loadSecretKey(): SecretKey? = keyStore().getKey(keyAlias, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun isValidApiKey(value: String): Boolean = value.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH &&
        value.none(Char::isWhitespace) &&
        value.none(Character::isISOControl)

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "pricetrace_gemini_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val DEFAULT_PREFERENCES_NAME = "gemini_api_credentials"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val MIN_KEY_LENGTH = 20
        const val MAX_KEY_LENGTH = 512
    }
}
