package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.PlaceCandidateSource
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitPayload
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResult
import com.pricetrace.receiptscanner.publisher.RestaurantPlaceJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class PriceObservationGatewayTest {
    @Test
    fun submitUsesTheExactRpcPathHeadersAndFiveFieldPayload() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"observation_id":"$OBSERVATION_ID","replayed":false,"applied_action":"created"}]""",
            ),
        )
        val gateway = PriceObservationGateway(store, transport)

        val result = gateway.submit(payload())

        assertEquals(
            PriceObservationSubmitResult.Success(
                com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResponse(
                    observationId = OBSERVATION_ID,
                    replayed = false,
                    appliedAction = com.pricetrace.receiptscanner.publisher.PriceObservationAppliedAction.CREATED,
                ),
            ),
            result,
        )
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/submit_price_observation_v1",
            request.url,
        )
        assertEquals("price-trace-publishable-key", request.headers["apikey"])
        assertEquals("Bearer access-token", request.headers["Authorization"])
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(
            setOf(
                "p_idempotency_key",
                "p_store_id",
                "p_observed_on",
                "p_catalog_product_id",
                "p_unit_price_krw",
            ),
            body.keys,
        )
        assertEquals("opaque-random-key", body["p_idempotency_key"]?.jsonPrimitive?.content)
        assertEquals(STORE_ID, body["p_store_id"]?.jsonPrimitive?.content)
        assertEquals("2026-08-13", body["p_observed_on"]?.jsonPrimitive?.content)
        assertEquals(CATALOG_PRODUCT_ID, body["p_catalog_product_id"]?.jsonPrimitive?.content)
        assertEquals(1590, body["p_unit_price_krw"]?.jsonPrimitive?.intOrNull)
        assertFalse(requireNotNull(request.body).contains("receipt.v2"))
        assertFalse(requireNotNull(request.body).contains("raw_text"))
    }

    @Test
    fun sourceAndProductReadsUseTheirExistingRpcContracts() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            PriceObservationHttpResponse(
                200,
                """[{"store_id":"$STORE_ID","source_namespace":"retail","source_store_code":"store-1","display_name":"Approved store","location_label":null}]""",
            ),
            PriceObservationHttpResponse(200, productReadJson()),
        )
        val gateway = PriceObservationGateway(store, transport)

        val sources = gateway.fetchSources()
        val products = gateway.searchProducts("coffee")

        assertEquals(1, (sources as PriceObservationReadOutcome.Success).value.size)
        assertEquals(STORE_ID, sources.value.single().storeId)
        assertEquals(CATALOG_PRODUCT_ID, (products as PriceObservationReadOutcome.Success).value.single().catalogProductId)
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/get_price_observation_sources_v1",
            transport.requests[0].url,
        )
        assertEquals("{}", transport.requests[0].body)
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/get_product_read_v1",
            transport.requests[1].url,
        )
        val productRequest = Json.parseToJsonElement(requireNotNull(transport.requests[1].body)).jsonObject
        assertEquals(setOf("p_catalog_product_id", "p_query", "p_limit"), productRequest.keys)
        assertTrue(productRequest["p_catalog_product_id"]?.toString() == "null")
        assertEquals("coffee", productRequest["p_query"]?.jsonPrimitive?.content)
        assertEquals(50, productRequest["p_limit"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun restaurantDirectorySearchUsesTheExactRpcPathHeadersAndFiveFieldPayload() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            PriceObservationHttpResponse(200, restaurantDirectoryJson()),
        )
        val gateway = PriceObservationGateway(store, transport)

        val result = gateway.searchRestaurantPlaces("  가상마트  ")

        val candidates = (result as PriceObservationReadOutcome.Success).value
        assertEquals(2, candidates.size)
        assertEquals(PlaceCandidateSource.VERIFIED_DIRECTORY, candidates[0].source)
        assertEquals("pricetrace:11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222221", candidates[0].id)
        assertEquals("가상마트", candidates[0].displayName)
        assertEquals("naver", candidates[0].sourceNamespace)
        assertEquals("강남점", candidates[0].branchName)
        assertEquals("GM-001", candidates[0].sourceLocationCode)
        assertEquals("11111111-1111-4111-8111-111111111111", candidates[0].restaurantId)
        assertEquals("22222222-2222-4222-8222-222222222221", candidates[0].restaurantLocationId)
        assertEquals("https://pricetrace.example.com/store/gm-001", candidates[0].detailUrl)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://pricetrace.example.com/rest/v1/rpc/get_restaurant_directory_v1",
            request.url,
        )
        assertEquals("price-trace-publishable-key", request.headers["apikey"])
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals(setOf("p_query", "p_limit"), body.keys)
        assertEquals("가상마트", body["p_query"]?.jsonPrimitive?.content)
        assertEquals(5, body["p_limit"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun restaurantDirectoryDecoderRejectsMissingNestedRestaurantOrLocationIdentity() {
        val missingRestaurantId = """
            [{"schemaVersion":"restaurant-directory.v2","restaurant":{"brand":"가상마트","legalName":"가상마트 주식회사"},"locations":[{"id":"22222222-2222-4222-8222-222222222221","sourceLabel":"naver","sourceRestaurantCode":"GM-001","locationLabel":"강남점","sourceUrl":"https://pricetrace.example.com/store/gm-001"}],"menuCount":12,"latestObservedAt":"2026-08-24T00:00:00Z","revision":"rev-1"}]
        """.trimIndent()
        val missingLocationId = """
            [{"schemaVersion":"restaurant-directory.v2","restaurant":{"id":"11111111-1111-4111-8111-111111111111","brand":"가상마트","legalName":"가상마트 주식회사"},"locations":[{"sourceLabel":"naver","sourceRestaurantCode":"GM-001","locationLabel":"강남점","sourceUrl":"https://pricetrace.example.com/store/gm-001"}],"menuCount":12,"latestObservedAt":"2026-08-24T00:00:00Z","revision":"rev-1"}]
        """.trimIndent()

        assertTrue(runCatching { RestaurantPlaceJson.decodeDirectoryResponse(missingRestaurantId) }.isFailure)
        assertTrue(runCatching { RestaurantPlaceJson.decodeDirectoryResponse(missingLocationId) }.isFailure)
    }

    @Test
    fun restaurantDirectoryDecoderFlattensOneRestaurantWithTwoLocations() {
        val candidates = RestaurantPlaceJson.decodeDirectoryResponse(restaurantDirectoryJson())

        assertEquals(2, candidates.size)
        assertEquals(PlaceCandidateSource.VERIFIED_DIRECTORY, candidates[1].source)
        assertEquals("pricetrace:11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222", candidates[1].id)
        assertEquals("가상마트", candidates[1].displayName)
        assertEquals("naver", candidates[1].sourceNamespace)
        assertEquals("홍대점", candidates[1].branchName)
        assertEquals("GM-002", candidates[1].sourceLocationCode)
        assertEquals("11111111-1111-4111-8111-111111111111", candidates[1].restaurantId)
        assertEquals("22222222-2222-4222-8222-222222222222", candidates[1].restaurantLocationId)
    }

    @Test
    fun authenticationFailureIsNotRetried() = runTest {
        val transport = QueueTransport(PriceObservationHttpResponse(401, """{"message":"JWT expired"}"""))
        val gateway = PriceObservationGateway(FakeStore(signedIn()), transport)

        val result = gateway.submit(payload())

        assertEquals(
            PriceObservationFailureKind.AUTHENTICATION,
            (result as PriceObservationSubmitResult.Failure).kind,
        )
        assertFalse(result.retryable)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun onlyTimeoutAndServerFailuresAreRetryable() = runTest {
        val timeoutTransport = QueueTransport(throwable = SocketTimeoutException("timeout"))
        val timeoutResult = PriceObservationGateway(FakeStore(signedIn()), timeoutTransport).submit(payload())
        assertEquals(PriceObservationFailureKind.NETWORK_TIMEOUT, (timeoutResult as PriceObservationSubmitResult.Failure).kind)
        assertTrue(timeoutResult.retryable)

        val serverTransport = QueueTransport(PriceObservationHttpResponse(503, "temporary"))
        val serverResult = PriceObservationGateway(FakeStore(signedIn()), serverTransport).submit(payload())
        assertEquals(PriceObservationFailureKind.SERVER, (serverResult as PriceObservationSubmitResult.Failure).kind)
        assertTrue(serverResult.retryable)

        val networkTransport = QueueTransport(throwable = java.io.IOException("offline"))
        val networkResult = PriceObservationGateway(FakeStore(signedIn()), networkTransport).submit(payload())
        assertEquals(PriceObservationFailureKind.NETWORK, (networkResult as PriceObservationSubmitResult.Failure).kind)
        assertFalse(networkResult.retryable)
    }

    @Test
    fun invalidSelectionAndIdempotencyMismatchAreNotRetryable() = runTest {
        val invalidSelection = PriceObservationGateway(
            FakeStore(signedIn()),
            QueueTransport(PriceObservationHttpResponse(400, """{"code":"22023","message":"approved public observation source required"}""")),
        ).submit(payload()) as PriceObservationSubmitResult.Failure
        assertEquals(PriceObservationFailureKind.INVALID_SELECTION, invalidSelection.kind)
        assertFalse(invalidSelection.retryable)

        val mismatch = PriceObservationGateway(
            FakeStore(signedIn()),
            QueueTransport(PriceObservationHttpResponse(409, """{"code":"23505","message":"idempotency key mismatch"}""")),
        ).submit(payload()) as PriceObservationSubmitResult.Failure
        assertEquals(PriceObservationFailureKind.IDEMPOTENCY_MISMATCH, mismatch.kind)
        assertFalse(mismatch.retryable)
    }

    @Test
    fun priceTraceConnectionRejectsPrivilegedKeysAndCleartextUrls() {
        assertTrue(
            PriceTraceSupabaseConfig.validateConnection(
                "http://pricetrace.example.com",
                "price-trace-publishable-key",
            ) != null,
        )
        assertTrue(
            PriceTraceSupabaseConfig.validateConnection(
                "https://pricetrace.example.com",
                "sb_secret_this-must-not-be-used",
            ) != null,
        )
        assertEquals(
            null,
            PriceTraceSupabaseConfig.validateConnection(
                "https://pricetrace.example.com",
                "price-trace-publishable-key",
            ),
        )
    }

    private fun payload() = PriceObservationSubmitPayload(
        idempotencyKey = "opaque-random-key",
        storeId = STORE_ID,
        observedOn = "2026-08-13",
        catalogProductId = CATALOG_PRODUCT_ID,
        unitPriceKrw = 1590,
    )

    private fun productReadJson() =
        """{"schemaVersion":"product-read.v1","namespace":"pricetrace","revision":"revision-1","products":[{"standardProduct":{"id":"$STANDARD_PRODUCT_ID","name":"Coffee","brand":null,"updatedAt":"2026-08-01T00:00:00Z"},"catalogProduct":{"id":"$CATALOG_PRODUCT_ID","name":"Coffee 500g","specificationText":"500g","contentAmount":500,"contentUnit":"g","packageCount":1,"referenceUnit":"g","listingReferenceUrl":null,"updatedAt":"2026-08-01T00:00:00Z"},"sellerProducts":[],"observations":[]}]}"""

    private fun restaurantDirectoryJson() =
        """
        [
          {
            "schemaVersion": "restaurant-directory.v2",
            "restaurant": {
              "id": "11111111-1111-4111-8111-111111111111",
              "brand": "가상마트",
              "legalName": "가상마트 주식회사"
            },
            "locations": [
              {
                "id": "22222222-2222-4222-8222-222222222221",
                "sourceLabel": "naver",
                "sourceRestaurantCode": "GM-001",
                "locationLabel": "강남점",
                "sourceUrl": "https://pricetrace.example.com/store/gm-001"
              },
              {
                "id": "22222222-2222-4222-8222-222222222222",
                "sourceLabel": "naver",
                "sourceRestaurantCode": "GM-002",
                "locationLabel": "홍대점",
                "sourceUrl": "https://pricetrace.example.com/store/gm-002"
              }
            ],
            "menuCount": 12,
            "latestObservedAt": "2026-08-24T00:00:00Z",
            "revision": "rev-1"
          }
        ]
        """.trimIndent()

    private fun signedIn() = PriceTraceSupabaseConfig(
        url = "https://pricetrace.example.com",
        publishableKey = "price-trace-publishable-key",
        userId = "user-1",
        email = "user@example.com",
        accessToken = "access-token",
        refreshToken = "refresh-token",
    )

    private class QueueTransport(
        vararg responses: PriceObservationHttpResponse,
        private val throwable: Throwable? = null,
    ) : PriceObservationHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<PriceObservationHttpRequest>()

        override suspend fun execute(request: PriceObservationHttpRequest): PriceObservationHttpResponse {
            requests += request
            throwable?.let { throw it }
            return responses.removeFirst()
        }
    }

    private class FakeStore(initial: PriceTraceSupabaseConfig) : PriceTraceSupabaseStore {
        private var config = initial

        override fun read(): PriceTraceSupabaseConfig = config

        override fun saveConnection(url: String, publishableKey: String): Result<PriceTraceSupabaseConfig> =
            runCatching {
                config = config.copy(url = url, publishableKey = publishableKey)
                config
            }

        override fun saveSession(
            userId: String,
            email: String,
            accessToken: String,
            refreshToken: String,
        ): Result<PriceTraceSupabaseConfig> = runCatching {
            config = config.copy(
                userId = userId,
                email = email,
                accessToken = accessToken,
                refreshToken = refreshToken,
            )
            config
        }

        override fun clearSession(): Boolean {
            config = config.copy(userId = "", email = "", accessToken = "", refreshToken = "")
            return true
        }
    }

    private companion object {
        const val STORE_ID = "11111111-1111-4111-8111-111111111111"
        const val CATALOG_PRODUCT_ID = "22222222-2222-4222-8222-222222222222"
        const val OBSERVATION_ID = "33333333-3333-4333-8333-333333333333"
        const val STANDARD_PRODUCT_ID = "44444444-4444-4444-8444-444444444444"
    }
}
