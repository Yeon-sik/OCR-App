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

    @Test fun uuidAndOtherOpaqueLogicalKeysRemainValidProvenLegacyAuthorities() {
        listOf("550e8400-e29b-41d4-a716-446655440000", "legacy-prefixed-key", "12345", "opaque legacy key").forEach { logical ->
            val current = candidate.copy(clientKey = logical)
            val row = JsonObject(accepted + mapOf(
                "clientKey" to JsonPrimitive(logical),
                "productClientKey" to JsonPrimitive(logical),
                "sourceRevisionFingerprint" to JsonPrimitive(PriceTraceCandidatePayload.revision(current).sourceRevisionFingerprint),
            ))
            val result = requireNotNull(PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata(row), listOf(current)))
            assertEquals(mapOf(logical to logical), PriceTraceCandidatePayload.acceptedKeys(result, listOf(current)))
            assertEquals(mapOf(logical to logical), PriceTraceCandidatePayload.acceptedKeys(result))
        }
    }

    @Test fun arbitraryThirdAuthorityIsDroppedAndCannotBeRecoveredForReplay() {
        val row = JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonPrimitive("arbitrary-third-key")))
        assertNull(PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata(row), listOf(candidate)))
        assertThrows(IllegalArgumentException::class.java) { PriceTraceCandidatePayload.acceptedKeys(metadata(row), listOf(candidate)) }
        assertThrows(IllegalArgumentException::class.java) { PriceTraceCandidatePayload.acceptedKeys(metadata(row)) }
    }

    @Test fun currentDerivedAuthorityIsAcceptedButAnAuthorityFromAnotherRevisionIsRejected() {
        val currentKey = PriceTraceCandidatePayload.revision(candidate).authorityClientKey
        val current = JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonPrimitive(currentKey)))
        val result = requireNotNull(PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata(current), listOf(candidate)))
        assertEquals(mapOf(candidate.clientKey to currentKey), PriceTraceCandidatePayload.acceptedKeys(result, listOf(candidate)))
        val oldKey = PriceTraceCandidatePayload.revision(candidate.copy(specification = "other source")).authorityClientKey
        val staleAuthority = JsonObject(current + ("priceTraceAuthorityClientKey" to JsonPrimitive(oldKey)))
        assertNull(PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata(staleAuthority), listOf(candidate)))
        assertThrows(IllegalArgumentException::class.java) { PriceTraceCandidatePayload.acceptedKeys(metadata(staleAuthority), listOf(candidate)) }
    }

    @Test fun candidateAwareRecoveryRequiresAcceptedIdentityAndTheExactCurrentFingerprint() {
        val unproven = listOf(
            JsonObject(accepted - "candidateId"),
            JsonObject(accepted - "sourceRevisionFingerprint"),
            JsonObject(accepted + ("sourceRevisionFingerprint" to JsonPrimitive("f".repeat(64)))),
        )
        unproven.forEach { row ->
            assertTrue(PriceTraceCandidatePayload.acceptedKeys(metadata(row), listOf(candidate)).isEmpty())
            assertNull(PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata(row), listOf(candidate)))
        }
    }

    @Test fun duplicateRepresentationsCannotProduceConflictingAuthorityMaps() {
        val root = Json.parseToJsonElement(metadata(accepted)).jsonObject
        val conflicting = JsonObject(accepted + ("priceTraceAuthorityClientKey" to JsonPrimitive(PriceTraceCandidatePayload.revision(candidate).authorityClientKey)))
        val metadata = JsonObject(root + ("products" to JsonArray(listOf(conflicting)))).toString()
        assertThrows(IllegalArgumentException::class.java) {
            PriceTraceCandidatePayload.preserveCurrentCandidateAuthorityMetadata(metadata, listOf(candidate))
        }
    }
}
