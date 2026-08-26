package com.pricetrace.receiptscanner.publisher

import org.junit.Assert.assertTrue
import org.junit.Test

class CashOsReceiptModelsTest {
    @Test
    fun payloadKeepsRestaurantBranchAndMenuIdentityFields() {
        val json = CashOsReceiptSubmitPayload(
            idempotencyKey = "receipt-key",
            ledgerEntryId = "ledger-1",
            documentId = "document-1",
            receiptRevision = "revision-1",
            receiptFingerprint = "a".repeat(64),
            merchantName = "텐진라면",
            branchName = "강남점",
            purchaseLocalDate = "2026-08-16",
            purchaseLocalTime = null,
            totalAmountKrw = 19_000,
            items = listOf(
                CashOsReceiptSubmitItem(
                    receiptItemId = "line-1",
                    descriptionSnapshot = "텐진라멘",
                    quantity = "1",
                    unit = "each",
                    unitPriceKrw = 19_000,
                    totalPriceKrw = 19_000,
                    lineType = "product",
                    restaurantMenuId = "33333333-3333-4333-8333-333333333333",
                    menuName = "텐진라멘",
                    catalogProductId = "44444444-4444-4444-8444-444444444444",
                ),
            ),
            restaurantId = "11111111-1111-4111-8111-111111111111",
            restaurantLocationId = "22222222-2222-4222-8222-222222222222",
        ).toRpcJson()

        assertTrue(json.contains("\"p_restaurant_id\":\"11111111-1111-4111-8111-111111111111\""))
        assertTrue(json.contains("\"p_restaurant_location_id\":\"22222222-2222-4222-8222-222222222222\""))
        assertTrue(json.contains("\"restaurant_menu_id\":\"33333333-3333-4333-8333-333333333333\""))
        assertTrue(json.contains("\"menu_name\":\"텐진라멘\""))
        assertTrue(json.contains("\"catalog_product_id\":\"44444444-4444-4444-8444-444444444444\""))
    }
}
