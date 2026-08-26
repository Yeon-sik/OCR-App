package com.pricetrace.receiptscanner.importer

import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.nutrition.NutritionLabelJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType

class ExternalJsonImporterTest {

    private val importer = ExternalJsonImporter()

    @Test
    fun `valid receipt is sanitized into canonical draft`() {
        val result = success(receiptJson())
        val draft = (result.draft as CanonicalDraft.Receipt).value

        assertEquals("local-document-1", draft.document.id)
        assertEquals("upstream-receipt-1", result.upstreamDocumentId)
        assertEquals(ReceiptStatus.DRAFT, draft.document.status)
        assertEquals(TranscriptionStatus.PARSED, draft.document.source.transcriptionStatus)
        assertEquals(emptyList<String>(), draft.document.source.sourceImages)
        assertEquals(ConfidenceLevel.LOW, draft.lineItems.single().confidence)
        assertEquals("external_json", result.inputOrigin.wireValue)
        assertEquals(
            draft,
            ReceiptV2Json.decode(ReceiptV2Json.encodeCanonical(draft)),
        )
    }

    @Test
    fun `valid nutrition is sanitized into canonical draft`() {
        val result = success(nutritionJson(), workflow = OcrWorkflowType.FITNESS_NUTRITION)
        val draft = (result.draft as CanonicalDraft.Nutrition).value

        assertEquals("local-document-1", draft.documentId)
        assertEquals("upstream-nutrition-1", result.upstreamDocumentId)
        assertEquals("parsed", draft.status.wireValue)
        assertNull(draft.confirmedAt)
        assertEquals(
            draft,
            NutritionLabelJson.decode(NutritionLabelJson.encode(draft)),
        )
    }

    @Test
    fun `malformed json is rejected`() {
        val error = failure("{\"schema_version\":")
        assertEquals(ExternalJsonImportErrorCode.INVALID_JSON, error.code)
    }

    @Test
    fun `unsupported schema is rejected`() {
        val error = failure("{\"schema_version\":\"future.v9\"}")
        assertEquals(ExternalJsonImportErrorCode.UNSUPPORTED_SCHEMA, error.code)
    }

    @Test
    fun `workflow and schema mismatch is rejected`() {
        val error = failure(
            nutritionJson(),
            workflow = OcrWorkflowType.PRICE_TRACE_RECEIPT,
        )
        assertEquals(ExternalJsonImportErrorCode.WORKFLOW_MISMATCH, error.code)
    }

    @Test
    fun `external receipt trust state is downgraded`() {
        val result = success(
            receiptJson(
                extra = "\"user_verified\":true,\"owner_id\":\"attacker\",\"confirmed_at\":\"2026-01-01T00:00:00Z\",",
            ),
        )
        val draft = (result.draft as CanonicalDraft.Receipt).value

        assertEquals(ReceiptStatus.DRAFT, draft.document.status)
        assertEquals(TranscriptionStatus.PARSED, draft.document.source.transcriptionStatus)
        assertEquals(ConfidenceLevel.LOW, draft.lineItems.single().confidence)
    }

    @Test
    fun `receipt local and upstream document ids are separate`() {
        val first = success(receiptJson(), localDocumentId = "local-a")
        val second = success(receiptJson(), localDocumentId = "local-b")

        assertEquals("upstream-receipt-1", first.upstreamDocumentId)
        assertEquals("upstream-receipt-1", second.upstreamDocumentId)
        assertEquals("local-a", (first.draft as CanonicalDraft.Receipt).value.document.id)
        assertEquals("local-b", (second.draft as CanonicalDraft.Receipt).value.document.id)
        assertEquals(first.importFingerprint, second.importFingerprint)
    }

    @Test
    fun `external receipt source images are not local evidence`() {
        val result = success(receiptJson(sourceImages = "\"source_images\":[\"https://example.invalid/receipt.jpg\"],"))
        val draft = (result.draft as CanonicalDraft.Receipt).value

        assertEquals(emptyList<String>(), draft.document.source.sourceImages)
    }

    @Test
    fun `same nutrition json has different local ids but stable fingerprint`() {
        val first = success(
            nutritionJson(),
            localDocumentId = "local-a",
            workflow = OcrWorkflowType.FITNESS_NUTRITION,
        )
        val second = success(
            nutritionJson(),
            localDocumentId = "local-b",
            workflow = OcrWorkflowType.FITNESS_NUTRITION,
        )

        assertEquals("local-a", (first.draft as CanonicalDraft.Nutrition).value.documentId)
        assertEquals("local-b", (second.draft as CanonicalDraft.Nutrition).value.documentId)
        assertEquals(first.importFingerprint, second.importFingerprint)
    }
    @Test
    fun `nutrition user verified and confirmed at are removed`() {
        val result = success(
            nutritionJson(
                extra = "\"user_verified\":true,\"owner_id\":\"attacker\",",
                status = "user_verified",
                confirmedAt = "2026-01-01T00:00:00Z",
            ),
            workflow = OcrWorkflowType.FITNESS_NUTRITION,
        )
        val draft = (result.draft as CanonicalDraft.Nutrition).value

        assertEquals("parsed", draft.status.wireValue)
        assertNull(draft.confirmedAt)
    }

