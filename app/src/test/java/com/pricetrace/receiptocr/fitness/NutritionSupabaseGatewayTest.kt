package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class NutritionSupabaseGatewayTest {
    @Test
    fun signInStoresReturnedSessionWithoutPersistingPassword() = runTest {
        val store = FakeStore(connection())
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """{"access_token":"access-1","refresh_token":"refresh-1","user":{"id":"user-1","email":"fit@example.com"}}""",
            ),
        )
        val gateway = NutritionSupabaseGateway(store, transport)

        val result = gateway.signIn("fit@example.com", "one-time-password")

        assertEquals(NutritionAuthOutcome.Success("fit@example.com"), result)
        assertEquals("user-1", store.read().userId)
        assertEquals("access-1", store.read().accessToken)
        assertFalse(store.read().toString().contains("one-time-password"))
        assertFalse(transport.requests.single().headers.containsKey("Authorization"))
        assertEquals("publishable-key-with-safe-length", transport.requests.single().headers["apikey"])
    }

    @Test
    fun unverifiedDraftIsRejectedBeforeAnyTransportCall() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport()
        val gateway = NutritionSupabaseGateway(store, transport)

        val result = gateway.publish(
            verifiedDraft().copy(
                status = com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus.PARSED,
                confirmedAt = null,
            ),
        )

        assertEquals(NutritionPublishOutcome.Failure(NutritionGatewayFailure.CONTRACT), result)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun newVerifiedDraftIsInsertedAsOwnerPrivateWithoutOcrEvidence() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            NutritionHttpResponse(200, "[]"),
            NutritionHttpResponse(
                201,
                """[{"id":"ocr-nutrition:ocr-doc","revision":1}]""",
            ),
        )
        val gateway = NutritionSupabaseGateway(
            store = store,
            transport = transport,
            now = { "2026-08-11T11:00:00+09:00" },
        )

        val result = gateway.publish(verifiedDraft())

        assertEquals(NutritionPublishOutcome.Success("ocr-nutrition:ocr-doc", 1), result)
        assertEquals("GET", transport.requests[0].method)
        val insert = transport.requests[1]
        assertEquals("POST", insert.method)
        assertTrue(insert.url.endsWith("/rest/v1/nutrition_foods?on_conflict=id"))
        assertEquals("Bearer access-token", insert.headers["Authorization"])
        assertTrue(insert.body.orEmpty().contains("\"visibility\":\"private\""))
        assertTrue(insert.body.orEmpty().contains("\"owner_id\":\"user-1\""))
        assertTrue(insert.body.orEmpty().contains("\"source_reference\":\"ocr-document:ocr-doc\""))
        assertFalse(insert.body.orEmpty().contains("evidence"))
        assertFalse(insert.body.orEmpty().contains("raw_text"))
        assertFalse(insert.body.orEmpty().contains("catalog_product_id"))
    }

    @Test
    fun existingScannerRowUsesRevisionGuardInsteadOfBlindOverwrite() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"id":"ocr-nutrition:ocr-doc","owner_id":"user-1","revision":4,"source_type":"product_label_ocr","source_reference":"ocr-document:ocr-doc"}]""",
            ),
            NutritionHttpResponse(
                200,
                """[{"id":"ocr-nutrition:ocr-doc","revision":5}]""",
            ),
        )
        val gateway = NutritionSupabaseGateway(store, transport, now = { "2026-08-11T12:00:00+09:00" })

        val result = gateway.publish(verifiedDraft())

        assertEquals(NutritionPublishOutcome.Success("ocr-nutrition:ocr-doc", 5), result)
        val patch = transport.requests[1]
        assertEquals("PATCH", patch.method)
        assertTrue(patch.url.contains("revision=eq.4"))
        assertTrue(patch.body.orEmpty().contains("\"revision\": 5"))
        assertFalse(patch.body.orEmpty().contains("\"owner_id\""))
        assertFalse(patch.body.orEmpty().contains("\"id\""))
    }

    @Test
    fun differentSourceReferenceFailsClosedWithoutWriting() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"id":"ocr-nutrition:ocr-doc","owner_id":"user-1","revision":7,"source_type":"product_label_ocr","source_reference":"manual:other"}]""",
            ),
        )
        val gateway = NutritionSupabaseGateway(store, transport)

        val result = gateway.publish(verifiedDraft())

        assertEquals(NutritionPublishOutcome.Failure(NutritionGatewayFailure.CONFLICT), result)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun expiredAccessTokenRefreshesOnceBeforeRetryingPublish() = runTest {
        val store = FakeStore(signedIn())
        val transport = QueueTransport(
            NutritionHttpResponse(401, "{}"),
            NutritionHttpResponse(
                200,
                """{"access_token":"access-2","refresh_token":"refresh-2","user":{"id":"user-1","email":"fit@example.com"}}""",
            ),
            NutritionHttpResponse(200, "[]"),
            NutritionHttpResponse(201, """[{"id":"ocr-nutrition:ocr-doc","revision":1}]"""),
        )
        val gateway = NutritionSupabaseGateway(store, transport)

        val result = gateway.publish(verifiedDraft())

        assertTrue(result is NutritionPublishOutcome.Success)
        assertEquals("access-2", store.read().accessToken)
        assertTrue(transport.requests[1].url.contains("grant_type=refresh_token"))
        assertEquals("Bearer access-2", transport.requests[2].headers["Authorization"])
    }

    @Test
    fun connectionValidationRejectsCleartextAndAcceptsCustomHttps() {
        assertTrue(
            NutritionSupabaseConfig.validateConnection(
                "http://project.supabase.co",
                "publishable-key-with-safe-length",
            ) != null,
        )
        assertEquals(
            null,
            NutritionSupabaseConfig.validateConnection(
                "https://nutrition.example.com",
                "publishable-key-with-safe-length",
            ),
        )
        assertTrue(
            NutritionSupabaseConfig.validateConnection(
                "https://nutrition.example.com",
                "sb_" + "secret_this-key-must-never-be-stored",
            ) != null,
        )
        val serviceRolePayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"role\":\"service_role\"}".toByteArray(StandardCharsets.UTF_8),
        )
        assertTrue(
            NutritionSupabaseConfig.validateConnection(
                "https://nutrition.example.com",
                "header.$serviceRolePayload.signature-with-safe-length",
            ) != null,
        )
    }

    private fun connection() = NutritionSupabaseConfig(
        url = "https://nutrition.example.com",
        publishableKey = "publishable-key-with-safe-length",
    )

    private fun signedIn() = connection().copy(
        userId = "user-1",
        email = "fit@example.com",
        accessToken = "access-token",
        refreshToken = "refresh-token",
    )

    private fun verifiedDraft() = NutritionLabelDraft(
        documentId = "ocr-doc",
        productName = "검증 상품",
        basisAmount = 100.0,
        basisUnit = "g",
        nutrients = mapOf(
            NutritionField.CALORIES_KCAL to 100.0,
            NutritionField.PROTEIN_GRAMS to 10.0,
            NutritionField.CARBS_GRAMS to 15.0,
            NutritionField.FAT_GRAMS to 2.0,
            NutritionField.SODIUM_MG to 90.0,
            NutritionField.SATURATED_FAT_GRAMS to 1.0,
            NutritionField.SUGARS_GRAMS to 5.0,
        ),
    ).asUserVerified("2026-08-11T10:00:00+09:00")

    private class QueueTransport(vararg responses: NutritionHttpResponse) : NutritionHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<NutritionHttpRequest>()

        override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class FakeStore(initial: NutritionSupabaseConfig) : NutritionSupabaseStore {
        private var config = initial

        override fun read(): NutritionSupabaseConfig = config

        override fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig> =
            runCatching {
                config = NutritionSupabaseConfig(url = url, publishableKey = publishableKey)
                config
            }

        override fun saveSession(
            userId: String,
            email: String,
            accessToken: String,
            refreshToken: String,
        ): Result<NutritionSupabaseConfig> = runCatching {
            require(userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank())
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
}
