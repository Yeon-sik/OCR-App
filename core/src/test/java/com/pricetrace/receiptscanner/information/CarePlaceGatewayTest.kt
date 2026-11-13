package com.pricetrace.receiptscanner.information

import com.pricetrace.receiptocr.fitness.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CarePlaceGatewayTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val input = CarePlaceInput("hospital", "검증 의원", "검증로 1")
    private class Connections : InformationConnectionStore {
        override fun read(owner: InformationOwner) = InformationConnection("https://example.supabase.co", "sb_publishable_fixture_key", "11111111-1111-4111-8111-111111111111", "fixture@example.test", "access", "refresh")
        override fun saveConnection(owner: InformationOwner, url: String, publishableKey: String) = Unit
        override fun saveSession(owner: InformationOwner, userId: String, email: String, accessToken: String, refreshToken: String) = Unit
        override fun clearSession(owner: InformationOwner) = Unit
    }
    @Test fun `registration sends public institution fields only and retries same request id`() = runTest {
        val requests = mutableListOf<NutritionHttpRequest>()
        val bridge = InformationDeliveryGateway(Connections(), object : NutritionHttpTransport {
            override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
                requests += request
                if (request.url.endsWith("/auth/v1/user")) return NutritionHttpResponse(200, "{\"id\":\"$id\"}")
                val body = Json.parseToJsonElement(request.body!!).jsonObject
                assertEquals(setOf("p_request_id", "p_place", "p_public_confirmed"), body.keys)
                assertEquals(id, body["p_request_id"]!!.jsonPrimitive.content)
                assertEquals(input.json(), body["p_place"])
                return NutritionHttpResponse(200, buildJsonObject { put("place", CarePlace(id, input).json()); put("duplicate", requests.count { it.method == "POST" } > 1) }.toString())
            }
        })
        val gateway = CarePlaceGateway(bridge)
        assertTrue(runCatching { gateway.register(id, input, false) }.isFailure)
        assertTrue(requests.isEmpty())
        assertFalse(gateway.register(id, input, true).duplicate)
        assertTrue(gateway.register(id, input, true).duplicate)
    }
    @Test fun `public lookup omits authorization and rejects unexpected patient fields`() = runTest {
        val bridge = InformationDeliveryGateway(Connections(), object : NutritionHttpTransport {
            override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
                assertFalse(request.headers.containsKey("Authorization"))
                return NutritionHttpResponse(200, JsonArray(listOf(CarePlace(id, input).json())).toString())
            }
        })
        assertEquals(id, CarePlaceGateway(bridge).search("검증", "hospital").single().id)
        assertTrue(runCatching { CarePlace.decode(JsonObject(CarePlace(id, input).json() + ("patient" to JsonPrimitive("not allowed")))) }.isFailure)
    }
}
