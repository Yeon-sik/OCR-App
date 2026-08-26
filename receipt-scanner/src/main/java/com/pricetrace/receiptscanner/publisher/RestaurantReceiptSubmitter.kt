package com.pricetrace.receiptscanner.publisher

interface RestaurantReceiptSubmitter {
    suspend fun submit(payload: RestaurantReceiptSubmitPayload): RestaurantReceiptSubmitResult
}

sealed interface RestaurantReceiptSubmitResult {
    data class Success(val response: RestaurantReceiptSubmitResponse) : RestaurantReceiptSubmitResult
    data class Failure(
        val kind: PriceObservationFailureKind,
        val message: String? = null,
    ) : RestaurantReceiptSubmitResult
}
