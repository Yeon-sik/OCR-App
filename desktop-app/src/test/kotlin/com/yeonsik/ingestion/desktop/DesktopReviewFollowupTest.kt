package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.*
import com.pricetrace.receiptscanner.review.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalTestApi::class)
class DesktopReviewFollowupTest {
    private fun example() = File("../examples/yeonsik-ocr.v2.restaurant.example.json").readText()
    private fun controller(): DesktopIngestionController {
        val dir = Files.createTempDirectory("followup-ui")
        return DesktopIngestionController(DesktopSessionStore(dir), DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), dir.resolve("none.env"))))
            .also { runBlocking { it.importJson(example()) } }
    }

    @Test fun completedBadgesOverridePlansAndPartialDeliveryRemainsExplicit() {
        val badge = ReviewDestinationBadge(ReviewDestination.PRICE_TRACE, ReviewDestinationStatus.PLANNED,
            listOf(ProjectionStatus.UPLOADED, ProjectionStatus.UPLOADED))
        assertEquals("전송 완료", reviewDestinationLabel(badge))
        assertEquals("대상 아님", reviewDestinationLabel(badge.copy(status = ReviewDestinationStatus.NOT_APPLICABLE)))
        assertEquals("일부 완료 1/2", reviewDestinationLabel(badge.copy(projectionStatuses = listOf(ProjectionStatus.UPLOADED, ProjectionStatus.FAILED))))
        assertEquals("전송 실패 · 재시도 필요", reviewDestinationLabel(badge.copy(projectionStatuses = listOf(ProjectionStatus.FAILED))))
        val c = controller()
        val state = c.state.value
        val session = state.session!!
        val complete = state.copy(session = session.copy(projections = session.projections.map { it.copy(status = ProjectionStatus.UPLOADED) }))
        assertTrue(pendingDeliveryProjections(complete, session.projections.map { it.projection }.toSet()).isEmpty())
    }

    @Test fun fieldPresentationDoesNotExposeKeysOrChangeCanonicalValues() {
        val envelope = YeonsikOcrV2Json.decode(example(), "display-only")
        val fields = canonicalReviewFieldGroups(envelope).visibleFields
        assertFalse(fields.any { it.label.contains("line-1") || it.label.contains("food-1") })
        assertTrue(fields.filter { it.path.endsWith(".menu_name") }.all { reviewFieldCategory(it) == ReviewFieldCategory.PRODUCT })
        val fulfillment = fields.single { it.path == "receipt.document.fulfillment.type" }
        assertEquals("매장 식사", reviewEnumLabel(fulfillment.value!!))
        assertEquals("dine_in", CanonicalFieldRegistry.value(envelope, fulfillment.path))
        val lines = fields.filter { reviewLineGroup(it.path) != null }
        assertEquals(1, lines.groupBy { reviewLineGroup(it.path) }.size)
        assertTrue(lines.filter(::reviewLineDetail).all { !it.path.endsWith(".description") && !it.path.endsWith(".quantity") })
    }

    @Test fun sourceResolverUsesExplicitLinksAndDoesNotGuessFromNames() {
        val envelope = YeonsikOcrV2Json.decode(example(), "sources")
        assertEquals(listOf("food-1"), reviewFieldSources(envelope, "nutrition[food-1].nutrients.calories_kcal"))
        assertEquals(listOf("food-3"), reviewFieldSources(envelope, "nutrition[food-3].nutrients.calories_kcal"))
        assertTrue(reviewFieldSources(envelope, "receipt.document.fulfillment.type").isEmpty())
        assertTrue(reviewFieldSources(envelope, "nutrition[food-1].menu_name").isEmpty())
    }

    @Test fun enumOpeningAndKeyboardNavigationRefreshSourceWithoutEditing() = runDesktopComposeUiTest(width = 1737, height = 1328) {
        val c = controller()
        val before = c.state.value.canonicalJson
        setContent { CollectorTheme { Box(Modifier.requiredSize(1737.dp, 1328.dp)) {
            YeonsikCollectorApp(c, remember { DesktopBatchCoordinator(c) })
        } } }
        waitForIdle()
        val protein = "nutrition[food-1].nutrients.protein_grams"
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("review-target:$protein"))).performScrollTo().performClick()
        onNodeWithText("음식 1 · 단백질 · 편집값:", substring = true).assertExists()
        val fulfillment = "receipt.document.fulfillment.type"
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag("review-target:$fulfillment"))).performScrollTo().performClick()
        onNodeWithText("수령 방식 · 편집값: 매장 식사", substring = true).assertExists()
        onNodeWithText("배달").assertIsDisplayed()
        onNodeWithText("dine_in").assertDoesNotExist()
        onNodeWithText("배달").performClick()
        assertEquals("delivery", c.state.value.reviewDrafts[fulfillment])
        assertEquals(before, c.state.value.canonicalJson)
        onNodeWithText("다음 확인 항목").performClick()
        onNodeWithText("수령 방식 · 편집값: 배달", substring = true).assertExists()
        c.discardAllReviewDrafts()
        assertEquals(before, c.state.value.canonicalJson)
    }

    @Test fun purchaseFieldsStayTogetherAndMetadataCanBeExpanded() = runDesktopComposeUiTest(width = 1737, height = 1328) {
        val c = controller()
        setContent { CollectorTheme { Box(Modifier.requiredSize(1737.dp, 1328.dp)) {
            YeonsikCollectorApp(c, remember { DesktopBatchCoordinator(c) })
        } } }
        waitForIdle()
        val group = "review-line:receipt.line_items[line-1]"
        onNodeWithText("품목 1 · Noodles").performScrollTo().assertIsDisplayed()
        for (suffix in listOf("description", "quantity", "net_amount_minor")) {
            onNode(hasTestTag("review-target:receipt.line_items[line-1].$suffix") and hasAnyAncestor(hasTestTag(group))).assertExists()
        }
        onNodeWithTag("review-target:receipt.line_items[line-1].type").assertDoesNotExist()
        onNodeWithText("품목 1 상세 · 유형·역할").performScrollTo().performClick()
        onNodeWithTag("review-target:receipt.line_items[line-1].type").assertExists()
        onNodeWithText("품목 1 상세 접기").performClick()
        onNodeWithTag("review-target:receipt.line_items[line-1].type").assertDoesNotExist()
    }
}
