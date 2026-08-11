package com.pricetrace.receiptocr.gemini

import android.content.Context
import com.pricetrace.receiptocr.security.EncryptedStringStore

interface GeminiApiKeyStore {
    fun isConfigured(): Boolean
    fun read(): String?
    fun save(rawApiKey: String): Boolean
    fun clear(): Boolean
}

internal class EncryptedGeminiApiKeyStore(
    context: Context,
    keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : GeminiApiKeyStore {
    private val encryptedValues = EncryptedStringStore(context, keyAlias, preferencesName)

    override fun isConfigured(): Boolean = read() != null

    override fun read(): String? = encryptedValues.read(LEGACY_VALUE_SLOT)?.takeIf(::isValidApiKey)

    override fun save(rawApiKey: String): Boolean {
        val apiKey = rawApiKey.trim()
        return isValidApiKey(apiKey) && encryptedValues.save(LEGACY_VALUE_SLOT, apiKey)
    }

    override fun clear(): Boolean = encryptedValues.clear()

    private fun isValidApiKey(value: String): Boolean = value.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH &&
        value.none(Char::isWhitespace) &&
        value.none(Character::isISOControl)

    private companion object {
        const val DEFAULT_KEY_ALIAS = "pricetrace_gemini_api_key"
        const val DEFAULT_PREFERENCES_NAME = "gemini_api_credentials"
        const val LEGACY_VALUE_SLOT = ""
        const val MIN_KEY_LENGTH = 20
        const val MAX_KEY_LENGTH = 512
    }
}
