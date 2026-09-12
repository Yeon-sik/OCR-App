package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptocr.fitness.NutritionSupabaseConfig
import com.pricetrace.receiptocr.fitness.NutritionSupabaseStore
import com.pricetrace.receiptocr.pricetrace.CashOsSupabaseConfig
import com.pricetrace.receiptocr.pricetrace.CashOsSupabaseStore
import com.pricetrace.receiptocr.pricetrace.PriceTraceSupabaseConfig
import com.pricetrace.receiptocr.pricetrace.PriceTraceSupabaseStore
import com.pricetrace.receiptscanner.ingestion.EvidenceSupabaseConfig
import com.pricetrace.receiptscanner.ingestion.EvidenceSupabaseStore

/** In-memory desktop credential stores. The session file never receives these values. */
class DesktopEvidenceSupabaseStore(config: DesktopServiceConfig) : EvidenceSupabaseStore {
    private var current = EvidenceSupabaseConfig(
        url = config.url.trim().trimEnd('/'),
        publishableKey = config.publishableKey.trim(),
        userId = config.userId.trim(),
        email = config.email.trim(),
        accessToken = config.accessToken.trim(),
        refreshToken = config.refreshToken.trim(),
    )

    override fun read(): EvidenceSupabaseConfig = current

    override fun saveConnection(url: String, publishableKey: String): Result<EvidenceSupabaseConfig> = runCatching {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedKey = publishableKey.trim()
        EvidenceSupabaseConfig.validateConnection(normalizedUrl, normalizedKey)?.let(::error)
        if (current.url != normalizedUrl || current.publishableKey != normalizedKey) clearSession()
        current = current.copy(url = normalizedUrl, publishableKey = normalizedKey)
        current
    }

    override fun saveSession(
        userId: String,
        email: String,
        accessToken: String,
        refreshToken: String,
    ): Result<EvidenceSupabaseConfig> = runCatching {
        require(userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank()) {
            "Evidence authentication response did not contain a complete session."
        }
        require(current.userId.isBlank() || current.userId == userId) {
            "A different Evidence user is already signed in."
        }
        current = current.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
        current
    }

    override fun clearSession(): Boolean {
        current = current.copy(userId = "", email = "", accessToken = "", refreshToken = "")
        return true
    }
}

class DesktopPriceTraceSupabaseStore(config: DesktopServiceConfig) : PriceTraceSupabaseStore {
    private var current = PriceTraceSupabaseConfig(
        url = config.url.trim().trimEnd('/'),
        publishableKey = config.publishableKey.trim(),
        userId = config.userId.trim(),
        email = config.email.trim(),
        accessToken = config.accessToken.trim(),
        refreshToken = config.refreshToken.trim(),
    )

    override fun read(): PriceTraceSupabaseConfig = current

    override fun saveConnection(url: String, publishableKey: String): Result<PriceTraceSupabaseConfig> = runCatching {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedKey = publishableKey.trim()
        PriceTraceSupabaseConfig.validateConnection(normalizedUrl, normalizedKey)?.let(::error)
        if (current.url != normalizedUrl || current.publishableKey != normalizedKey) clearSession()
        current = current.copy(url = normalizedUrl, publishableKey = normalizedKey)
        current
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
        require(current.userId.isBlank() || current.userId == userId) {
            "A different PriceTrace user is already signed in."
        }
        current = current.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
        current
    }

    override fun clearSession(): Boolean {
        current = current.copy(userId = "", email = "", accessToken = "", refreshToken = "")
        return true
    }
}

class DesktopNutritionSupabaseStore(config: DesktopServiceConfig) : NutritionSupabaseStore {
    private var current = NutritionSupabaseConfig(
        url = config.url.trim().trimEnd('/'),
        publishableKey = config.publishableKey.trim(),
        userId = config.userId.trim(),
        email = config.email.trim(),
        accessToken = config.accessToken.trim(),
        refreshToken = config.refreshToken.trim(),
    )

    override fun read(): NutritionSupabaseConfig = current

    override fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig> = runCatching {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedKey = publishableKey.trim()
        NutritionSupabaseConfig.validateConnection(normalizedUrl, normalizedKey)?.let(::error)
        if (current.url != normalizedUrl || current.publishableKey != normalizedKey) clearSession()
        current = current.copy(url = normalizedUrl, publishableKey = normalizedKey)
        current
    }

    override fun saveSession(
        userId: String,
        email: String,
        accessToken: String,
        refreshToken: String,
    ): Result<NutritionSupabaseConfig> = runCatching {
        require(userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank()) {
            "Nutrition authentication response did not contain a complete session."
        }
        require(current.userId.isBlank() || current.userId == userId) {
            "A different Nutrition user is already signed in."
        }
        current = current.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
        current
    }

    override fun clearSession(): Boolean {
        current = current.copy(userId = "", email = "", accessToken = "", refreshToken = "")
        return true
    }
}

class DesktopCashOsSupabaseStore(config: DesktopServiceConfig) : CashOsSupabaseStore {
    private var current = CashOsSupabaseConfig(
        url = config.url.trim().trimEnd('/'),
        publishableKey = config.publishableKey.trim(),
        userId = config.userId.trim(),
        email = config.email.trim(),
        accessToken = config.accessToken.trim(),
        refreshToken = config.refreshToken.trim(),
    )

    override fun read(): CashOsSupabaseConfig = current

    override fun saveConnection(url: String, publishableKey: String): Result<CashOsSupabaseConfig> = runCatching {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedKey = publishableKey.trim()
        CashOsSupabaseConfig.validateConnection(normalizedUrl, normalizedKey)?.let(::error)
        if (current.url != normalizedUrl || current.publishableKey != normalizedKey) clearSession()
        current = current.copy(url = normalizedUrl, publishableKey = normalizedKey)
        current
    }

    override fun saveSession(
        userId: String,
        email: String,
        accessToken: String,
        refreshToken: String,
    ): Result<CashOsSupabaseConfig> = runCatching {
        require(userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank()) {
            "CashOS authentication response did not contain a complete session."
        }
        require(current.userId.isBlank() || current.userId == userId) {
            "A different CashOS user is already signed in."
        }
        current = current.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
        current
    }

    override fun clearSession(): Boolean {
        current = current.copy(userId = "", email = "", accessToken = "", refreshToken = "")
        return true
    }
}
