package com.pricetrace.receiptocr.fitness

import android.annotation.SuppressLint
import android.content.Context
import com.pricetrace.receiptocr.security.EncryptedStringStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

data class NutritionSupabaseConfig(
    val url: String = "",
    val publishableKey: String = "",
    val userId: String = "",
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
) {
    val isConnectionConfigured: Boolean get() = validateConnection(url, publishableKey) == null
    val isSignedIn: Boolean get() = isConnectionConfigured && userId.isNotBlank() && accessToken.isNotBlank()

    companion object {
        fun validateConnection(rawUrl: String, rawKey: String): String? {
            val url = rawUrl.trim().trimEnd('/')
            val key = rawKey.trim()
            val uri = runCatching { URI(url) }.getOrNull()
            return when {
                uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ->
                    "Nutrition Supabase URL은 유효한 HTTPS 주소여야 합니다."
                uri.userInfo != null || uri.fragment != null || uri.rawQuery != null ->
                    "Nutrition Supabase URL에는 사용자 정보, query 또는 fragment를 넣을 수 없습니다."
                looksLikeServiceRole(key) ->
                    "service_role/secret key는 저장할 수 없습니다. publishable/anon key만 사용하세요."
                key.length !in 20..4096 || key.any(Char::isWhitespace) ->
                    "publishable/anon key를 확인하세요. service_role 키는 사용하지 마세요."
                else -> null
            }
        }

        private fun looksLikeServiceRole(key: String): Boolean {
            if (key.startsWith("sb_secret_", ignoreCase = true)) return true
            val payload = key.split('.').takeIf { it.size == 3 }?.get(1) ?: return false
            return runCatching {
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
                    .contains(Regex("\"role\"\\s*:\\s*\"service_role\"", RegexOption.IGNORE_CASE))
            }.getOrDefault(false)
        }
    }
}

interface NutritionSupabaseStore {
    fun read(): NutritionSupabaseConfig
    fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig>
    fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String): Result<NutritionSupabaseConfig>
    fun clearSession(): Boolean
}

internal class AndroidNutritionSupabaseStore(
    context: Context,
    preferencesName: String = CONNECTION_PREFERENCES,
    keyAlias: String = SESSION_KEY_ALIAS,
    sessionPreferencesName: String = SESSION_PREFERENCES,
) : NutritionSupabaseStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val encryptedSession = EncryptedStringStore(context, keyAlias, sessionPreferencesName)

    override fun read(): NutritionSupabaseConfig {
        val connection = NutritionSupabaseConfig(
            url = preferences.getString(KEY_URL, "").orEmpty(),
            publishableKey = preferences.getString(KEY_PUBLISHABLE, "").orEmpty(),
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
    override fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig> = runCatching {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedKey = publishableKey.trim()
        NutritionSupabaseConfig.validateConnection(normalizedUrl, normalizedKey)?.let { error(it) }
        val previous = read()
        if (previous.url != normalizedUrl || previous.publishableKey != normalizedKey) {
            check(encryptedSession.clear()) {
                "기존 Nutrition 로그인 세션을 안전하게 지우지 못해 프로젝트 변경을 중단했습니다."
            }
        }
        check(
            preferences.edit()
                .putString(KEY_URL, normalizedUrl)
                .putString(KEY_PUBLISHABLE, normalizedKey)
                .commit(),
        ) { "Nutrition Supabase 연결 정보를 저장하지 못했습니다." }
        read()
    }

    override fun saveSession(
        userId: String,
        email: String,
        accessToken: String,
        refreshToken: String,
    ): Result<NutritionSupabaseConfig> = runCatching {
        require(userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank()) {
            "Supabase 인증 응답에 필수 세션 값이 없습니다."
        }
        val existing = read()
        require(existing.userId.isBlank() || existing.userId == userId) {
            "현재 Nutrition 세션과 다른 사용자 ID를 덮어쓸 수 없습니다."
        }
        check(encryptedSession.save(KEY_SESSION, encodeSession(userId, email, accessToken, refreshToken))) {
            "암호화된 Nutrition 세션을 저장하지 못했습니다."
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
        ?: error("Missing session value")

    private companion object {
        const val CONNECTION_PREFERENCES = "nutrition_supabase_connection"
        const val SESSION_PREFERENCES = "nutrition_supabase_session"
        const val SESSION_KEY_ALIAS = "receipt_ocr_nutrition_supabase_session"
        const val KEY_URL = "url"
        const val KEY_PUBLISHABLE = "publishable_key"
        const val KEY_SESSION = "session"
        val json = Json { ignoreUnknownKeys = false }
    }
}
