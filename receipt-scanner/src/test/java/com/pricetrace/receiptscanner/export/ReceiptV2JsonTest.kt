package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.SyntheticFixtures
import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.domain.ReceiptBenefitKind
import com.pricetrace.receiptscanner.domain.ReceiptFoodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptV2JsonTest {
    @Test
    fun `receipt v2 round trips with exact integer amounts`() {
        val receipt = SyntheticFixtures.verifiedCandidate()
        val json = ReceiptV2Json.encodeCanonical(receipt)
        val decoded = ReceiptV2Json.decode(json)

        assertEquals(receipt, decoded)
        assertTrue(json.contains("\"net_amount_minor\":1000"))
        assertFalse(json.contains("1000.0"))
    }

    @Test
    fun `canonical revision and idempotency key are stable`() {
        val receipt = SyntheticFixtures.verifiedCandidate()
        assertEquals(ReceiptV2Json.revisionHash(receipt), ReceiptV2Json.revisionHash(receipt.copy()))
        assertEquals(ReceiptV2Json.idempotencyKey(receipt), ReceiptV2Json.idempotencyKey(receipt.copy()))
    }

    @Test
    fun `legacy food service decodes with a null benefit and new encoding is explicit`() {
        val receipt = restaurantReceipt(
            SyntheticFixtures.verifiedCandidate().lineItems.single().copy(
                foodService = ReceiptFoodService(FoodServiceRole.MAIN),
            ),
        )
        val encoded = ReceiptV2Json.encodeCanonical(receipt)
        assertTrue(encoded.contains("\"benefit_kind\":null"))

        val legacy = encoded.replace("\"benefit_kind\":null,", "")
        val decodedLegacy = ReceiptV2Json.decode(legacy)
        assertEquals(null, decodedLegacy.lineItems.single().foodService?.benefitKind)
        assertEquals(receipt, ReceiptV2Json.decode(encoded))
    }

    @Test
    fun `benefit kind round trips independently from role and amount`() {
        val main = SyntheticFixtures.verifiedCandidate().lineItems.single().copy(
            id = "main",
            foodService = ReceiptFoodService(FoodServiceRole.MAIN),
        )
        val includedOption = main.copy(
            id = "included-option",
            description = "포함 옵션",
            netAmountMinor = 0,
            foodService = ReceiptFoodService(
                role = FoodServiceRole.OPTION,
                appliesToLineId = "main",
                benefitKind = ReceiptBenefitKind.INCLUDED,
            ),
        )
        val reviewOption = main.copy(
            id = "review-option",
            description = "리뷰 이벤트",
            netAmountMinor = 100,
            foodService = ReceiptFoodService(
                role = FoodServiceRole.OPTION,
                appliesToLineId = "main",
                benefitKind = ReceiptBenefitKind.REVIEW_EVENT,
            ),
        )
        val decoded = ReceiptV2Json.decode(
            ReceiptV2Json.encodeCanonical(
                restaurantReceipt(
                    main.copy(netAmountMinor = 10_000),
                    includedOption,
                    reviewOption,
                ),
            ),
        )

        assertEquals(ReceiptBenefitKind.INCLUDED, decoded.lineItems[1].foodService?.benefitKind)
        assertEquals(FoodServiceRole.OPTION, decoded.lineItems[1].foodService?.role)
        assertEquals(0L, decoded.lineItems[1].netAmountMinor)
        assertEquals(ReceiptBenefitKind.REVIEW_EVENT, decoded.lineItems[2].foodService?.benefitKind)
        assertEquals(100L, decoded.lineItems[2].netAmountMinor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown benefit kind is rejected strictly`() {
        val receipt = restaurantReceipt(
            SyntheticFixtures.verifiedCandidate().lineItems.single().copy(
                foodService = ReceiptFoodService(FoodServiceRole.MAIN, benefitKind = ReceiptBenefitKind.REVIEW_EVENT),
            ),
        )
        ReceiptV2Json.decode(
            ReceiptV2Json.encodeCanonical(receipt).replace(
                "\"benefit_kind\":\"review_event\"",
                "\"benefit_kind\":\"invented\"",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown contract fields are rejected`() {
        val valid = ReceiptV2Json.encodeCanonical(SyntheticFixtures.verifiedCandidate())
        ReceiptV2Json.decode(valid.replaceFirst("{", "{\"invented\":true,"))
    }


    @Test(expected = IllegalArgumentException::class)
    fun `food service links are rejected when merchant is not a food service business`() {
        val valid = ReceiptV2Json.encodeCanonical(SyntheticFixtures.verifiedCandidate())
        ReceiptV2Json.decode(valid.replaceFirst(
            "\"food_service\":null",
            "\"food_service\":{\"role\":\"option\",\"applies_to_line_id\":null}",
        ))
    }

    private fun restaurantReceipt(vararg lines: com.pricetrace.receiptscanner.domain.ReceiptV2LineItem) =
        SyntheticFixtures.verifiedCandidate().copy(
            merchant = SyntheticFixtures.verifiedCandidate().merchant.copy(
                businessKind = BusinessKind.FOOD_SERVICE,
            ),
            lineItems = lines.toList(),
        )
}
