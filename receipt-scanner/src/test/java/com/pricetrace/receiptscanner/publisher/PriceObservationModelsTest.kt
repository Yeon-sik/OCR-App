package com.pricetrace.receiptscanner.publisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PriceObservationModelsTest {
    private val storeId = "11111111-1111-1111-1111-111111111111"
    private val productId = "22222222-2222-2222-2222-222222222222"
    private val observationId = "33333333-3333-3333-3333-333333333333"

    @Test
    fun `submit payload maps only the five RPC parameters`() {
        val payload = PriceObservationSubmitPayload(
            idempotencyKey = "opaque-random-key",
            storeId = storeId,
            observedOn = "2026-08-13",
            catalogProductId = productId,
            unitPriceKrw = 1_250,
        )

        assertEquals(
            "{\"p_idempotency_key\":\"opaque-random-key\",\"p_store_id\":\"$storeId\",\"p_observed_on\":\"2026-08-13\",\"p_catalog_product_id\":\"$productId\",\"p_unit_price_krw\":1250}",
            payload.toRpcJson(),
        )
        assertEquals(-1, payload.toRpcJson().indexOf("receipt"))
        assertEquals(-1, payload.toRpcJson().indexOf("raw_text"))
    }

    @Test
    fun `submit response maps created deduplicated and replayed actions`() {
        PriceObservationAppliedAction.entries.forEach { action ->
            val response = PriceObservationJson.decodeSubmitResponse(
                "[{\"observation_id\":\"$observationId\",\"replayed\":${action == PriceObservationAppliedAction.REPLAYED},\"applied_action\":\"${action.wireValue}\"}]",
            )

            assertEquals(observationId, response.observationId)
            assertEquals(action == PriceObservationAppliedAction.REPLAYED, response.replayed)
            assertEquals(action, response.appliedAction)
        }
    }

    @Test
    fun `strict response mapping rejects an unknown key`() {
        assertThrows(IllegalArgumentException::class.java) {
            PriceObservationJson.decodeSubmitResponse(
                "[{\"observation_id\":\"$observationId\",\"replayed\":false,\"applied_action\":\"replayed\",\"extra\":true}]",
            )
        }
    }

    @Test
    fun `submit payload rejects an impossible observed date`() {
        assertThrows(IllegalArgumentException::class.java) {
            PriceObservationSubmitPayload(
                idempotencyKey = "opaque-random-key",
                storeId = storeId,
                observedOn = "2026-99-99",
                catalogProductId = productId,
                unitPriceKrw = 1_000,
            )
        }
    }

    @Test
    fun `product read preserves the exact catalog id without name matching`() {
        val response = PriceObservationJson.decodeProductRead(
            """
            {
              "schemaVersion":"product-read.v1",
              "namespace":"pricetrace",
              "revision":"sha256:test",
              "products":[{
                "standardProduct":{"id":"44444444-4444-4444-4444-444444444444","name":"Exact product","brand":null,"updatedAt":"2026-08-13T00:00:00Z"},
                "catalogProduct":{"id":"$productId","name":"Exact product 500g","specificationText":"500g","contentAmount":500,"contentUnit":"g","packageCount":1,"referenceUnit":"100g","listingReferenceUrl":null,"updatedAt":"2026-08-13T00:00:00Z"},
                "sellerProducts":[],
                "observations":[]
              }]
            }
            """.trimIndent(),
        )

        assertEquals(productId, response.products.single().catalogProductId)
        assertEquals("Exact product 500g", response.products.single().catalogProductName)
    }
}
