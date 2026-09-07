package com.pricetrace.receiptocr

import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCanonicalJsonValidatorTest {
    @Test
    fun `android validator uses the same core plan and confirms a no image manual review`() = runBlocking {
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            now = { "2026-09-08T00:00:00Z" },
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            newLocalDocumentId = { "android-parity-document" },
            newIngestionId = { "android-parity-ingestion" },
        )

        val imported = validator.importJson(merchantJson(), AndroidCanonicalJsonValidatorState())
        val envelope = requireNotNull(imported.envelope)
        val plan = requireNotNull(imported.plan)
        assertEquals(CanonicalProjectionPlanner.plan(envelope), plan)
        assertEquals(setOf(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE), plan.eligible)
        assertEquals(plan.eligible, imported.selectedProjections)

        val confirmed = validator.confirm(imported)
        assertEquals(IngestionReviewStatus.READY, confirmed.envelope?.review?.status)
        assertEquals(VerificationBasis.MANUAL_CANONICAL_REVIEW, confirmed.envelope?.review?.verificationBasis)
        assertNotNull(confirmed.session)
        assertTrue(confirmed.error == null)
    }

    private fun merchantJson(): String = Json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schema_version", JsonPrimitive("yeonsik-ocr.v3"))
            put("mode", JsonPrimitive("merchant"))
            put("source", buildJsonObject {
                put("producer", JsonPrimitive("chatgpt"))
                put("source_files", JsonArray(emptyList<JsonElement>()))
                put("user_text", JsonNull)
            })
            put("merchant_candidate", buildJsonObject {
                put("name", JsonPrimitive("Parity Merchant"))
                put("business_kind", JsonPrimitive("retail"))
                put("branch_name", JsonNull)
                put("address", JsonNull)
                put("phone", JsonNull)
                put("business_registration_number", JsonNull)
                put("source_attachment_ids", JsonArray(emptyList<JsonElement>()))
                put("source_namespace", JsonNull)
                put("source_location_code", JsonNull)
            })
            put("receipt", JsonNull)
            put("product_candidates", JsonArray(emptyList<JsonElement>()))
            put("price_observations", JsonArray(emptyList<JsonElement>()))
            put("nutrition", JsonArray(emptyList<JsonElement>()))
            put("consumption", JsonArray(emptyList<JsonElement>()))
            put("classification_hints", buildJsonObject { put("cashos", buildJsonObject {}) })
            put("links", JsonArray(emptyList<JsonElement>()))
            put("projection_targets", JsonArray(listOf(JsonPrimitive("cashos_receipt"))))
            put("review", buildJsonObject {
                put("status", JsonPrimitive("ready"))
                put("blocking_issues", JsonArray(emptyList<JsonElement>()))
                put("warnings", JsonArray(emptyList<JsonElement>()))
                put("verification_basis", JsonPrimitive("SOURCE_EVIDENCE"))
                put("user_verified", JsonPrimitive(true))
            })
        },
    )
}
