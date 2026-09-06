package com.pricetrace.receiptocr.fitness

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
