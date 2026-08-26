package com.pricetrace.receiptscanner.nutrition

import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.ocr.OcrBlock
import com.pricetrace.receiptscanner.ocr.OcrDocument
import com.pricetrace.receiptscanner.ocr.OcrEngineInfo
import com.pricetrace.receiptscanner.ocr.OcrLine
import com.pricetrace.receiptscanner.ocr.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionCorrectionPolicyTest {
    @Test
    fun acceptsEvidenceBoundCandidateAndAppliesItAsAnUnverifiedDraft() {
        val document = document(
            "튼튼 프로틴바",
            "영양정보 1회 제공량 45 g",
            "열량 210 kcal",
        )
        val draft = NutritionLabelParser().parse(document)
        val request = NutritionCorrectionRequestFactory.create(draft, document)
        val candidate = candidate(
            fieldPath = NutritionField.CALORIES_KCAL.wireKey,
            oldValue = "210",
            proposedValue = "220",
            sourceLineIds = listOf("line-2"),
        )

        val result = NutritionCorrectionPolicy.validateBatch(draft, request, listOf(candidate))

        assertEquals(1, result.accepted.size)
        val updated = NutritionCorrectionPolicy.apply(draft, candidate)
        assertEquals(220.0, updated?.value(NutritionField.CALORIES_KCAL))
        assertEquals(NutritionDraftStatus.PARSED, updated?.status)
    }

    @Test
    fun rejectsStaleAndUnrelatedCandidates() {
        val document = document(
            "튼튼 프로틴바",
            "영양정보",
            "열량 210 kcal",
            "단백질 15 g",
        )
        val draft = NutritionLabelParser().parse(document)
        val request = NutritionCorrectionRequestFactory.create(draft, document)
        val stale = candidate(
            fieldPath = NutritionField.CALORIES_KCAL.wireKey,
            oldValue = "999",
            proposedValue = "220",
            sourceLineIds = listOf("line-2"),
        )
        val unrelated = candidate(
            fieldPath = NutritionField.PROTEIN_GRAMS.wireKey,
            oldValue = "15",
            proposedValue = "20",
            sourceLineIds = listOf("line-2"),
        )

        val result = NutritionCorrectionPolicy.validateBatch(draft, request, listOf(stale, unrelated))

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            setOf(
                NutritionCorrectionRejectionReason.STALE_OLD_VALUE,
                NutritionCorrectionRejectionReason.UNRELATED_SOURCE_EVIDENCE,
            ),
            result.rejected.map(RejectedNutritionCorrection::reason).toSet(),
        )
    }

    @Test
    fun rejectsInvalidCategoryAndNegativeNutrient() {
        val document = document(
            "상품명",
            "영양정보",
            "열량 210 kcal",
        )
        val draft = NutritionLabelParser().parse(document)
        val request = NutritionCorrectionRequestFactory.create(draft, document)
        val invalidCategory = candidate(
            fieldPath = NutritionCorrectionFieldPaths.CATEGORY,
            oldValue = draft.category,
            proposedValue = "not-a-category",
            sourceLineIds = listOf("line-0"),
        )
        val negative = candidate(
            fieldPath = NutritionField.CALORIES_KCAL.wireKey,
            oldValue = "210",
            proposedValue = "-1",
            sourceLineIds = listOf("line-2"),
        )

        val result = NutritionCorrectionPolicy.validateBatch(draft, request, listOf(invalidCategory, negative))

        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.all { it.reason == NutritionCorrectionRejectionReason.INVALID_VALUE })
    }

    @Test
    fun separatesOverlappingFatAndSugarEvidenceAliases() {
        val document = document(
            "상품명",
            "영양정보",
            "지방 10 g",
            "포화지방 2 g",
            "당류 8 g",
            "첨가당 4 g",
        )
        val draft = NutritionLabelParser().parse(document)
        val request = NutritionCorrectionRequestFactory.create(draft, document)
        val fatTarget = request.targets.first { it.fieldPath == NutritionField.FAT_GRAMS.wireKey }
        val sugarsTarget = request.targets.first { it.fieldPath == NutritionField.SUGARS_GRAMS.wireKey }

        assertTrue("line-2" in fatTarget.sourceLineIds)
        assertTrue("line-3" !in fatTarget.sourceLineIds)
        assertTrue("line-4" in sugarsTarget.sourceLineIds)
        assertTrue("line-5" !in sugarsTarget.sourceLineIds)
    }

    private fun candidate(
        fieldPath: String,
        oldValue: String?,
        proposedValue: String,
        sourceLineIds: List<String>,
    ): NutritionCorrectionCandidate = NutritionCorrectionCandidate(
        id = "candidate-$fieldPath",
        fieldPath = fieldPath,
        oldValue = oldValue,
        proposedValue = proposedValue,
        sourceLineIds = sourceLineIds,
        confidencePercent = 90,
        reason = "OCR 근거 대조",
        providerId = "test",
        model = "test",
        promptVersion = "test",
    )

    private fun document(vararg texts: String): OcrDocument {
        val pageId = "page-nutrition-test"
        val lines = texts.mapIndexed { index, text ->
            OcrLine(
                id = "line-$index",
                pageId = pageId,
                pageIndex = 0,
                text = text,
                boundingBox = BoundingBox(0, index * 20, 600, index * 20 + 18),
                elements = emptyList(),
                confidence = 0.95f,
                recognitionOrder = index,
            )
        }
        val block = OcrBlock(
            id = "block-0",
            pageId = pageId,
            pageIndex = 0,
            text = texts.joinToString("\n"),
            boundingBox = null,
            lines = lines,
            recognitionOrder = 0,
        )
        return OcrDocument(
            documentId = "ocr-nutrition-test",
            rawText = block.text,
            pages = listOf(OcrPage(pageId, 0, block.text, listOf(block))),
            engine = OcrEngineInfo("synthetic", "1"),
        )
    }
}
