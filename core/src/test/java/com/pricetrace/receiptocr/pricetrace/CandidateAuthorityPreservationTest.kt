package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.ingestion.ProductCandidateEvidence
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CandidateAuthorityPreservationTest {
    private val candidate = ProductCandidate("logical-a", "Same display name", evidence = listOf(
        ProductCandidateEvidence(sourceType = "user_statement", sourceRef = "user-statement:a", field = "product_name", observedValue = "Same display name"),
    ))
    private val accepted = buildJsonObject {
        put("clientKey", candidate.clientKey)
        put("productClientKey", candidate.clientKey)
        put("candidateId", "00000000-0000-4000-8000-000000000001")
        put("sourceRevisionFingerprint", PriceTraceCandidatePayload.revision(candidate).sourceRevisionFingerprint)
    }

    private fun metadata(vararg rows: JsonObject) = buildJsonObject {
        put("responses", JsonArray(rows.toList()))
        put("brandResolutions", JsonArray(listOf(buildJsonObject { put("stale", true) })))
    }.toString()

    @Test fun staleUnknownUnprovenAndMalformedAuthorityRowsAreDropped() {
        val invalidRows = listOf(
            JsonObject(accepted - "sourceRevisionFingerprint"),
            JsonObject(accepted + ("sourceRevisionFingerprint" to JsonPrimitive("f".repeat(64)))),
            JsonObject(accepted + ("clientKey" to JsonPrimitive("other-logical-key"))),
            JsonObject(accepted - "candidateId"),
            JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonPrimitive(" whitespace "))),
            JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonPrimitive("00000000-0000-4000-8000-000000000001"))),
            JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonNull)),
            JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonPrimitive(12))),
        )
        invalidRows.forEach { row ->
            assertNull(row.toString(), PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata(row), listOf(candidate)))
        }
    }

    @Test fun provenLegacyKeySurvivesWithoutRetainingRemoteBrandCache() {
        val result = requireNotNull(PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata(accepted), listOf(candidate)))
        assertEquals(mapOf(candidate.clientKey to candidate.clientKey), PriceTraceCandidatePayload.acceptedKeys(result, listOf(candidate)))
        assertFalse(Json.parseToJsonElement(result).jsonObject.containsKey("brandResolutions"))
    }

    @Test fun duplicateRepresentationsCannotProduceConflictingAuthorityMaps() {
        val root = Json.parseToJsonElement(metadata(accepted)).jsonObject
        val conflicting = JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonPrimitive("different-authority")))
        val metadata = JsonObject(root + ("products" to JsonArray(listOf(conflicting)))).toString()
        assertThrows(IllegalArgumentException::class.java) {
            PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata, listOf(candidate))
        }
    }
}
