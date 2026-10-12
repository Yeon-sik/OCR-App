package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DesktopReviewConfirmationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun receiptAndNutritionShowThreeAppChoicesAndToggleAllInternalStagesTogether() {
        val envelope = YeonsikOcrEnvelopeCodec.decode(File("../examples/yeonsik-ocr.v2.packaged-product.example.json").readText(), "three-apps")
            .copy(receipt = com.pricetrace.receiptscanner.export.ReceiptV2Json.decode(File("../examples/receipt.v2.example.json").readText()))
        val delivery = CanonicalProjectionPlanner.deliveryProjections(envelope)
        var selected by mutableStateOf(delivery)
        val session = sampleState().session!!.copy(projections = delivery.map { ProjectionState(it, ProjectionStatus.PENDING, updatedAt = "now") })
        compose.setContent { CollectorTheme { Surface(Modifier.requiredSize(600.dp, 540.dp)) {
            DestinationSection(sampleState().copy(envelope = envelope, session = session), emptyList(), selected, false) { projection ->
                selected = selected.toMutableSet().apply { if (!add(projection)) remove(projection) }
            }
        } } }
        compose.onAllNodes(isToggleable()).assertCountEquals(3)
        compose.onNodeWithText("PriceTrace · 구매 상품·영수증·가격").assertIsDisplayed()
        compose.onNodeWithText("Fitness · 영양 정보·구매 상품 연결").assertIsDisplayed()
        compose.onNodeWithText("전송 완료 0/3곳").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.runOnIdle {
            assertEquals(emptySet<IngestionProjection>(), selected.filter { it.destination() == IngestionDestination.PRICE_TRACE }.toSet())
            assertEquals(delivery.filter { it.destination() != IngestionDestination.PRICE_TRACE }.toSet(), selected)
        }
        capture("review-three-app-destinations")
    }

    @Test fun compactReviewConfirmsBeforeOpeningExplicitTransmissionConfirmation() {
        var state by mutableStateOf(sampleState())
        var confirmationOpen by mutableStateOf(false)
        var transmissions = 0
        val selected = setOf(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE)
        compose.setContent {
            CollectorTheme {
                Surface(Modifier.requiredSize(380.dp, 480.dp)) {
                    ConfirmAndSubmitSection(
                        state = state, busy = false, canVerify = true,
                        canSubmit = state.session!!.verifiedCanonicalFingerprint != null,
                        confirmationBlocker = null, verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
                        selectedProjections = selected, submitConfirmationOpen = confirmationOpen,
                        onVerificationBasisSelected = {}, onVerify = {
                            state = state.copy(
                                session = state.session!!.copy(verifiedCanonicalFingerprint = "fingerprint"),
                                bundleMetadata = state.bundleMetadata!!.copy(verificationEventRecorded = true),
                            )
                        }, onOpenSubmitConfirmation = { confirmationOpen = true },
                        onDismissSubmitConfirmation = { confirmationOpen = false },
                        onConfirmSubmit = { transmissions++ }, onRetryArchive = {}, onRetryRevision = {},
                    )
                }
            }
        }
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("선택한 곳에 보내기").assertIsNotEnabled()
        capture("review-confirmation-compact")
        compose.onNodeWithText("검수 완료 · 확정하기").performClick()
        compose.onNodeWithText("검수 확정 완료").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("선택한 곳에 보내기").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, transmissions) }
        compose.onNode(hasText("전송 확인") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, transmissions) }
    }

    @Test fun blockedReviewExplainsTheReasonBesideTheActionAndOffersArchiveRecovery() {
        val state = sampleState().let { it.copy(bundleMetadata = it.bundleMetadata!!.copy(archiveStatus = DesktopEvidenceArchiveStatus.FAILED)) }
        val blocker = requireNotNull(reviewConfirmationBlocker(state, busy = false, revisionReady = true))
        var retries = 0
        compose.setContent {
            CollectorTheme {
                Surface(Modifier.requiredSize(640.dp, 480.dp)) {
                    ConfirmAndSubmitSection(
                        state = state, busy = false, canVerify = false, canSubmit = false,
                        confirmationBlocker = blocker, verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
                        selectedProjections = emptySet(), submitConfirmationOpen = false,
                        onVerificationBasisSelected = {}, onVerify = {}, onOpenSubmitConfirmation = {},
                        onDismissSubmitConfirmation = {}, onConfirmSubmit = {},
                        onRetryArchive = { retries++ }, onRetryRevision = {},
                    )
                }
            }
        }
        compose.onNodeWithText(blocker).assertIsDisplayed()
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsNotEnabled()
        capture("review-confirmation-blocked")
        compose.onNodeWithText("원본 보관 다시 시도").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun sampleState() = DesktopUiState(
        session = IngestionSession("review", "document", "storage", "fingerprint", IngestionReviewStatus.NEEDS_REVIEW, "now", "now", emptyList()),
        bundleMetadata = DesktopBundleMetadata("bundle.yeonsik", "a".repeat(64), "{}", DesktopBundleValidationStatus.VALID, DesktopEvidenceArchiveStatus.ARCHIVED),
    )

    private fun capture(name: String) {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val output = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val file = File("build/reports/review-ux/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
