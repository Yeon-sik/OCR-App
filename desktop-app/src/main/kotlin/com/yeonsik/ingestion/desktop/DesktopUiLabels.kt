package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.VerificationBasis

/** Korean display names for the Desktop UI. Contract values remain in the domain models. */
object DesktopUiLabels {
    fun batchItemStatus(value: DesktopBatchItemStatus): String = when (value) {
        DesktopBatchItemStatus.QUEUED -> "대기"
        DesktopBatchItemStatus.IMPORTING -> "가져오는 중"
        DesktopBatchItemStatus.ARCHIVING -> "보관 중"
        DesktopBatchItemStatus.REVIEW_REQUIRED -> "검수 필요"
        DesktopBatchItemStatus.READY_TO_SUBMIT -> "검수 확정 · 전송 전 확인"
        DesktopBatchItemStatus.SUBMITTING -> "전송 중"
        DesktopBatchItemStatus.COMPLETED -> "완료"
        DesktopBatchItemStatus.FAILED -> "실패"
        DesktopBatchItemStatus.DUPLICATE -> "중복"
    }

    fun sourceAttachmentType(value: SourceAttachmentType): String = when (value) {
        SourceAttachmentType.RECEIPT -> "영수증"
        SourceAttachmentType.NUTRITION_LABEL -> "영양성분표"
        SourceAttachmentType.FOOD_PHOTO -> "음식 사진"
        SourceAttachmentType.MENU_PHOTO -> "메뉴 사진"
        SourceAttachmentType.PRODUCT_PHOTO -> "상품 사진"
        SourceAttachmentType.ORDER_HISTORY -> "주문 내역"
        SourceAttachmentType.PAYMENT_HISTORY -> "결제 내역"
    }

    fun verificationBasis(value: VerificationBasis): String = when (value) {
        VerificationBasis.SOURCE_EVIDENCE -> "원본 자료를 보고 확인"
        VerificationBasis.MANUAL_CANONICAL_REVIEW -> "원본 없이 직접 확인"
    }

    fun ingestionReviewStatus(value: IngestionReviewStatus): String = when (value) {
        IngestionReviewStatus.READY -> "검수 가능"
        IngestionReviewStatus.NEEDS_REVIEW -> "검수 필요"
        IngestionReviewStatus.CONFLICT -> "충돌"
        IngestionReviewStatus.BLOCKED -> "차단됨"
    }

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

    fun bundleValidationStatus(value: DesktopBundleValidationStatus): String = when (value) {
        DesktopBundleValidationStatus.VALID -> "유효"
        DesktopBundleValidationStatus.INVALID -> "무효"
    }

    fun evidenceArchiveStatus(value: DesktopEvidenceArchiveStatus): String = when (value) {
        DesktopEvidenceArchiveStatus.NOT_STARTED -> "시작 전"
        DesktopEvidenceArchiveStatus.ARCHIVING -> "보관 중"
        DesktopEvidenceArchiveStatus.ARCHIVED -> "보관 완료"
        DesktopEvidenceArchiveStatus.FAILED -> "실패"
    }

    fun artifactStatus(verified: Boolean, evidenceReady: Boolean, issues: List<String>): String = when {
        verified -> "검수 완료"
        evidenceReady -> "검수 가능"
        issues.isEmpty() -> "차단됨"
        else -> "차단됨: ${issues.joinToString()}"
    }

    fun fileAvailability(readable: Boolean): String = if (readable) "읽을 수 있음" else "파일 없음"
}

/** Counts are intentionally per projection; one success never means every destination succeeded. */
internal fun deliveryProgressLabel(statuses: List<ProjectionStatus>): String {
    val active = statuses.filterNot { it == ProjectionStatus.DISABLED }
    return "전송 완료 ${active.count { it == ProjectionStatus.UPLOADED }}/${active.size} · 실패 ${active.count { it == ProjectionStatus.FAILED }} · 확인 필요 ${active.count { it == ProjectionStatus.BLOCKED }}"
}
