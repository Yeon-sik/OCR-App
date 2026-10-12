package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class YeonsikOcrV2ModeDiagnosticTest {
    @Test fun merchantWithProductCandidateRejectsAndExplainsPackagedProductMode() {
        val packaged = packaged()
        rejects(packaged.copy(mode = IngestionMode.MERCHANT, merchantCandidate = MerchantCandidate("가게"), nutrition = emptyList()),
            "V2 merchant mode requires product_candidates=[]; packaged product artifacts require mode=packaged_product.")
    }

    @Test fun merchantWithProductLabelRejectsWithNutritionDiagnostic() {
        rejects(packaged().copy(mode = IngestionMode.MERCHANT, merchantCandidate = MerchantCandidate("가게"), productCandidates = emptyList()),
            "V2 merchant mode requires nutrition=[]; product_label nutrition requires mode=packaged_product.")
    }

    @Test fun merchantWithConsumptionRejectsBeforeDanglingReferenceDiagnostic() {
        val consumption = YeonsikOcrV2Json.decode(example("yeonsik-ocr.v2.restaurant.example.json"), "restaurant").consumption
        assertTrue(consumption.isNotEmpty())
        rejects(merchant().copy(consumption = consumption), "V2 merchant mode requires consumption=[].")
    }

    @Test fun merchantRequiresCandidateEmptyLinksAndNonFoodReceipt() {
        rejects(merchant().copy(merchantCandidate = null), "V2 merchant mode requires merchant_candidate.")
        rejects(merchant().copy(links = listOf(IngestionLink("missing", "missing"))), "V2 merchant mode requires links=[].")
        val receipt = YeonsikOcrV2Json.decode(example("yeonsik-ocr.v2.restaurant.example.json"), "food-receipt").receipt!!
        rejects(merchant().copy(receipt = receipt), "V2 merchant mode does not allow a food_service receipt; use restaurant mode.")
    }

    @Test fun packagedProductWithNonFoodReceiptAndCandidateDecodesAndImports() = runBlocking {
        val envelope = packagedWithReceipt().copy(nutrition = emptyList())
        val canonical = YeonsikOcrV2Json.encode(envelope)
        val decoded = YeonsikOcrV2Json.decode(canonical, "retail-product-receipt")
        assertEquals(IngestionMode.PACKAGED_PRODUCT, decoded.mode)
        assertNull(decoded.merchantCandidate)
        assertEquals(envelope.receipt!!.merchant, decoded.receipt!!.merchant)
        assertTrue(decoded.productCandidates.isNotEmpty())
        assertTrue(CanonicalIngestionUseCase(InMemoryIngestionSessionStore()).importJson(canonical, "retail-product-receipt") is CanonicalImportResult.Success)
    }

    @Test fun packagedProductWithNonFoodReceiptCandidateAndLabelPreservesReceiptMerchant() = runBlocking {
        val envelope = packagedWithReceipt()
        val canonical = YeonsikOcrV2Json.encode(envelope)
        val decoded = YeonsikOcrV2Json.decode(canonical, "retail-product-label-receipt")
        assertEquals(envelope.receipt!!.merchant, decoded.receipt!!.merchant)
        assertTrue(decoded.nutrition.all { it is IngestionNutrition.ProductLabel })
        assertTrue(CanonicalIngestionUseCase(InMemoryIngestionSessionStore()).importJson(canonical, "retail-product-label-receipt") is CanonicalImportResult.Success)
    }

    @Test fun packagedProductRejectsTopLevelMerchantAndExplainsReceiptMerchantMayRemain() {
        rejects(packagedWithReceipt().copy(merchantCandidate = MerchantCandidate("충돌 상점")),
            "V2 packaged_product requires merchant_candidate=null; receipt.merchant may remain.")
    }

    @Test fun packagedProductRejectsFoodServiceReceipt() {
        val foodReceipt = YeonsikOcrV2Json.decode(example("yeonsik-ocr.v2.restaurant.example.json"), "food-receipt").receipt
        rejects(packaged().copy(receipt = foodReceipt), "V2 packaged_product does not allow a food_service receipt.")
    }

    @Test fun packagedProductRequiresCandidateOrLabelAndEmptyLinks() {
        rejects(packaged().copy(nutrition = emptyList(), productCandidates = emptyList()),
            "V2 packaged_product requires a product candidate or product_label nutrition.")
        rejects(packaged().copy(links = listOf(IngestionLink("missing", "missing"))), "V2 packaged_product requires links=[].")
    }

    @Test fun packagedProductRejectsRestaurantNutrition() {
        val restaurant = YeonsikOcrV2Json.decode(example("yeonsik-ocr.v2.restaurant-food-photo.example.json"), "restaurant")
        rejects(restaurant.copy(mode = IngestionMode.PACKAGED_PRODUCT, merchantCandidate = null, targets = emptySet()),
            "V2 packaged_product nutrition must contain only product_label artifacts.")
    }

    @Test fun legacyMerchantReceiptAndRestaurantFixturesStillPass() {
        val merchant = merchant()
        assertEquals(IngestionMode.MERCHANT, YeonsikOcrV2Json.decode(YeonsikOcrV2Json.encode(merchant), "merchant-only").mode)
        val receiptOnly = merchant.copy(receipt = packagedWithReceipt().receipt)
        assertEquals(receiptOnly.receipt!!.merchant, YeonsikOcrV2Json.decode(YeonsikOcrV2Json.encode(receiptOnly), "receipt-only").receipt!!.merchant)
        listOf("yeonsik-ocr.v2.restaurant.example.json", "yeonsik-ocr.v2.restaurant-food-photo.example.json").forEach { name ->
            assertEquals(IngestionMode.RESTAURANT, YeonsikOcrV2Json.decode(example(name), "legacy-restaurant").mode)
        }
    }

    @Test fun invalidModeImportFailsClosedWithoutRepairOrSession() = runBlocking {
        val invalid = packaged().copy(mode = IngestionMode.MERCHANT, merchantCandidate = MerchantCandidate("원본 상점"))
        val canonical = YeonsikOcrV2Json.encode(invalid)
        val store = InMemoryIngestionSessionStore()
        val result = CanonicalIngestionUseCase(store).importJson(canonical, "bad-mode", "bad-mode-session") as CanonicalImportResult.Failure
        assertTrue(result.error!!.detail!!.contains("merchant mode requires product_candidates=[]"))
        assertFalse(result.error.detail!!.contains("Failed requirement."))
        assertNull(store.get("bad-mode-session"))
        assertEquals(canonical, YeonsikOcrV2Json.encode(invalid))
        assertEquals("원본 상점", invalid.merchantCandidate!!.name)
        assertEquals(IngestionMode.MERCHANT, invalid.mode)
    }

    private fun rejects(envelope: YeonsikOcrEnvelope, expected: String) {
        val canonical = YeonsikOcrV2Json.encode(envelope)
        val error = assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV2Json.decode(canonical, "bad-mode") }
        assertEquals(expected, error.message)
        val imported = ExternalJsonImporter().import(canonical, "bad-mode") as ExternalJsonImportOutcome.Failure
        assertTrue(imported.error.detail.orEmpty(), imported.error.detail.orEmpty().contains(expected))
        assertFalse(imported.error.detail.orEmpty().contains("Failed requirement."))
        assertTrue(CanonicalEnvelopeValidator.validate(envelope).single().contains(expected))
    }

    private fun packaged() = YeonsikOcrV2Json.decode(example("yeonsik-ocr.v2.packaged-product.example.json"), "v2-mode")
        .copy(targets = emptySet(), consumption = emptyList())
    private fun merchant() = YeonsikOcrEnvelope(
        schemaVersion = YEONSIK_OCR_V2_SCHEMA, mode = IngestionMode.MERCHANT,
        source = IngestionSource("chatgpt", emptyList(), "가게 Test Mart"), merchantCandidate = MerchantCandidate("Test Mart"),
    )
    private fun packagedWithReceipt(): YeonsikOcrEnvelope {
        val receipt = ((ExternalJsonImporter().import(example("receipt.v2.example.json"), "non-food-receipt") as ExternalJsonImportOutcome.Success).result.draft as CanonicalDraft.Receipt).value
        assertNotEquals(BusinessKind.FOOD_SERVICE, receipt.merchant.businessKind)
        return packaged().copy(receipt = receipt)
    }
    private fun example(name: String) = sequenceOf(File("examples", name), File("../examples", name)).first(File::isFile).readText()
}
