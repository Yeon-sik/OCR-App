package com.pricetrace.receiptocr

import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val draftReview = Json.parseToJsonElement(imported.canonicalJson)
            .jsonObject["review"]!!.jsonObject
        assertEquals(setOf("status", "blocking_issues", "warnings"), draftReview.keys)
        assertFalse(imported.canonicalJson.contains("verification_basis"))

        val confirmed = validator.confirm(imported)
        assertEquals(IngestionReviewStatus.READY, confirmed.envelope?.review?.status)
        assertEquals(VerificationBasis.MANUAL_CANONICAL_REVIEW, confirmed.envelope?.review?.verificationBasis)
        val persistedReview = Json.parseToJsonElement(confirmed.canonicalJson)
            .jsonObject["review"]!!.jsonObject
        assertTrue(persistedReview.containsKey("verification_basis"))
        assertTrue(persistedReview.containsKey("user_verified"))
        assertNotNull(confirmed.session)
        assertTrue(confirmed.error == null)
    }

    @Test
    fun `android text-only manual review submits product before standalone price`() = runBlocking {
        val order = mutableListOf<IngestionProjection>()
        val product = RecordingSubmitter(order)
        val price = RecordingSubmitter(order)
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to product,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to price,
            ),
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            newLocalDocumentId = { "android-text-only-document" },
            newIngestionId = { "android-text-only-ingestion" },
        )

        var state = validator.importJson(
            readExample("yeonsik-ocr.v3.text-only-retail.example.json"),
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, state.envelope?.review?.status)
        assertEquals(
            setOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            ),
            state.plan?.eligible,
        )

        state = validator.confirm(state)
        assertEquals(IngestionReviewStatus.READY, state.envelope?.review?.status)
        assertEquals(VerificationBasis.MANUAL_CANONICAL_REVIEW, state.envelope?.review?.verificationBasis)

        state = validator.submit(state)
        assertEquals(
            listOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            ),
            order,
        )
        val session = requireNotNull(state.session)
        assertEquals(
            com.pricetrace.receiptscanner.ingestion.ProjectionStatus.UPLOADED,
            session.projections.single {
                it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE
            }.status,
        )
        assertEquals(
            com.pricetrace.receiptscanner.ingestion.ProjectionStatus.UPLOADED,
            session.projections.single {
                it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION
            }.status,
        )
    }

    @Test
    fun `android validator imports purchase v4 and submits CashOS without creating Fitness`() = runBlocking {
        val order = mutableListOf<IngestionProjection>()
        val cashOs = RecordingSubmitter(order)
        val priceTrace = RecordingSubmitter(order)
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.CASHOS_TRANSACTION to cashOs,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to priceTrace,
            ),
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            newLocalDocumentId = { "android-purchase-v4-document" },
            newIngestionId = { "android-purchase-v4-ingestion" },
        )

        var state = validator.importJson(
            readExample("yeonsik-ocr.v4.purchase.text-only.example.json"),
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals("yeonsik-ocr.v4", state.envelope?.schemaVersion)
        assertEquals(setOf(IngestionProjection.CASHOS_TRANSACTION), state.plan?.eligible)
        assertEquals(setOf(IngestionProjection.CASHOS_TRANSACTION), state.selectedProjections)

        state = validator.confirm(state)
        assertEquals(IngestionReviewStatus.READY, state.envelope?.review?.status)
        state = validator.submit(state)

        assertEquals(listOf(IngestionProjection.CASHOS_TRANSACTION), order)
        val session = requireNotNull(state.session)
        assertEquals(
            com.pricetrace.receiptscanner.ingestion.ProjectionStatus.UPLOADED,
            session.projections.single { it.projection == IngestionProjection.CASHOS_TRANSACTION }.status,
        )
        assertTrue(session.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }
            .status == com.pricetrace.receiptscanner.ingestion.ProjectionStatus.DISABLED)
        assertTrue(session.projections.single { it.projection == IngestionProjection.FITNESS_NUTRITION }
            .status == com.pricetrace.receiptscanner.ingestion.ProjectionStatus.DISABLED)
    }

    private class RecordingSubmitter(
        private val order: MutableList<IngestionProjection>,
    ) : IngestionProjectionSubmitter {
        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
            order += request.projection
            return ProjectionSubmission.Success("android-" + request.projection.wireValue)
        }
    }

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
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
