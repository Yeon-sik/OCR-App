package com.pricetrace.receiptscanner.publisher

interface PriceObservationSubmitter {
    suspend fun submit(payload: PriceObservationSubmitPayload): PriceObservationSubmitResult
}
