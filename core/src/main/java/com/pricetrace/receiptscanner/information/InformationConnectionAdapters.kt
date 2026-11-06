package com.pricetrace.receiptscanner.information

import com.pricetrace.receiptocr.fitness.NutritionSupabaseStore
import com.pricetrace.receiptocr.pricetrace.CashOsSupabaseStore
import com.pricetrace.receiptocr.pricetrace.PriceTraceSupabaseStore

/** Reuses existing stores; Health has its own namespace even when projects share a host. */
class InformationConnectionAdapters(
    private val fitness: NutritionSupabaseStore,
    private val cash: CashOsSupabaseStore,
    private val price: PriceTraceSupabaseStore,
    private val health: PriceTraceSupabaseStore,
) : InformationConnectionStore {
    override fun read(owner: InformationOwner): InformationConnection = when (owner) {
        InformationOwner.FITNESS -> fitness.read().let { InformationConnection(it.url, it.publishableKey, it.userId, it.email, it.accessToken, it.refreshToken) }
        InformationOwner.CASHOS -> cash.read().let { InformationConnection(it.url, it.publishableKey, it.userId, it.email, it.accessToken, it.refreshToken) }
        InformationOwner.PRICETRACE -> price.read().let { InformationConnection(it.url, it.publishableKey, it.userId, it.email, it.accessToken, it.refreshToken) }
        InformationOwner.HEALTHTRACE -> health.read().let { InformationConnection(it.url, it.publishableKey, it.userId, it.email, it.accessToken, it.refreshToken) }
    }
    override fun saveConnection(owner: InformationOwner, url: String, publishableKey: String) {
        when (owner) {
            InformationOwner.FITNESS -> fitness.saveConnection(url, publishableKey).getOrThrow()
            InformationOwner.CASHOS -> cash.saveConnection(url, publishableKey).getOrThrow()
            InformationOwner.PRICETRACE -> price.saveConnection(url, publishableKey).getOrThrow()
            InformationOwner.HEALTHTRACE -> health.saveConnection(url, publishableKey).getOrThrow()
        }
    }
    override fun saveSession(owner: InformationOwner, userId: String, email: String, accessToken: String, refreshToken: String) {
        when (owner) {
            InformationOwner.FITNESS -> fitness.saveSession(userId, email, accessToken, refreshToken).getOrThrow()
            InformationOwner.CASHOS -> cash.saveSession(userId, email, accessToken, refreshToken).getOrThrow()
            InformationOwner.PRICETRACE -> price.saveSession(userId, email, accessToken, refreshToken).getOrThrow()
            InformationOwner.HEALTHTRACE -> health.saveSession(userId, email, accessToken, refreshToken).getOrThrow()
        }
    }
    override fun clearSession(owner: InformationOwner) {
        check(when (owner) {
            InformationOwner.FITNESS -> fitness.clearSession()
            InformationOwner.CASHOS -> cash.clearSession()
            InformationOwner.PRICETRACE -> price.clearSession()
            InformationOwner.HEALTHTRACE -> health.clearSession()
        }) { "로그인 정보를 지우지 못했습니다." }
    }
}
