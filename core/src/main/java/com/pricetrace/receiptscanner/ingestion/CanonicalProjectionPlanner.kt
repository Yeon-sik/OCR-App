package com.pricetrace.receiptscanner.ingestion

/** The only routing authority for canonical artifacts. JSON projection_targets are hints. */
data class CanonicalProjectionPlan(
    val eligible: Set<IngestionProjection>,
    val disabled: Set<IngestionProjection>,
    val dependencies: Map<IngestionProjection, Set<IngestionProjection>>,
) {
    fun isEligible(projection: IngestionProjection): Boolean = projection in eligible
}

object CanonicalProjectionPlanner {
    fun plan(envelope: YeonsikOcrEnvelope): CanonicalProjectionPlan {
        val eligible = buildSet {
            if (envelope.receipt != null) {
                add(IngestionProjection.PRICETRACE_RECEIPT)
                add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                add(IngestionProjection.CASHOS_RECEIPT)
            }
            if (envelope.priceObservations.isNotEmpty() &&
                envelope.priceObservations.all { it.netAmountMinor != null }
            ) {
                add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
            }
            if (envelope.purchaseRecords.any(PurchaseRecord::priceTraceSourceEligible)) {
                add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
            }
            if (envelope.purchaseRecords.any(PurchaseRecord::cashOsTransactionEligible)) {
                add(IngestionProjection.CASHOS_TRANSACTION)
            }
            if (envelope.productCandidates.isNotEmpty()) {
                add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
            }
            if (envelope.purchaseRecords.isEmpty() && envelope.nutrition.isNotEmpty()) {
                add(IngestionProjection.FITNESS_NUTRITION)
            }
            val mealValuesComplete = envelope.consumption.all(IngestionConsumption::isCompleteForFitnessMeal)
            // v1 keeps its historical configured-but-blocked meal state. v2 keeps the stricter
            // verified-consumption gate. v3 makes eligibility structural: complete consumption
            // is enough to expose the sink, while confirmation remains a separate Core action.
            val mealEligible = when (envelope.schemaVersion) {
                YEONSIK_OCR_SCHEMA -> true
                YEONSIK_OCR_V2_SCHEMA -> mealValuesComplete &&
                    envelope.consumption.all { it.status == ConsumptionVerificationStatus.USER_VERIFIED }
                YEONSIK_OCR_V3_SCHEMA -> mealValuesComplete
                else -> mealValuesComplete
            }
            if (envelope.purchaseRecords.isEmpty() &&
                envelope.nutrition.isNotEmpty() &&
                envelope.consumption.isNotEmpty() &&
                mealEligible
            ) {
                add(IngestionProjection.FITNESS_MEAL)
            }
            if (envelope.purchaseRecords.isEmpty() &&
                envelope.productCandidates.isNotEmpty() &&
                envelope.nutrition.any { it is IngestionNutrition.ProductLabel }
            ) {
                add(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK)
            }
            if (envelope.merchantCandidate != null && envelope.receipt == null &&
                envelope.priceObservations.isEmpty() && envelope.nutrition.isEmpty() &&
                envelope.productCandidates.isEmpty() && envelope.consumption.isEmpty()
            ) {
                add(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE)
            }
        }
        val withDependencies = withDependencies(eligible, envelope)
        val dependencies = withDependencies.associateWith { projection ->
            dependenciesFor(projection, envelope).intersect(withDependencies)
        }
        return CanonicalProjectionPlan(
            eligible = withDependencies,
            disabled = IngestionProjection.entries.toSet() - withDependencies,
            dependencies = dependencies,
        )
    }

    private fun withDependencies(
        requested: Set<IngestionProjection>,
        envelope: YeonsikOcrEnvelope,
    ): Set<IngestionProjection> = buildSet {
        addAll(requested)
        if (envelope.receipt != null && requested.any {
                it == IngestionProjection.PRICETRACE_PRICE_OBSERVATION ||
                    it == IngestionProjection.CASHOS_RECEIPT ||
                    it == IngestionProjection.FITNESS_NUTRITION ||
                    it == IngestionProjection.FITNESS_MEAL
            }) {
            add(IngestionProjection.PRICETRACE_RECEIPT)
        }
        if (requested.contains(IngestionProjection.PRICETRACE_PRICE_OBSERVATION) &&
            envelope.purchaseRecords.any { record ->
                record.priceTraceSourceEligible &&
                    record.purchaseKind == PurchaseKind.RETAIL &&
                    record.lineItems.any { line ->
                        line.productClientKey in envelope.productCandidates.map(ProductCandidate::clientKey).toSet()
                    }
            }
        ) {
            add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
        }
        if (requested.contains(IngestionProjection.PRICETRACE_PRICE_OBSERVATION) &&
            envelope.priceObservations.isNotEmpty() &&
            envelope.priceObservations.all { it.netAmountMinor != null } &&
            envelope.priceObservations.any { it.kind == StandalonePriceObservationKind.RETAIL_PURCHASE } &&
            envelope.productCandidates.isNotEmpty()
        ) {
            add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
        }
        if (requested.contains(IngestionProjection.FITNESS_NUTRITION) &&
            envelope.nutrition.filterIsInstance<IngestionNutrition.RestaurantMenuEstimate>().let { menuItems ->
                menuItems.isNotEmpty() && menuItems.all { item ->
                    envelope.priceObservations.singleOrNull { observation ->
                        observation.clientKey == item.priceObservationClientKey
                    }?.netAmountMinor != null
                }
            }
        ) {
            add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
        }
        if (requested.contains(IngestionProjection.FITNESS_MEAL)) add(IngestionProjection.FITNESS_NUTRITION)
        if (requested.contains(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK)) {
            add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
            add(IngestionProjection.FITNESS_NUTRITION)
        }
    }

    /** Shared dependency graph consumed by planning and by the submission orchestrator. */
    fun dependenciesFor(
        projection: IngestionProjection,
        envelope: YeonsikOcrEnvelope,
    ): Set<IngestionProjection> = buildSet {
        when (projection) {
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> {
                if (envelope.receipt != null) add(IngestionProjection.PRICETRACE_RECEIPT)
                if (envelope.priceObservations.any {
                        it.kind == StandalonePriceObservationKind.RETAIL_PURCHASE
                    }) {
                    add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
                }
                if (envelope.purchaseRecords.any { record ->
                        record.priceTraceSourceEligible &&
                            record.purchaseKind == PurchaseKind.RETAIL &&
                            record.lineItems.any { line ->
                                line.productClientKey in envelope.productCandidates.map(ProductCandidate::clientKey).toSet()
                            }
                    }
                ) {
                    add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
                }
            }
            IngestionProjection.CASHOS_RECEIPT,
            IngestionProjection.FITNESS_NUTRITION -> {
                if (projection == IngestionProjection.CASHOS_RECEIPT && envelope.receipt != null) {
                    add(IngestionProjection.PRICETRACE_RECEIPT)
                }
                if (projection == IngestionProjection.FITNESS_NUTRITION) {
                    if (envelope.receipt != null) add(IngestionProjection.PRICETRACE_RECEIPT)
                    if (envelope.nutrition.any { it is IngestionNutrition.RestaurantMenuEstimate }) {
                        add(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                    }
                }
            }
            IngestionProjection.FITNESS_MEAL -> add(IngestionProjection.FITNESS_NUTRITION)
            IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> {
                add(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
                add(IngestionProjection.FITNESS_NUTRITION)
            }
            else -> Unit
        }
    }
}
