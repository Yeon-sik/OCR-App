package com.pricetrace.receiptocr

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus

internal object ReceiptDeliveryLabels {
    fun projection(value: IngestionProjection): String = when (value) {
        IngestionProjection.PRICETRACE_RECEIPT -> "PriceTrace · 영수증 기록"
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> "PriceTrace · 가격 기록"
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE -> "PriceTrace · 가게 등록 요청"
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE -> "PriceTrace · 상품 등록 요청"
        IngestionProjection.FITNESS_NUTRITION -> "Fitness · 영양 정보"
        IngestionProjection.FITNESS_MEAL -> "Fitness · 식사 기록"
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> "Fitness · 상품과 영양 정보 연결"
        IngestionProjection.CASHOS_RECEIPT -> "CashOS · 영수증 기록"
        IngestionProjection.CASHOS_TRANSACTION -> "CashOS · 거래 기록"
    }

    fun projectionStatus(value: ProjectionStatus): String = when (value) {
        ProjectionStatus.PENDING -> "아직 보내지 않음"
        ProjectionStatus.BLOCKED -> "보내기 전 확인 필요"
        ProjectionStatus.UPLOADED -> "전송 완료"
        ProjectionStatus.FAILED -> "실패"
        ProjectionStatus.DISABLED -> "이 작업의 전송 대상 아님"
    }

}

internal fun archiveDisplay(value: String?): String = when (value) {
    "VALID" -> "자료 확인 완료"
    "INVALID" -> "자료를 열 수 없음"
    "NOT_STARTED" -> "보관 전"
    "ARCHIVING" -> "보관 중"
    "ARCHIVED" -> "보관 완료"
    "FAILED" -> "보관 실패 · 다시 시도 필요"
    else -> "상태 미확인"
}
