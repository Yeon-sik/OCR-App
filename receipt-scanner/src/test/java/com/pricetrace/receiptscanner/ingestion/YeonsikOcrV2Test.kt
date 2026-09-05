package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YeonsikOcrV2Test {
    @Test
    fun `v2 examples route product and meal component facts without importing external verification`() {
        val importer = ExternalJsonImporter()
        val packaged = success(importer.import(
            readExample("yeonsik-ocr.v2.packaged-product.example.json"),
            "local-packaged-v2",
        ))
        assertEquals(OcrWorkflowType.FITNESS_NUTRITION, packaged.workflowType)
        val packagedEnvelope = (packaged.draft as CanonicalDraft.Envelope).value
        assertEquals(YEONSIK_OCR_V2_SCHEMA, packagedEnvelope.schemaVersion)
        assertEquals("product-1", packagedEnvelope.productCandidates.single().clientKey)
        assertEquals("8801234567890", packagedEnvelope.productCandidates.single().barcode)
        assertEquals("2026-08-27T08:10:00+09:00", packagedEnvelope.consumption.single().consumedAt)
        assertEquals(40.0, packagedEnvelope.consumption.single().items.single().amount, 0.0)
        assertEquals(ConsumptionVerificationStatus.UNVERIFIED, packagedEnvelope.consumption.single().status)
        assertEquals(
            setOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.FITNESS_MEAL,
                IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
            ),
            packagedEnvelope.targets,
        )

        val restaurant = success(importer.import(
            readExample("yeonsik-ocr.v2.restaurant.example.json"),
            "local-restaurant-v2",
        ))
        assertEquals(OcrWorkflowType.PRICE_TRACE_RESTAURANT_RECEIPT, restaurant.workflowType)
        val restaurantEnvelope = (restaurant.draft as CanonicalDraft.Envelope).value
        val component = restaurantEnvelope.nutrition.single { it.clientKey == "food-3" }
            as IngestionNutrition.MealComponentEstimate
        assertEquals(null, component.lineId)
        assertEquals(null, component.reference?.restaurantMenuId)
        assertTrue(restaurantEnvelope.links.none { it.nutritionClientKey == component.clientKey })
        assertEquals("2026-08-27T19:30:00+09:00", restaurantEnvelope.consumption.single().consumedAt)
        assertTrue(IngestionProjection.FITNESS_MEAL in restaurantEnvelope.targets)
        assertFalse(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE in restaurantEnvelope.targets)
    }

    @Test
    fun `v2 rejects identity fields that GPT must not provide`() {
        val invalid = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace(
                "\"source_version\": \"chatgpt-vision-v2\",",
                "\"source_version\": \"chatgpt-vision-v2\", \"catalog_product_id\": \"not-a-client-fact\",",
            )

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.decode(invalid, "local-invalid-v2")
        }
    }

    @Test
    fun `v2 rejects malformed observable product identifiers`() {
        val invalid = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace("\"barcode\": \"8801234567890\"", "\"barcode\": \"not-a-barcode\"")

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.decode(invalid, "local-invalid-product-identifier")
        }
    }

    @Test
    fun `v2 product candidate requires a readable product photo`() {
        val imported = success(ExternalJsonImporter().import(
            readExample("yeonsik-ocr.v2.packaged-product.example.json"),
            "local-product-evidence",
        ))
        val envelope = (imported.draft as CanonicalDraft.Envelope).value
        val artifactKey = IngestionArtifactKeys.productCandidate("product-1")

        assertTrue(
            IngestionEvidenceGate.evaluate(
                envelope = envelope,
                evidence = listOf(LocalEvidence("product-photo-1", SourceAttachmentType.PRODUCT_PHOTO, true)),
                artifactKeys = setOf(artifactKey),
            ).isAllowed,
        )
        assertFalse(
            IngestionEvidenceGate.evaluate(
                envelope = envelope,
                evidence = listOf(LocalEvidence("nutrition-label-1", SourceAttachmentType.NUTRITION_LABEL, true)),
                artifactKeys = setOf(artifactKey),
            ).isAllowed,
        )
    }

    @Test
    fun `v2 review object is strict but its status cannot self authorize`() {
        val invalid = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace(
                "\"review\": {\"status\": \"ready\", \"blocking_issues\": [], \"warnings\": []}",
                "\"review\": {\"status\": \"ready\", \"blocking_issues\": [], \"warnings\": [], \"user_verified\": true}",
            )

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.decode(invalid, "local-invalid-review")
        }
    }

    @Test
    fun `v2 cannot infer meal time from a missing consumed_at`() {
        val invalid = readExample("yeonsik-ocr.v2.packaged-product.example.json")
            .replace("\"consumed_at\": \"2026-08-27T08:10:00+09:00\"", "\"consumed_at\": null")

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV2Json.decode(invalid, "local-missing-meal-time")
        }
    }

    @Test
    fun `version codec still dispatches the original v1 contract`() {
        val v1 = readExample("yeonsik-ocr.packaged-product.example.json")
        val envelope = YeonsikOcrEnvelopeCodec.decode(v1, "local-v1")
        assertEquals(YEONSIK_OCR_SCHEMA, envelope.schemaVersion)
        val encoded = YeonsikOcrEnvelopeCodec.encode(envelope)
        assertEquals(YEONSIK_OCR_SCHEMA, JsonSupport.parse(encoded)["schema_version"]?.jsonPrimitive?.content)
        assertNotNull(envelope.nutrition.single())
        assertFalse(JsonSupport.parse(encoded).containsKey("product_candidates"))
    }

    private fun success(outcome: ExternalJsonImportOutcome): com.pricetrace.receiptscanner.importer.ExternalJsonImportResult =
        (outcome as? ExternalJsonImportOutcome.Success)?.result
            ?: error("expected successful import: $outcome")

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }

    private object JsonSupport {
        private val json = Json { ignoreUnknownKeys = false }
        fun parse(value: String): JsonObject = json.parseToJsonElement(value).jsonObject
    }
}
