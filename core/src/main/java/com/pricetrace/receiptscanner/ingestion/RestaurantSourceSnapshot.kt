package com.pricetrace.receiptscanner.ingestion

/** Canonical source facts shared by payloads and their checkpoint fingerprints, never authority. */
internal data class RestaurantSourceSnapshot(val name: String?, val branchName: String?) {
    companion object {
        fun forNutrition(envelope: YeonsikOcrEnvelope, clientKey: String): RestaurantSourceSnapshot {
            envelope.receipt?.merchant?.let { return RestaurantSourceSnapshot(it.name, it.branchName) }
            if (envelope.schemaVersion == YEONSIK_OCR_V5_SCHEMA) {
                return RestaurantSourceSnapshot(
                    PurchaseNutritionIdentity.sellerName(envelope, clientKey),
                    PurchaseNutritionIdentity.sellerBranchName(envelope, clientKey),
                )
            }
            return RestaurantSourceSnapshot(envelope.merchantCandidate?.name, envelope.merchantCandidate?.branchName)
        }

        fun forMeal(envelope: YeonsikOcrEnvelope): RestaurantSourceSnapshot {
            envelope.receipt?.merchant?.let { return RestaurantSourceSnapshot(it.name, it.branchName) }
            if (envelope.schemaVersion != YEONSIK_OCR_V5_SCHEMA) {
                return if (envelope.mode == IngestionMode.RESTAURANT) forNutrition(envelope, "")
                    else RestaurantSourceSnapshot(null, null)
            }
            // Unknown links remain in the consensus; dropping them would select a partial source.
            val sources = envelope.consumption.flatMap { it.items }.map { it.nutritionClientKey }
                .distinct().map { forNutrition(envelope, it) }
            val name = sources.map { it.name }.distinct().singleOrNull()
            val branch = if (name != null) sources.map { it.branchName }.distinct().singleOrNull() else null
            return RestaurantSourceSnapshot(name, branch)
        }
    }
}
