package com.pricetrace.receiptocr.pricetrace

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

data class PriceTraceSupabaseConfig(
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
                    "PriceTrace Supabase URL must be an HTTPS URL."
                uri.userInfo != null || uri.fragment != null || uri.rawQuery != null ->
                    "PriceTrace Supabase URL must not contain user info, query, or fragment."
                looksLikePrivilegedKey(key) ->
                    "service_role/secret keys are not allowed. Use a PriceTrace publishable key."
                key.length !in 20..4096 || key.any(Char::isWhitespace) ->
                    "PriceTrace publishable key is invalid."
                else -> null
            }
        }

        private fun looksLikePrivilegedKey(key: String): Boolean {
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

interface PriceTraceSupabaseStore {
    fun read(): PriceTraceSupabaseConfig
    fun saveConnection(url: String, publishableKey: String): Result<PriceTraceSupabaseConfig>
    fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String): Result<PriceTraceSupabaseConfig>
    fun clearSession(): Boolean
}
