package com.pricetrace.receiptscanner.publisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestaurantReceiptModelsTest {
    @Test
    fun payloadKeepsMenuOptionRowsAndUsesStrictRpcKeys() {
        val payload = RestaurantReceiptSubmitPayload(
            idempotencyKey = "restaurant-key",
            documentId = "ocr-document",
            restaurantName = "테스트 식당",
            branchName = "강남점",
            observedOn = "2026-08-14",
            totalPriceKrw = 19_000,
            items = listOf(
                RestaurantReceiptSubmitItem("menu-1", "치킨", 1, 15_000, 15_000, "product"),
                RestaurantReceiptSubmitItem("option-1", "치즈 추가", 1, 4_000, 4_000, "service"),
            ),
        )

        val json = payload.toRpcJson()
        assertTrue(json.contains("\"description\":\"치즈 추가\""))
        assertEquals(2, payload.items.size)
        assertEquals(
            RestaurantReceiptSubmitResponse(
                receiptId = "11111111-1111-4111-8111-111111111111",
                replayed = false,
                itemCount = 2,
            ),
            RestaurantReceiptJson.decodeSubmitResponse(
                """[{"receipt_id":"11111111-1111-4111-8111-111111111111","replayed":false,"item_count":2}]""",
            ),
        )
    }
}
