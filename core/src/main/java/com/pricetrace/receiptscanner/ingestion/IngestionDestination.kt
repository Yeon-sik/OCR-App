package com.pricetrace.receiptscanner.ingestion

enum class IngestionDestination(val label: String) { PRICE_TRACE("PriceTrace"), CASH_OS("CashOS"), FITNESS("Fitness") }

fun IngestionProjection.destination(): IngestionDestination = when (this) {
    IngestionProjection.CASHOS_RECEIPT, IngestionProjection.CASHOS_TRANSACTION -> IngestionDestination.CASH_OS
    IngestionProjection.FITNESS_NUTRITION, IngestionProjection.FITNESS_MEAL,
    IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> IngestionDestination.FITNESS
    else -> IngestionDestination.PRICE_TRACE
}

fun IngestionDestination.contentLabel(projections: Set<IngestionProjection>): String = when (this) {
    IngestionDestination.PRICE_TRACE -> "구매 상품·영수증·가격"
    IngestionDestination.CASH_OS -> "구매 내역"
    IngestionDestination.FITNESS -> if (IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK in projections) "영양 정보·구매 상품 연결" else "영양 정보"
}
