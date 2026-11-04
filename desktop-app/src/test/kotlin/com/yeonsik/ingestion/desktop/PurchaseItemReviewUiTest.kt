package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class PurchaseItemReviewUiTest {
    private val name = "닥터스베스트 고흡수 킬레이트 마그네슘 타블렛"
    private val productPath = "product_candidates[product-client-1].product_name"
    private val linePath = "purchase_records[purchase-1].line_items[0]"
    private val descriptionPath = "$linePath.description"

    private fun controller(): DesktopIngestionController {
        val store = DesktopSessionStore(Files.createTempDirectory("purchase-item-ui"))
        val controller = DesktopIngestionController(store,
            DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("none.env"))))
        runBlocking { controller.importJson(File("../examples/yeonsik-ocr.v4.purchase.example.json").readText().replace("생수", name)) }
        return controller
    }

    private fun ComposeUiTest.show(controller: DesktopIngestionController, width: Int, height: Int) {
        setContent { CollectorTheme { Box(Modifier.requiredSize(width.dp, height.dp)) {
            YeonsikCollectorApp(controller, remember { DesktopBatchCoordinator(controller) })
        } } }
        waitUntil(timeoutMillis = 5_000) { !controller.state.value.busy }
        waitForIdle()
    }

    @Test fun equalNamesAppearOnceAndRevealedSourceTextStaysEditableWithoutLosingFocus() =
        runDesktopComposeUiTest(width = 1426, height = 1328) {
            val controller = controller()
            show(controller, 1426, 1328)
            onNodeWithText("상품 정보", substring = false).assertDoesNotExist()
            onAllNodes(hasSetTextAction() and hasText(name)).assertCountEquals(1)
            onNodeWithTag("review-target:$descriptionPath").assertDoesNotExist()
            onNode(hasTestTag("review-target:$productPath") and hasAnyAncestor(hasTestTag("review-line:$linePath"))).assertExists()
            capture("purchase-item-wide")

            onNodeWithText("구매 내역 표기명 보기").performScrollTo().performClick()
            val sourceInput = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("review-target:$descriptionPath")))
            sourceInput.performScrollTo().performClick().performTextReplacement("$name 원문")
            sourceInput.assertIsFocused()
            assertEquals("$name 원문", controller.state.value.reviewDrafts[descriptionPath])
            assertEquals(name, controller.state.value.envelope!!.productCandidates.single().productName)
            onNodeWithText("변경사항 모두 적용").performClick()
            waitUntil(timeoutMillis = 5_000) { !controller.state.value.busy && controller.state.value.reviewDrafts.isEmpty() }
            assertEquals("$name 원문", controller.state.value.envelope!!.purchaseRecords.single().lineItems.single().description)
            assertEquals(name, controller.state.value.envelope!!.productCandidates.single().productName)
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("review-target:$descriptionPath")))
                .assertTextContains("구매 내역 표기명", substring = true)
        }

    @Test fun productEditsKeepPurchaseTextAndOptionsStayInsideTheirItemOnNarrowWindows() =
        runDesktopComposeUiTest(width = 800, height = 720) {
            val controller = controller()
            show(controller, 800, 720)
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("review-target:$productPath")))
                .performScrollTo().performTextReplacement("닌텐도 스위치 커버")
            onNodeWithText("변경사항 모두 적용").performClick()
            waitUntil(timeoutMillis = 5_000) { !controller.state.value.busy && controller.state.value.reviewDrafts.isEmpty() }
            assertEquals(name, controller.state.value.envelope!!.purchaseRecords.single().lineItems.single().description)
            onNodeWithTag("review-target:$descriptionPath").performScrollTo().assertIsDisplayed()
            capture("purchase-item-narrow-different-name")

            onNodeWithText("구매 옵션 추가").performScrollTo().performClick()
            val optionPath = "$linePath.option_items.new_option"
            onNode(hasTestTag("review-target:$optionPath") and hasAnyAncestor(hasTestTag("review-line:$linePath")))
                .performScrollTo().assertIsDisplayed()
        }

    private fun ComposeUiTest.capture(name: String) {
        val pixels = onRoot().captureToImage().toPixelMap()
        val output = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val file = File("build/reports/purchase-item-review/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
