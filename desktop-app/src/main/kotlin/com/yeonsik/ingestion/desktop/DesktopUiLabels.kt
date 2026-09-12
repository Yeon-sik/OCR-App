package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.VerificationBasis

/** Korean display names for the Desktop UI. Contract values remain in the domain models. */
object DesktopUiLabels {
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
        VerificationBasis.SOURCE_EVIDENCE -> "원본 증거 기반"
        VerificationBasis.MANUAL_CANONICAL_REVIEW -> "수동 검수"
    }

    fun ingestionReviewStatus(value: IngestionReviewStatus): String = when (value) {
        IngestionReviewStatus.READY -> "검수 가능"
        IngestionReviewStatus.NEEDS_REVIEW -> "검수 필요"
        IngestionReviewStatus.CONFLICT -> "충돌"
        IngestionReviewStatus.BLOCKED -> "차단됨"
    }

    fun projection(value: IngestionProjection): String = when (value) {
        IngestionProjection.PRICETRACE_RECEIPT -> "영수증(PriceTrace)"
        IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> "가격 관측(PriceTrace)"
        IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE -> "상점 후보(PriceTrace)"
        IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE -> "상품 후보(PriceTrace)"
        IngestionProjection.FITNESS_NUTRITION -> "영양 정보(Fitness)"
        IngestionProjection.FITNESS_MEAL -> "식사 기록(Fitness)"
        IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK -> "상품-영양 연결"
        IngestionProjection.CASHOS_RECEIPT -> "영수증(CashOS)"
        IngestionProjection.CASHOS_TRANSACTION -> "거래(CashOS)"
    }

    fun projectionStatus(value: ProjectionStatus): String = when (value) {
        ProjectionStatus.PENDING -> "대기"
        ProjectionStatus.BLOCKED -> "차단됨"
        ProjectionStatus.UPLOADED -> "전송 완료"
        ProjectionStatus.FAILED -> "실패"
        ProjectionStatus.DISABLED -> "비활성"
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
