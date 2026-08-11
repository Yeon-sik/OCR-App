package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.SyntheticFixtures
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.ReceiptValidator
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.domain.isUserEntered
import com.pricetrace.receiptscanner.domain.purchaseLocalTime
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptReviewControllerTest {
    @Test
    fun `invalid JSON does not mutate reviewed state`() {
        val original = SyntheticFixtures.verifiedCandidate()
        val controller = ReceiptReviewController(original, now = { "2026-08-03T10:00:00+09:00" })
        val before = controller.state.value.receipt

        assertTrue(controller.replaceFromJson("{broken").isFailure)
        assertSame(before, controller.state.value.receipt)
    }

    @Test
    fun `user edit records history and marks the line user verified`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )
        assertTrue(controller.updateLineDescription("line_fixture_001", "수정된 합성 상품"))
        assertEquals("수정된 합성 상품", controller.state.value.receipt.lineItems.single().description)
        assertEquals(ConfidenceLevel.USER_VERIFIED, controller.state.value.receipt.lineItems.single().confidence)
        assertEquals(1, controller.state.value.edits.size)
    }

    @Test
    fun `valid JSON replacement is atomic`() {
        val controller = ReceiptReviewController(SyntheticFixtures.verifiedCandidate())
        val replacement = SyntheticFixtures.verifiedCandidate().copy(
            merchant = SyntheticFixtures.verifiedCandidate().merchant.copy(name = "교체 상점"),
        )
        assertTrue(controller.replaceFromJson(ReceiptV2Json.encodeCanonical(replacement)).isSuccess)
        assertEquals("교체 상점", controller.state.value.receipt.merchant.name)
    }

    @Test
    fun `editing a finalized receipt returns the document to draft review`() {
        val verified = ReceiptValidator.markUserVerified(SyntheticFixtures.verifiedCandidate()).getOrThrow()
        val controller = ReceiptReviewController(verified, now = { "2026-08-03T10:00:00+09:00" })

        controller.updateMerchantName("재검수 상점")

        assertEquals(ReceiptStatus.DRAFT, controller.state.value.receipt.document.status)
        assertEquals(
            TranscriptionStatus.PARSED,
            controller.state.value.receipt.document.source.transcriptionStatus,
        )
    }

    @Test
    fun `reconciliation reason is restored from persisted edit history`() {
        val first = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )
        first.setReconciliationReason("원본에서 반올림 행을 확인함")

        val restored = ReceiptReviewController(
            initialReceipt = first.state.value.receipt,
            initialEdits = first.state.value.edits,
        )

        assertEquals("원본에서 반올림 행을 확인함", restored.state.value.reconciliationReason)
    }

    @Test
    fun `editing a simple product net amount keeps gross amount in sync`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        assertTrue(controller.updateLineNetAmount("line_fixture_001", "1,200"))

        val item = controller.state.value.receipt.lineItems.single()
        assertEquals(1_200L, item.netAmountMinor)
        assertEquals(1_200L, item.grossAmountMinor)
    }

    @Test
    fun `local purchase time is editable without inventing an offset`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        assertTrue(controller.updateIssuedLocalTime("18:07"))
        assertEquals("18:07:00", controller.state.value.receipt.document.source.purchaseLocalTime())
        assertEquals(null, controller.state.value.receipt.document.issuedAt)
        assertFalse(controller.updateIssuedLocalTime("25:99"))
        assertEquals("18:07:00", controller.state.value.receipt.document.source.purchaseLocalTime())
    }

    @Test
    fun `an added row is marked as a user transcription instead of OCR evidence`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        val lineId = controller.addLineItem(afterLineItemId = "line_fixture_001")

        val items = controller.state.value.receipt.lineItems
        assertEquals(listOf("line_fixture_001", lineId), items.map { it.id })
        val added = items.last()
        assertTrue(added.isUserEntered())
        assertEquals(listOf("user_entered:$lineId"), added.sourceLineReferences)
        assertNull(added.description)
        assertNull(added.netAmountMinor)
        assertEquals(ConfidenceLevel.LOW, added.confidence)
    }

    @Test
    fun `a removed row stays recoverable from the edit history`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        assertTrue(controller.removeLineItem("line_fixture_001"))
        assertFalse(controller.removeLineItem("line_fixture_001"))

        assertTrue(controller.state.value.receipt.lineItems.isEmpty())
        val edit = controller.state.value.edits.single()
        assertEquals("line_items[line_fixture_001]", edit.fieldPath)
        assertNull(edit.newValue)
        assertTrue(requireNotNull(edit.previousValue).contains("\"description\":\"합성 상품\""))
    }

    @Test
    fun `undo restores the previous draft and appends a compensating history row`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )
        assertFalse(controller.state.value.canUndo)

        controller.updateMerchantName("잘못 입력한 상점")
        assertTrue(controller.state.value.canUndo)
        assertTrue(controller.undo())

        val state = controller.state.value
        assertEquals("가상마트", state.receipt.merchant.name)
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)
        // The mistaken edit is never erased; reverting it is recorded as its own row.
        assertEquals(2, state.edits.size)
        assertEquals("가상마트", state.edits.last().newValue)
        assertTrue(requireNotNull(state.edits.last().provenanceJson).contains("\"undo\":true"))
        assertEquals(2, state.edits.map { it.id }.distinct().size)
    }

    @Test
    fun `redo reapplies the reverted value and is dropped by a new edit`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        controller.updateMerchantName("교체 상점")
        controller.undo()
        assertTrue(controller.redo())
        assertEquals("교체 상점", controller.state.value.receipt.merchant.name)

        controller.updateBranchName("부산점")
        assertFalse(controller.state.value.canRedo)
        assertFalse(controller.redo())
    }

    @Test
    fun `undo restores a removed row with its amounts intact`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )
        val original = controller.state.value.receipt.lineItems.single()

        controller.removeLineItem("line_fixture_001")
        assertTrue(controller.undo())

        assertEquals(original, controller.state.value.receipt.lineItems.single())
    }

    @Test
    fun `undo of a reconciliation reason restores the previous reason`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        controller.setReconciliationReason("첫 사유")
        controller.setReconciliationReason("둘째 사유")
        assertTrue(controller.undo())

        assertEquals("첫 사유", controller.state.value.reconciliationReason)
    }

    @Test
    fun `an adjustment row added from a suggestion keeps its type and amount`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        val lineId = controller.addLineItem(type = ReceiptLineType.DISCOUNT, netAmountMinor = -500)

        val added = controller.state.value.receipt.lineItems.single { it.id == lineId }
        assertEquals(ReceiptLineType.DISCOUNT, added.type)
        assertEquals(-500L, added.netAmountMinor)
        // Gross tracks net only for sold items, so an adjustment row leaves it unset.
        assertNull(added.grossAmountMinor)
    }

    @Test
    fun `edits made within one clock reading get distinct history ids`() {
        val controller = ReceiptReviewController(
            SyntheticFixtures.verifiedCandidate(),
            now = { "2026-08-03T10:00:00+09:00" },
        )

        controller.updateMerchantName("첫 입력")
        controller.updateMerchantName("둘째 입력")

        val edits = controller.state.value.edits
        assertEquals(2, edits.size)
        assertNotEquals(edits[0].id, edits[1].id)
    }
}
