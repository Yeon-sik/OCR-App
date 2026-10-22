package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DesktopReviewerUxTest {
    private lateinit var compose: ComposeUiTest

    private fun withUiTest(width: Int = 1200, height: Int = 850, block: () -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            compose = this
            block()
        }

    private fun controller(conflict: Boolean = false): DesktopIngestionController {
        val store = DesktopSessionStore(Files.createTempDirectory("reviewer-ui"))
        val c = DesktopIngestionController(store, DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("none.env"))))
        val base = YeonsikOcrV2Json.decode(File("../examples/yeonsik-ocr.v2.packaged-product.example.json").readText(), "ux-sample")
        val label = base.nutrition.single() as IngestionNutrition.ProductLabel
        val linked = label.copy(productClientKey = "product-1", draft = if (conflict) label.draft.copy(productName = "Other cereal") else label.draft)
        runBlocking { c.importJson(YeonsikOcrEnvelopeCodec.encodePersisted(base.copy(schemaVersion = YEONSIK_OCR_V3_SCHEMA, nutrition = listOf(linked)))) }
        return c
    }

    private fun show(c: DesktopIngestionController, width: Int = 1200, height: Int = 850) {
        compose.setContent { CollectorTheme { Box(Modifier.requiredSize(width.dp, height.dp)) {
            YeonsikCollectorApp(c, remember { DesktopBatchCoordinator(c) })
        } } }
        compose.waitUntil(timeoutMillis = 5_000) { !c.state.value.busy }
        compose.waitForIdle()
    }

    @Test fun realEditorBlocksConfirmationUntilDraftIsAppliedThenBlocksDeliveryOnFurtherTyping() = withUiTest {
        val c = controller()
        show(c)
        val productInput = hasSetTextAction() and hasText("Test cereal")
        compose.onNode(productInput).assertIsDisplayed()
        assertFalse(reviewSummaryFacts(c.state.value.envelope!!, emptyList()).any { it.first in setOf("날짜", "금액") })
        capture("after-1200-initial")
        compose.onNode(productInput).performTextReplacement("UX AUDIT DRAFT")
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsNotEnabled()
        compose.onNodeWithText("미적용 입력 1개 · 적용하거나 입력을 취소하세요.").assertIsDisplayed()
        capture("after-draft-blocked")
        compose.onNodeWithContentDescription("상품명 값 적용").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !c.state.value.busy && c.state.value.reviewDrafts.isEmpty() }
        assertEquals("UX AUDIT DRAFT", c.state.value.envelope!!.productCandidates.single().productName)
        compose.onNodeWithText("원본 없이 직접 확인").performClick()
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { c.state.value.session!!.verifiedCanonicalFingerprint != null }
        compose.onNodeWithText("선택한 곳에 보내기").assertIsEnabled()
        compose.onNode(hasSetTextAction() and hasText("UX AUDIT DRAFT")).performTextReplacement("PENDING AGAIN")
        compose.onNodeWithText("선택한 곳에 보내기").assertIsNotEnabled()
        compose.onNodeWithContentDescription("상품명 입력 취소").performClick()
        compose.onNodeWithText("선택한 곳에 보내기").assertIsEnabled()
        compose.onNode(hasSetTextAction() and hasText("UX AUDIT DRAFT")).assertExists()
    }

    @Test fun compactConflictIsVisibleFirstAndNextItemMovesFocusToItsResolution() = withUiTest(820, 720) {
        val c = controller(conflict = true)
        show(c, 820, 720)
        compose.onNodeWithText("상품명 확인 필요 · 서로 다른 값이 있습니다").assertIsDisplayed()
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsNotEnabled()
        capture("after-820-conflict")
        compose.onNodeWithText("다음 확인 항목").performClick()
        compose.onNodeWithTag("review-target:conflict:product-1:NAME").assertIsFocused()
        compose.onNodeWithText("상품 정보에서 읽음 · Test cereal").performClick()
        compose.onNodeWithText("상품명 확정").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !c.state.value.busy && (c.state.value.envelope!!.nutrition.single() as IngestionNutrition.ProductLabel).draft.productName == "Test cereal" }
        compose.onNodeWithText("상품명 확인 필요 · 서로 다른 값이 있습니다").assertDoesNotExist()
        capture("after-820-conflict-resolved")
    }

    @Test fun minimumWindowRetainsFieldDraftAcrossSourceTabAndExposesBothActions() = withUiTest(800, 560) {
        val c = controller()
        show(c, 800, 560)
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsDisplayed()
        compose.onNodeWithText("선택한 곳에 보내기").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Test cereal")).performTextReplacement("KEPT DRAFT")
        compose.onNodeWithText("원본", useUnmergedTree = true).performClick()
        compose.onNodeWithText("검수", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("KEPT DRAFT")).assertExists()
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsNotEnabled()
        capture("after-800-draft")
    }

    @Test fun invalidNumberIsRetainedNavigableAndCanBeCancelled() = withUiTest {
        val c = controller()
        show(c)
        val field = canonicalReviewFieldGroups(c.state.value.envelope!!).visibleFields
            .single { it.path.endsWith(".basis_amount") }
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("review-target:${field.path}")))
            .performTextReplacement("invalid-number")
        compose.onNodeWithContentDescription("${field.label} 값 적용").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !c.state.value.busy && field.path in c.state.value.reviewFieldErrors }
        assertEquals("invalid-number", c.state.value.reviewDrafts[field.path])
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsNotEnabled()
        compose.onNodeWithText("다음 확인 항목").performClick()
        compose.onNodeWithTag("review-target:${field.path}").assertIsFocused()
        compose.onNodeWithContentDescription("${field.label} 입력 취소").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !c.state.value.busy && c.state.value.reviewDrafts.isEmpty() }
        assertTrue(c.state.value.reviewFieldErrors.isEmpty())
        compose.onNodeWithText("검수 완료 · 확정하기").assertIsEnabled()
    }

    private fun capture(name: String) {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val output = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) output.setRGB(x, y, pixels[x,y].toArgb())
        val file = File("build/reports/reviewer-ux/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
