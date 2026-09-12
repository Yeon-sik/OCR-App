package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptocr.fitness.FitnessCanonicalProjectionSubmitter
import com.pricetrace.receiptocr.fitness.FitnessMealProjectionSubmitter
import com.pricetrace.receiptocr.fitness.FitnessProductNutritionLinkProjectionSubmitter
import com.pricetrace.receiptocr.fitness.NutritionAuthOutcome
import com.pricetrace.receiptocr.fitness.NutritionGatewayFailure
import com.pricetrace.receiptocr.fitness.NutritionSupabaseGateway
import com.pricetrace.receiptocr.fitness.PriceTraceProductRevisionReader
import com.pricetrace.receiptocr.fitness.ProductRevisionReadOutcome
import com.pricetrace.receiptocr.fitness.ProductRevisionReadResult
import com.pricetrace.receiptocr.pricetrace.CashOsAuthOutcome
import com.pricetrace.receiptocr.pricetrace.CashOsCanonicalProjectionSubmitter
import com.pricetrace.receiptocr.pricetrace.CashOsReceiptGateway
import com.pricetrace.receiptocr.pricetrace.PriceObservationGateway
import com.pricetrace.receiptocr.pricetrace.PriceTraceCanonicalGateway
import com.pricetrace.receiptocr.pricetrace.PriceTraceCanonicalProjectionSubmitter
import com.pricetrace.receiptocr.pricetrace.PriceTraceProductCandidateProjectionSubmitter
import com.pricetrace.receiptocr.pricetrace.PriceTraceProductReadOutcome
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.EvidenceArchivePort
import com.pricetrace.receiptscanner.ingestion.EvidenceSupabaseArchivePort
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind

