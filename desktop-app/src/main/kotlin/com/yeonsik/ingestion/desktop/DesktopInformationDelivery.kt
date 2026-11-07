package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.information.*
import com.pricetrace.receiptocr.fitness.NutritionHttpTransport
import com.pricetrace.receiptocr.fitness.HttpsNutritionHttpTransport

class DesktopInformationDelivery(private val config: DesktopRuntimeConfig = DesktopRuntimeConfig.load(),
    transport: NutritionHttpTransport = HttpsNutritionHttpTransport(4 * InformationRecordCodec.MAX_BYTES)) {
    val connections = InformationConnectionAdapters(DesktopNutritionSupabaseStore(config.nutrition),
        DesktopCashOsSupabaseStore(config.cashOs), DesktopPriceTraceSupabaseStore(config.priceTrace), DesktopPriceTraceSupabaseStore(config.healthTrace))
    val gateway = InformationDeliveryGateway(connections, transport)

    suspend fun prepare(record: InformationRecord): PreparedInformationDelivery {
        val bootstrap = when (record.owner) {
            InformationOwner.FITNESS -> config.nutrition
            InformationOwner.CASHOS -> config.cashOs
            InformationOwner.PRICETRACE -> config.priceTrace
            InformationOwner.HEALTHTRACE -> config.healthTrace
        }
        val current = connections.read(record.owner)
        if (current.userId.isBlank() && current.origin == bootstrap.url.trim().trimEnd('/') &&
            current.publishableKey == bootstrap.publishableKey && bootstrap.email.isNotBlank() && bootstrap.password.isNotBlank()) {
            gateway.signIn(record.owner, bootstrap.email, bootstrap.password)
        }
        return gateway.prepare(record)
    }
}
