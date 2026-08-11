package com.pricetrace.receiptocr.security

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

/** Small Android Keystore-backed value store shared by runtime credentials. */
internal class EncryptedStringStore(
    context: Context,
    private val keyAlias: String,
    preferencesName: String,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun read(name: String): String? = runCatching {
        val encodedIv = preferences.getString(ivKey(name), null) ?: return@runCatching null
        val encodedCiphertext = preferences.getString(ciphertextKey(name), null) ?: return@runCatching null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadSecretKey() ?: return@runCatching null,
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)).toString(StandardCharsets.UTF_8)
    }.getOrNull()

    @Synchronized
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun save(name: String, value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        preferences.edit()
            .putString(ivKey(name), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextKey(name), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    @Synchronized
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun clear(): Boolean {
        val payloadCleared = preferences.edit().clear().commit()
        val keyCleared = runCatching {
            keyStore().apply { if (containsAlias(keyAlias)) deleteEntry(keyAlias) }
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
    private fun ivKey(name: String): String = if (name.isBlank()) "iv" else "${name}_iv"
    private fun ciphertextKey(name: String): String = if (name.isBlank()) "ciphertext" else "${name}_ciphertext"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