/** Shared core gateway wiring for the Desktop console. No Desktop-specific submitter is added. */
class DesktopProjectionBundle(
    private val config: DesktopRuntimeConfig = DesktopRuntimeConfig.load(),
) {
    val evidenceStore = DesktopEvidenceSupabaseStore(config.evidence)
    val evidenceArchivePort: EvidenceArchivePort = EvidenceSupabaseArchivePort(
        store = evidenceStore,
        email = config.evidence.email,
        password = config.evidence.password,
    )
    val priceTraceStore = DesktopPriceTraceSupabaseStore(config.priceTrace)
    val nutritionStore = DesktopNutritionSupabaseStore(config.nutrition)
    val cashOsStore = DesktopCashOsSupabaseStore(config.cashOs)

    val priceObservationGateway = PriceObservationGateway(priceTraceStore)
    val priceTraceCanonicalGateway = PriceTraceCanonicalGateway(priceTraceStore)
    val nutritionGateway = NutritionSupabaseGateway(nutritionStore)
    val cashOsReceiptGateway = CashOsReceiptGateway(cashOsStore)

    private val productRevisionReader = PriceTraceProductRevisionReader { catalogProductId ->
        when (val result = priceTraceCanonicalGateway.readExactProductRevision(catalogProductId)) {
            is PriceTraceProductReadOutcome.Success ->
                ProductRevisionReadOutcome.Success(ProductRevisionReadResult(result.revision))
            is PriceTraceProductReadOutcome.Failure ->
                ProductRevisionReadOutcome.Failure(
                    reason = result.kind.toNutritionGatewayFailure(),
                    message = result.message,
                )
        }
    }

    val submitters: Map<IngestionProjection, IngestionProjectionSubmitter> = mapOf(
        IngestionProjection.PRICETRACE_RECEIPT to PriceTraceCanonicalProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION to PriceTraceCanonicalProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE to PriceTraceCanonicalProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to PriceTraceProductCandidateProjectionSubmitter(priceTraceCanonicalGateway),
        IngestionProjection.FITNESS_NUTRITION to FitnessCanonicalProjectionSubmitter(nutritionGateway),
        IngestionProjection.FITNESS_MEAL to FitnessMealProjectionSubmitter(nutritionGateway),
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK to FitnessProductNutritionLinkProjectionSubmitter(
            nutritionGateway = nutritionGateway,
            productRevisionReader = productRevisionReader,
        ),
        IngestionProjection.CASHOS_RECEIPT to CashOsCanonicalProjectionSubmitter(cashOsReceiptGateway),
        IngestionProjection.CASHOS_TRANSACTION to CashOsCanonicalProjectionSubmitter(cashOsReceiptGateway),
    )

    suspend fun ensureAuthenticated(
        activeProjections: Set<IngestionProjection>,
        envelope: YeonsikOcrEnvelope? = null,
    ): List<String> = buildList {
        val priceTraceIdentityNeeded = envelope?.receipt != null && activeProjections.any {
            it == IngestionProjection.CASHOS_RECEIPT ||
                it == IngestionProjection.FITNESS_NUTRITION ||
                it == IngestionProjection.FITNESS_MEAL
        }
        if (activeProjections.any(::usesPriceTrace) || priceTraceIdentityNeeded) ensurePriceTrace()?.let(::add)
        if (activeProjections.any(::usesNutrition)) ensureNutrition()?.let(::add)
        if (activeProjections.any { it == IngestionProjection.CASHOS_RECEIPT || it == IngestionProjection.CASHOS_TRANSACTION }) {
            ensureCashOs()?.let(::add)
        }
    }

    private suspend fun ensurePriceTrace(): String? {
        val current = priceTraceStore.read()
        if (current.isSignedIn) return null
        val credentials = config.priceTrace
        if (credentials.email.isBlank() || credentials.password.isBlank()) {
            return "PriceTrace credentials are missing: provide an access token or email/password."
        }
        return when (val outcome = priceObservationGateway.signIn(credentials.email, credentials.password)) {
            is com.pricetrace.receiptocr.pricetrace.PriceTraceAuthOutcome.Success -> null
            is com.pricetrace.receiptocr.pricetrace.PriceTraceAuthOutcome.Failure ->
                "PriceTrace sign-in failed: ${outcome.kind.name.lowercase()}"
        }
    }

    private suspend fun ensureNutrition(): String? {
        val current = nutritionStore.read()
        if (current.isSignedIn) return null
        val credentials = config.nutrition
        if (credentials.email.isBlank() || credentials.password.isBlank()) {
            return "Fitness Nutrition credentials are missing: provide an access token or email/password."
        }
        return when (val outcome = nutritionGateway.signIn(credentials.email, credentials.password)) {
            is NutritionAuthOutcome.Success -> null
            is NutritionAuthOutcome.Failure -> "Fitness Nutrition sign-in failed: ${outcome.reason.name.lowercase()}"
        }
    }

    private suspend fun ensureCashOs(): String? {
        val current = cashOsStore.read()
        if (current.isSignedIn) return null
        val credentials = config.cashOs
        if (credentials.email.isBlank() || credentials.password.isBlank()) {
            return "CashOS credentials are missing: provide an access token or email/password."
        }
        return when (val outcome = cashOsReceiptGateway.signIn(credentials.email, credentials.password)) {
            is CashOsAuthOutcome.Success -> null
            is CashOsAuthOutcome.Failure -> "CashOS sign-in failed: ${outcome.kind.name.lowercase()}"
        }
    }

    private fun usesPriceTrace(projection: IngestionProjection): Boolean = projection in setOf(
        IngestionProjection.PRICETRACE_RECEIPT,
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE,
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
    )

    private fun usesNutrition(projection: IngestionProjection): Boolean = projection in setOf(
        IngestionProjection.FITNESS_NUTRITION,
        IngestionProjection.FITNESS_MEAL,
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
    )

    private fun PriceObservationFailureKind.toNutritionGatewayFailure(): NutritionGatewayFailure = when (this) {
        PriceObservationFailureKind.NOT_CONFIGURED -> NutritionGatewayFailure.NOT_CONFIGURED
        PriceObservationFailureKind.AUTHENTICATION -> NutritionGatewayFailure.AUTHENTICATION
        PriceObservationFailureKind.NETWORK,
        PriceObservationFailureKind.NETWORK_TIMEOUT -> NutritionGatewayFailure.NETWORK
        PriceObservationFailureKind.SERVER -> NutritionGatewayFailure.SERVER
        PriceObservationFailureKind.IDEMPOTENCY_MISMATCH,
        PriceObservationFailureKind.INVALID_SELECTION,
        PriceObservationFailureKind.CONTRACT -> NutritionGatewayFailure.CONTRACT
    }
}
