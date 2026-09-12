package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopUiLabelsTest {
    @Test
    fun contractEnumsUseKoreanDisplayLabels() {
        assertEquals("원본 증거 기반", DesktopUiLabels.verificationBasis(VerificationBasis.SOURCE_EVIDENCE))
        assertEquals("수동 검수", DesktopUiLabels.verificationBasis(VerificationBasis.MANUAL_CANONICAL_REVIEW))
        assertEquals("영수증", DesktopUiLabels.sourceAttachmentType(SourceAttachmentType.RECEIPT))
        assertEquals("검수 가능", DesktopUiLabels.ingestionReviewStatus(IngestionReviewStatus.READY))
        assertEquals("검수 완료", DesktopUiLabels.artifactStatus(verified = true, evidenceReady = false, issues = emptyList()))
        assertEquals("대기", DesktopUiLabels.projectionStatus(ProjectionStatus.PENDING))
        assertEquals("차단됨", DesktopUiLabels.projectionStatus(ProjectionStatus.BLOCKED))
        assertEquals("전송 완료", DesktopUiLabels.projectionStatus(ProjectionStatus.UPLOADED))
        assertEquals("상품-영양 연결", DesktopUiLabels.projection(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK))
    }

    @Test
    fun bundleAndArchiveStatusesUseKoreanDisplayLabels() {
        assertEquals("유효", DesktopUiLabels.bundleValidationStatus(DesktopBundleValidationStatus.VALID))
        assertEquals("무효", DesktopUiLabels.bundleValidationStatus(DesktopBundleValidationStatus.INVALID))
        assertEquals("시작 전", DesktopUiLabels.evidenceArchiveStatus(DesktopEvidenceArchiveStatus.NOT_STARTED))
        assertEquals("보관 중", DesktopUiLabels.evidenceArchiveStatus(DesktopEvidenceArchiveStatus.ARCHIVING))
        assertEquals("보관 완료", DesktopUiLabels.evidenceArchiveStatus(DesktopEvidenceArchiveStatus.ARCHIVED))
        assertEquals("실패", DesktopUiLabels.evidenceArchiveStatus(DesktopEvidenceArchiveStatus.FAILED))
    }
}
