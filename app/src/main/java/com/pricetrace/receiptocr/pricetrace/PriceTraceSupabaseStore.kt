package com.pricetrace.receiptocr.pricetrace

import android.annotation.SuppressLint
import android.content.Context
import com.pricetrace.receiptocr.BuildConfig
import com.pricetrace.receiptocr.security.EncryptedStringStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
internal class AndroidPriceTraceSupabaseStore(
    context: Context,
    preferencesName: String = CONNECTION_PREFERENCES,
    keyAlias: String = SESSION_KEY_ALIAS,
    sessionPreferencesName: String = SESSION_PREFERENCES,
    private val defaultUrl: String = BuildConfig.DEFAULT_PRICETRACE_SUPABASE_URL,
    private val defaultPublishableKey: String = BuildConfig.DEFAULT_PRICETRACE_SUPABASE_PUBLISHABLE_KEY,
) : PriceTraceSupabaseStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val encryptedSession = EncryptedStringStore(context, keyAlias, sessionPreferencesName)

    override fun read(): PriceTraceSupabaseConfig {
        val connection = PriceTraceSupabaseConfig(
            url = preferences.getString(KEY_URL, null).orEmpty().ifBlank { defaultUrl },
            publishableKey = preferences.getString(KEY_PUBLISHABLE, null).orEmpty()
                .ifBlank { defaultPublishableKey },
        )
        val session = encryptedSession.read(KEY_SESSION)?.let(::decodeSession)
        return if (session == null) connection else connection.copy(
            userId = session.userId,
            email = session.email,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
        )
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun saveConnection(url: String, publishableKey: String): Result<PriceTraceSupabaseConfig> = runCatching {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedKey = publishableKey.trim()
        PriceTraceSupabaseConfig.validateConnection(normalizedUrl, normalizedKey)?.let { error(it) }
        val previous = read()
        if (previous.url != normalizedUrl || previous.publishableKey != normalizedKey) {
            check(encryptedSession.clear()) { "PriceTrace session could not be cleared after project change." }
        }
        check(
            preferences.edit()
                .putString(KEY_URL, normalizedUrl)
                .putString(KEY_PUBLISHABLE, normalizedKey)
                .commit(),
        ) { "PriceTrace Supabase connection could not be saved." }
        read()
    }

    override fun saveSession(
        userId: String,
        email: String,
        accessToken: String,
        refreshToken: String,
    ): Result<PriceTraceSupabaseConfig> = runCatching {
        require(userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank()) {
            "PriceTrace authentication response did not contain a complete session."
        }
        val existing = read()
        require(existing.userId.isBlank() || existing.userId == userId) {
            "A different PriceTrace user is already signed in."
        }
        check(encryptedSession.save(KEY_SESSION, encodeSession(userId, email, accessToken, refreshToken))) {
            "PriceTrace session could not be encrypted and saved."
        }
        read()
    }

    override fun clearSession(): Boolean = encryptedSession.clear()

    private data class StoredSession(
        val userId: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
    )

    private fun encodeSession(userId: String, email: String, accessToken: String, refreshToken: String): String =
        json.encodeToString(
            JsonElement.serializer(),
            JsonObject(
                mapOf(
                    "user_id" to JsonPrimitive(userId),
                    "email" to JsonPrimitive(email),
                    "access_token" to JsonPrimitive(accessToken),
                    "refresh_token" to JsonPrimitive(refreshToken),
                ),
            ),
        )

    private fun decodeSession(value: String): StoredSession? = runCatching {
        val root = json.parseToJsonElement(value) as JsonObject
        StoredSession(
            userId = root.string("user_id"),
            email = root.string("email"),
            accessToken = root.string("access_token"),
            refreshToken = root.string("refresh_token"),
        )
    }.getOrNull()

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull
        ?: error("Missing PriceTrace session value")

    private companion object {
        const val CONNECTION_PREFERENCES = "pricetrace_supabase_connection"
        const val SESSION_PREFERENCES = "pricetrace_supabase_session"
        const val SESSION_KEY_ALIAS = "receipt_ocr_pricetrace_supabase_session"
        const val KEY_URL = "url"
        const val KEY_PUBLISHABLE = "publishable_key"
        const val KEY_SESSION = "session"
        val json = Json { ignoreUnknownKeys = false }
    }
}
