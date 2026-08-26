package com.pricetrace.receiptscanner.domain

import com.pricetrace.receiptscanner.SyntheticFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestaurantPlaceResolutionTest {
    @Test
    fun `OCR merchant becomes an unconfirmed candidate with evidence`() {
        val parsed = SyntheticFixtures.ocrDocument().let { document ->
            com.pricetrace.receiptscanner.parser.GenericReceiptParser().parse(document)
        }

        val resolution = parsed.placeResolution
        val receiptV2 = parsed.toReceiptV2()

        assertEquals(PlaceResolutionStatus.CANDIDATES_READY, resolution.status)
        assertEquals(1, resolution.candidates.size)
        assertEquals("가상마트", resolution.candidates.single().displayName)
        assertEquals(PlaceCandidateSource.OCR, resolution.candidates.single().source)
        assertTrue(resolution.candidates.single().evidenceLineReferences.isNotEmpty())
        assertNull(resolution.selectedCandidateId)
        assertFalse(resolution.candidates.single().sourceLocationCode != null)
        assertEquals(PlaceResolutionStatus.CANDIDATES_READY, receiptV2.placeResolution.status)
    }

    @Test
    fun `a selected provider candidate is explicitly user confirmed`() {
        val candidate = RestaurantPlaceCandidate(
            id = "naver:location-123",
            source = PlaceCandidateSource.NAVER_LOCAL,
            displayName = "육회식당",
            sourceLocationCode = "location-123",
        )
        val resolution = RestaurantPlaceResolution(
            status = PlaceResolutionStatus.CANDIDATES_READY,
            candidates = listOf(candidate),
        )

        val confirmed = resolution.confirm(candidate.id)

        assertEquals(PlaceResolutionStatus.USER_CONFIRMED, confirmed.status)
        assertEquals(candidate, confirmed.selectedCandidate)
    }

    @Test
    fun `missing OCR merchant remains unresolved`() {
        val parsed = SyntheticFixtures.verifiedCandidate().let { receipt ->
            ParsedReceipt(
                documentId = receipt.document.id,
                sourceImages = receipt.document.source.sourceImages,
                rawText = receipt.document.source.rawText,
            )
        }

        assertEquals(
            PlaceResolutionStatus.UNRESOLVED,
            RestaurantPlaceResolution.fromParsedReceipt(parsed).status,
        )
    }
}
