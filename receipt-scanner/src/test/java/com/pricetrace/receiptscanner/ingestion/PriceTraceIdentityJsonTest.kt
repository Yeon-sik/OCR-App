package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceTraceIdentityJsonTest {
    @Test
    fun decodeAndEncodePreserveAuthorityIdentityAtTopAndLineLevel() {
        val identity = PriceTraceIdentityJson.decode(
            Json.parseToJsonElement(
                """
                {
                  "receiptId":"receipt-1",
                  "storeId":"store-1",
                  "restaurantId":"restaurant-1",
                  "restaurantLocationId":"location-1",
                  "lines":[
                    {
                      "sourceLineId":"line-1",
                      "receiptItemId":"item-1",
                      "productId":"product-1",
                      "storeProductId":"store-product-1",
                      "catalogProductId":"catalog-1",
                      "restaurantMenuId":"menu-1"
                    }
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertEquals("receipt-1", identity.receiptId)
        assertEquals("store-1", identity.storeId)
        assertEquals("restaurant-1", identity.restaurantId)
        assertEquals("location-1", identity.restaurantLocationId)
        assertEquals("product-1", identity.lines.single().productId)
        assertEquals("store-product-1", identity.lines.single().storeProductId)
        assertEquals("catalog-1", identity.lines.single().catalogProductId)
        assertEquals("menu-1", identity.lines.single().restaurantMenuId)

        val encoded = PriceTraceIdentityJson.encode(identity)
        assertEquals("receipt-1", encoded["receiptId"]?.jsonPrimitive?.content)
        assertEquals("store-1", encoded["storeId"]?.jsonPrimitive?.content)
        assertEquals(
            "menu-1",
            encoded["lines"]?.jsonArray?.single()?.jsonObject?.get("restaurantMenuId")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun unavailableAuthorityIdentityRemainsNull() {
        val identity = PriceTraceIdentityJson.decode(
            Json.parseToJsonElement("""{"receiptId":"receipt-2","storeId":"store-2","lines":[{}]}""").jsonObject,
        )

        assertNull(identity.restaurantId)
        assertNull(identity.lines.single().productId)
        assertEquals(JsonNull, PriceTraceIdentityJson.encode(identity)["restaurantId"])
        assertEquals(
            JsonNull,
            PriceTraceIdentityJson.encode(identity)["lines"]?.jsonArray?.single()?.jsonObject?.get("productId"),
        )
    }
}
