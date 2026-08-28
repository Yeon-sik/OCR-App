package com.pricetrace.receiptscanner.export

import com.pricetrace.receiptscanner.SyntheticFixtures
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
}