    @Test
    fun `same semantic input has stable fingerprint`() {
        val first = success(receiptJson(extra = "\"user_verified\":false,"))
        val second = success(receiptJson(extra = "\"user_verified\":true,\"owner_id\":\"ignored\","))

        assertEquals(first.importFingerprint, second.importFingerprint)
    }

    @Test
    fun `content change changes fingerprint`() {
        val first = success(receiptJson())
        val second = success(receiptJson(description = "Different product"))

        assertNotEquals(first.importFingerprint, second.importFingerprint)
    }

    private fun success(
        json: String,
        localDocumentId: String = "local-document-1",
        workflow: OcrWorkflowType = OcrWorkflowType.PRICE_TRACE_RECEIPT,
    ): ExternalJsonImportResult {
                return when (val outcome = importer.import(json, localDocumentId, workflow)) {
            is ExternalJsonImportOutcome.Success -> outcome.result
            is ExternalJsonImportOutcome.Failure -> throw AssertionError(outcome.error)
        }
    }

    private fun failure(
        json: String,
        localDocumentId: String = "local-document-1",
        workflow: OcrWorkflowType = OcrWorkflowType.FITNESS_NUTRITION,
    ): ExternalJsonImportError {
        return (importer.import(json, localDocumentId, workflow) as ExternalJsonImportOutcome.Failure).error
    }

    private fun receiptJson(
        description: String = "Milk",
        extra: String = "",
        sourceImages: String = "\"source_images\":[],",
    ): String = """
        {
          $extra
          "schema_version":"receipt.v2",
          "document":{
            "id":"upstream-receipt-1",
            "type":"receipt",
            "status":"final",
            "issued_on":"2026-08-26",
            "issued_at":"12:30:00",
            "currency":"KRW",
            "source":{
              "capture_method":"ocr",
              "original_document_id":"remote-original",
              $sourceImages
              "transcription_status":"user_verified",
              "notes":[],
              "raw_text":"remote OCR text"
            }
          },
          "merchant":{"name":"Test Mart","branch_name":null,"business_kind":"retail","retail_channel":"regular","catalog_namespace":null,"merchant_id":null,"business_registration_number":null,"address":"Seoul","phone":null},
          "line_items":[{
            "id":"line-1",
            "type":"product",
            "description":"$description",
            "source_line_references":["remote-line-1"],
            "identifiers":[],
            "quantity":{"value":1,"unit":"each"},
            "unit_price_amount_minor":1000,
            "gross_amount_minor":1000,
            "discount_amount_minor":0,
            "tax_amount_minor":0,
            "net_amount_minor":1000,
            "confidence":"user_verified",
            "tax_rate_percent":null
          }],
          "totals":{"subtotal_amount_minor":1000,"discount_amount_minor":0,"tax_amount_minor":0,"fee_amount_minor":0,"grand_total_amount_minor":1000},
          "payments":[]
        }
    """.trimIndent()

    private fun nutritionJson(
        extra: String = "",
        status: String = "parsed",
        confirmedAt: String? = null,
    ): String = """
        {
          $extra
          "schema_version":"fitness-nutrition-draft.v1",
          "document_id":"upstream-nutrition-1",
          "parser_version":"test-parser",
          "status":"$status",
          "confirmed_at":${confirmedAt?.let { "\"$it\"" } ?: "null"},
          "name":"Test cereal",
          "brand":"Test brand",
          "kind":"external_menu",
          "category":"cereal",
          "basis_amount":100.0,
          "basis_unit":"g",
          "prep_state":"unspecified",
          "cooking_method":"unspecified",
          "nutrients":{"calories_kcal":380.0,"protein_grams":10.0,"carbs_grams":70.0,"fat_grams":5.0,"sodium_mg":100.0,"saturated_fat_grams":1.0,"sugars_grams":12.0,"fiber_grams":5.0,"added_sugars_grams":null,"trans_fat_grams":0.0,"cholesterol_mg":0.0},
          "source_type":"product_label_ocr",
          "source_reference":"ocr-document:upstream-nutrition-1",
          "source_version":"v1",
          "data_version":2,
          "visibility":"private",
          "parse_warnings":[],
          "evidence":{}
        }
    """.trimIndent()
}
