package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Contract
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Payload
import com.pricetrace.receiptscanner.review.CanonicalFieldRegistry
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import com.pricetrace.receiptscanner.review.PurchaseOptionListMutation
import com.pricetrace.receiptscanner.review.ReviewViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StructuredPurchaseOptionsTest {
    private val options = listOf(
        PurchaseOption("option-1", "보통", 1.0, 0L),
        PurchaseOption("option-2", "현미잡곡밥 180g (약275kcal → 순탄수 61g)", 1.0, 0L),
        PurchaseOption("option-3", "리뷰이벤트 계란후라이 1개 추가 (순단백질7g)", 1.0, 0L),
    )
    private val optionPath = "purchase_records[purchase-1].line_items[0].option_items"

    @Test fun v4DecodesThreeOptionsInSourceOrder() {
        val encoded = withOptions().let(YeonsikOcrV4Json::encode)
        val decoded = YeonsikOcrV4Json.decode(encoded, "three-options")
        assertEquals(options, decoded.purchaseRecords.single().lineItems.single().optionItems)
        assertNull(decoded.purchaseRecords.single().lineItems.single().optionText)
        assertEquals(1, decoded.purchaseRecords.single().lineItems.size)
        assertTrue(ReviewViewModel.fromCanonical(decoded).rows.any { row ->
            row.details.filter { it.item.startsWith("옵션 ") }.map { it.value }.firstOrNull() == "보통 ×1 [0 KRW]"
        })
    }

    @Test fun encoderAlwaysEmitsOptionItemsAndRoundTripPreservesReorderedOptions() {
        val envelope = withOptions(options.reversed())
        val encoded = YeonsikOcrV4Json.encode(envelope)
        val decoded = YeonsikOcrV4Json.decode(encoded, "round-trip")
        assertEquals(options.reversed(), decoded.purchaseRecords.single().lineItems.single().optionItems)
        assertEquals(encoded, YeonsikOcrV4Json.encode(decoded))
        assertEquals(JsonArray(emptyList()), lineJson(YeonsikOcrV4Json.encode(legacy()))["option_items"])
    }

    @Test fun missingOptionItemsDecodesToEmptyAndLegacyOptionTextRemainsValid() {
        val legacyJson = replaceLineJson(YeonsikOcrV4Json.encode(legacy())) {
            JsonObject(it.toMutableMap().apply {
                remove("option_items")
                put("option_text", JsonPrimitive("기존 옵션 · 그대로"))
            })
        }
        val decoded = YeonsikOcrV4Json.decode(legacyJson, "legacy-options")
        val line = decoded.purchaseRecords.single().lineItems.single()
        assertTrue(line.optionItems.isEmpty())
        assertEquals("기존 옵션 · 그대로", line.optionText)
        assertTrue(CanonicalEnvelopeValidator.validate(decoded).isEmpty())
        assertEquals(line.optionText, PriceTracePurchaseOptions.flatten(line))
    }

    @Test fun structuredOptionsRequireNullLegacyTextAndUniqueLocalKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            line().copy(optionText = "old", optionItems = options)
        }
        assertThrows(IllegalArgumentException::class.java) {
            line().copy(optionItems = listOf(options[0], options[0]))
        }
        // The same local key can be used under a different parent.
        assertEquals("option-1", line().copy(optionItems = options).optionItems.first().optionKey)
    }

    @Test fun optionKeysAndDescriptionsMustNotBeBlank() {
        assertThrows(IllegalArgumentException::class.java) { PurchaseOption(" ", "설명") }
        assertThrows(IllegalArgumentException::class.java) { PurchaseOption("key", " \n ") }
        val invalid = replaceOptionsJson { JsonArray(listOf(JsonObject(it.first().jsonObject + ("description" to JsonPrimitive(""))))) }
        assertThrows(IllegalStateException::class.java) { YeonsikOcrV4Json.decode(invalid, "blank") }
    }

    @Test fun nullableOptionQuantityIsNotInferredAndMustBePositiveWhenPresent() {
        val source = withOptions(listOf(PurchaseOption("key", "양 불명")))
        val decoded = YeonsikOcrV4Json.decode(YeonsikOcrV4Json.encode(source), "unknown")
        assertNull(decoded.purchaseRecords.single().lineItems.single().optionItems.single().quantity)
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { PurchaseOption("key", "설명", invalid) }
        }
        assertEquals(0.25, PurchaseOption("key", "분량", 0.25).quantity!!, 0.0)
    }

    @Test fun nullableAmountAndObservedZeroAreDistinctAndFractionsAreRejected() {
        val values = listOf(PurchaseOption("unknown", "금액 불명"), PurchaseOption("zero", "0원 표시", amountKrw = 0L))
        val decoded = YeonsikOcrV4Json.decode(YeonsikOcrV4Json.encode(withOptions(values)), "amounts")
            .purchaseRecords.single().lineItems.single().optionItems
        assertNull(decoded[0].amountKrw)
        assertEquals(0L, decoded[1].amountKrw)
        assertThrows(IllegalArgumentException::class.java) { PurchaseOption("key", "설명", amountKrw = -1L) }
        listOf(JsonPrimitive(0.5), JsonPrimitive("0"), JsonPrimitive(9223372036854775808.0)).forEach { invalid ->
            val canonical = replaceOptionsJson { JsonArray(listOf(JsonObject(it.first().jsonObject + ("amount_krw" to invalid)))) }
            assertThrows(IllegalStateException::class.java) { YeonsikOcrV4Json.decode(canonical, "fractional-amount") }
        }
    }

    @Test fun malformedOptionShapesAndAuthorityFieldsFailClosed() {
        listOf(JsonNull, JsonPrimitive("options"), JsonObject(emptyMap())).forEach { invalid ->
            val canonical = replaceLineJson(YeonsikOcrV4Json.encode(withOptions())) { JsonObject(it + ("option_items" to invalid)) }
            assertNotNull(runCatching { YeonsikOcrV4Json.decode(canonical, "bad-options") }.exceptionOrNull())
        }
        val authority = replaceOptionsJson { JsonArray(listOf(JsonObject(it.first().jsonObject + ("authority_uuid" to JsonPrimitive("fake"))))) }
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV4Json.decode(authority, "authority") }
    }

    @Test fun manualAddEditDeleteReorderAndUndoRedoRetainStableKeys() {
        val controller = CanonicalReviewController(legacy())
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("첫 옵션")))
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("둘째 옵션", 0.5, 0L)))
        assertEquals(listOf("option-1", "option-2"), controller.options().map { it.optionKey })
        assertNull(controller.options()[0].quantity)
        assertNull(controller.options()[0].amountKrw)
        assertTrue(controller.updateField("$optionPath[option-1].description", "수정 설명"))
        assertTrue(controller.updateField("$optionPath[option-1].quantity", "1.25"))
        assertTrue(controller.updateField("$optionPath[option-1].amount_krw", "100"))
        assertTrue(controller.updateField("$optionPath[option-1].quantity", null))
        assertTrue(controller.updateField("$optionPath[option-1].amount_krw", null))
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Reorder(listOf("option-2", "option-1"))))
        assertEquals(listOf("option-2", "option-1"), controller.options().map { it.optionKey })
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Delete("option-2")))
        assertTrue(controller.undo())
        assertEquals("option-2", controller.options().first().optionKey)
        assertTrue(controller.redo())
        val restored = CanonicalReviewController(controller.state.value.envelope, controller.state.value.edits)
        assertTrue(restored.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("새 옵션")))
        assertEquals(listOf("option-1", "option-3"), restored.options().map { it.optionKey })
        assertTrue(restored.state.value.edits.all { it.provenanceJson.contains("user_modified") })
    }

    @Test fun invalidEditsDoNotChangeCanonicalOrRevisionAndKeysAreReadOnly() {
        val controller = CanonicalReviewController(withOptions())
        val before = controller.state.value
        listOf("description" to " ", "quantity" to "0", "quantity" to "-1", "amount_krw" to "-1", "amount_krw" to "1.5", "option_key" to "evil").forEach { (field, value) ->
            assertFalse(controller.updateField("$optionPath[option-1].$field", value))
            assertEquals(before.envelope, controller.state.value.envelope)
            assertEquals(before.edits, controller.state.value.edits)
            assertEquals(before.canonicalFingerprint, controller.state.value.canonicalFingerprint)
            assertTrue(controller.state.value.fieldErrors.isNotEmpty())
        }
        assertFalse(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Reorder(listOf("option-1", "option-1", "option-3"))))
        assertFalse(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Delete("missing")))
        assertNull(CanonicalFieldRegistry.descriptor(before.envelope, "$optionPath[option-1].option_key"))
        assertNull(CanonicalFieldRegistry.descriptor(before.envelope, optionPath))
        assertFalse(controller.updateField("$optionPath[option-1].source_attachment_ids", "fake"))
        assertFalse(controller.updateField("source.user_text", "fake"))
        assertFalse(controller.updateField("purchase_records[purchase-1].line_items[0].option_text", "both"))
    }

    @Test fun addingStructuredOptionsNeverSilentlyReplacesLegacyText() {
        val envelope = replaceLine(legacy(), line().copy(optionText = "기존 원문"))
        val controller = CanonicalReviewController(envelope)
        assertFalse(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("추가")))
        assertEquals(envelope, controller.state.value.envelope)
        assertTrue(controller.updateField("purchase_records[purchase-1].line_items[0].option_text", null))
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("추가")))
    }

    @Test fun invalidOptionDraftsRemainBlockedAcrossOtherEditsAndReorder() {
        var controller = CanonicalReviewController(withOptions())
        val quantityPath = "$optionPath[option-1].quantity"
        val amountPath = "$optionPath[option-2].amount_krw"
        assertFalse(controller.updateField(quantityPath, "0"))
        assertFalse(controller.updateField(amountPath, "-1"))
        assertEquals(setOf(quantityPath, amountPath), controller.state.value.fieldErrors.keys)
        controller = CanonicalReviewController(controller.state.value.envelope, controller.state.value.edits, controller.state.value.fieldErrors)
        assertTrue(controller.updateField("$optionPath[option-3].description", "다른 정상 편집"))
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Reorder(options.map { it.optionKey }.reversed())))
        assertEquals(setOf(quantityPath, amountPath), controller.state.value.fieldErrors.keys)
        assertTrue(controller.updateField(quantityPath, null))
        assertEquals(setOf(amountPath), controller.state.value.fieldErrors.keys)
        // Correcting a draft back to the existing observed zero must also resolve its own error.
        val fingerprint = controller.state.value.canonicalFingerprint
        assertTrue(controller.updateField(amountPath, "0"))
        assertTrue(controller.state.value.fieldErrors.isEmpty())
        assertEquals(fingerprint, controller.state.value.canonicalFingerprint)
    }

    @Test fun deletingAnOptionClearsOnlyThatOptionsDraftErrors() {
        val controller = CanonicalReviewController(withOptions())
        val quantityPath = "$optionPath[option-1].quantity"
        assertFalse(controller.updateField(quantityPath, "0"))
        assertFalse(controller.updateField("$optionPath[option-2].description", ""))
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Delete("option-2")))
        assertEquals(setOf(quantityPath), controller.state.value.fieldErrors.keys)
        assertTrue(controller.updateField(quantityPath, "1"))
        assertTrue(controller.state.value.fieldErrors.isEmpty())
    }

    @Test fun localOptionKeysRemainStableAcrossLineAliasesAndSupportNonBlankProducerKeys() {
        val sourceKey = "source]key\n1"
        val controller = CanonicalReviewController(replaceLine(legacy(), line().copy(
            lineKey = "parent", optionItems = listOf(PurchaseOption(sourceKey, "원문")),
        )))
        val path = "purchase_records[purchase-1].line_items[parent].option_items"
        assertTrue(controller.updateField("$path[$sourceKey].description", "수정"))
        assertEquals(sourceKey, controller.options().single().optionKey)
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("추가")))
        assertTrue(controller.mutatePurchaseOptions("purchase-1", "parent", PurchaseOptionListMutation.Delete("option-1")))
        assertTrue(controller.state.value.edits.filter { it.fieldPath.endsWith(".option_items") }.all { it.fieldPath == path })
        val restored = CanonicalReviewController(controller.state.value.envelope, controller.state.value.edits)
        assertTrue(restored.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("다음")))
        assertEquals(listOf(sourceKey, "option-2"), restored.options().map { it.optionKey })
    }

    @Test fun v5OptionsRetainParentLinksSourceEvidenceNutritionAndConsumption() {
        val original = YeonsikOcrV5Json.decode(example("yeonsik-ocr.v5.restaurant-purchase.example.json"), "v5-options")
        val controller = CanonicalReviewController(original)
        val record = original.purchaseRecords.single()
        val parent = record.lineItems.single().lineKey!!
        assertTrue(controller.mutatePurchaseOptions(record.clientKey, parent, PurchaseOptionListMutation.Add(options[1].description)))
        assertTrue(controller.mutatePurchaseOptions(record.clientKey, parent, PurchaseOptionListMutation.Add(options[2].description)))
        assertTrue(controller.updateField("purchase_records[${record.clientKey}].line_items[$parent].option_items[option-1].description", "약900kcal 단백질100g"))
        assertTrue(controller.mutatePurchaseOptions(record.clientKey, parent, PurchaseOptionListMutation.Reorder(listOf("option-2", "option-1"))))
        assertTrue(controller.mutatePurchaseOptions(record.clientKey, parent, PurchaseOptionListMutation.Delete("option-1")))
        val edited = controller.state.value.envelope
        assertEquals(original.source, edited.source)
        assertEquals(original.purchaseRecords.single().evidence, edited.purchaseRecords.single().evidence)
        assertEquals(original.nutrition, edited.nutrition)
        assertEquals(original.consumption, edited.consumption)
        assertTrue(edited.consumption.isEmpty())
        assertEquals(original.purchaseNutritionLinks, edited.purchaseNutritionLinks)
        assertTrue(CanonicalEnvelopeValidator.validate(edited).isEmpty())
        val root = Json.parseToJsonElement(YeonsikOcrV5Json.encodeDraft(edited)).jsonObject
        assertEquals(setOf("purchase_record_client_key", "purchase_line_key", "nutrition_client_key"), root.getValue("links").jsonArray.single().jsonObject.keys)
        assertFalse(root.toString().contains("authority_uuid"))
        assertFalse(root.getValue("review").jsonObject.containsKey("user_verified"))
        val roundTrip = YeonsikOcrV5Json.decode(root.toString(), "v5-options")
        assertEquals(edited.purchaseRecords.single().lineItems.single().optionItems, roundTrip.purchaseRecords.single().lineItems.single().optionItems)
        val threeOptionExample = YeonsikOcrV5Json.decode(example("yeonsik-ocr.v5.restaurant-purchase-options.example.json"), "v5-three-options")
        assertEquals(options, threeOptionExample.purchaseRecords.single().lineItems.single().optionItems)
    }

    @Test fun priceTraceFlattensInOrderWithoutInventingNullableValues() {
        val record = withOptions().purchaseRecords.single()
        val payload = PriceTracePurchaseObservationV4Payload(PriceTracePurchaseObservationV4Contract(), record, VerificationBasis.MANUAL_CANONICAL_REVIEW).toJson()
        val expected = "보통 ×1 [0 KRW] | 현미잡곡밥 180g (약275kcal → 순탄수 61g) ×1 [0 KRW] | 리뷰이벤트 계란후라이 1개 추가 (순단백질7g) ×1 [0 KRW]"
        assertEquals(expected, payload.getValue("items").jsonArray.single().jsonObject.getValue("option_text").jsonPrimitive.content)
        assertFalse(payload.getValue("items").jsonArray.single().jsonObject.containsKey("option_items"))
        assertEquals("불명 | 반 개 ×0.5 [0 KRW]", PriceTracePurchaseOptions.flatten(line().copy(optionItems = listOf(
            PurchaseOption("unknown", "불명"), PurchaseOption("half", "반 개", 0.5, 0L),
        ))))
        assertEquals(options, record.lineItems.single().optionItems)
    }

    @Test fun longOptionFactsRemainCanonicalAndBlockOnlyPriceTrace() {
        val longDescription = "긴 옵션".repeat(200)
        val envelope = withOptions(listOf(PurchaseOption("long", longDescription, 1.0, 0L)))
        val record = envelope.purchaseRecords.single()
        assertTrue(CanonicalEnvelopeValidator.validate(envelope).isEmpty())
        assertEquals(longDescription, YeonsikOcrV4Json.decode(YeonsikOcrV4Json.encode(envelope), "long").purchaseRecords.single().lineItems.single().optionItems.single().description)
        assertTrue(PriceTracePurchaseOptions.flatten(record.lineItems.single())!!.length > 500)
        assertEquals(PriceTraceV4SubmissionReason.OPTION_TEXT_LENGTH_REQUIRED, record.priceTraceSubmissionReasonCode)
        assertFalse(record.priceTraceSubmissionEligible)
        assertTrue(record.cashOsTransactionEligible)
        val plan = CanonicalProjectionPlanner.plan(envelope)
        assertFalse(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in plan.eligible)
        assertTrue(IngestionProjection.CASHOS_TRANSACTION in plan.eligible)
        assertThrows(IllegalArgumentException::class.java) {
            PriceTracePurchaseObservationV4Payload(PriceTracePurchaseObservationV4Contract(), record, VerificationBasis.MANUAL_CANONICAL_REVIEW)
        }
        val v5 = YeonsikOcrV5Json.decode(example("yeonsik-ocr.v5.restaurant-purchase.example.json"), "v5-long")
        val v5Long = replaceLine(v5, v5.purchaseRecords.single().lineItems.single().copy(optionItems = listOf(PurchaseOption("long", longDescription))))
        assertTrue(IngestionProjection.FITNESS_NUTRITION in CanonicalProjectionPlanner.plan(v5Long).eligible)
        assertEquals(v5.nutrition, v5Long.nutrition)
    }

    @Test fun lengthBoundaryUsesReceiverCharacterCountAndLegacyTextIsNeverTruncated() {
        assertNull(PriceTracePurchaseOptions.incompatibilityReason(line().copy(optionText = "a".repeat(500))))
        assertEquals(PriceTraceV4SubmissionReason.OPTION_TEXT_LENGTH_REQUIRED, PriceTracePurchaseOptions.incompatibilityReason(line().copy(optionText = "a".repeat(501))))
        val emoji = "😀".repeat(500)
        assertNull(PriceTracePurchaseOptions.incompatibilityReason(line().copy(optionText = emoji)))
        assertEquals(emoji, PriceTracePurchaseOptions.flatten(line().copy(optionText = emoji)))
        assertEquals(PriceTraceV4SubmissionReason.OPTION_TEXT_UNREPRESENTABLE, PriceTracePurchaseOptions.incompatibilityReason(line().copy(optionText = "a\u0000b")))
        assertEquals(PriceTraceV4SubmissionReason.OPTION_TEXT_UNREPRESENTABLE, PriceTracePurchaseOptions.incompatibilityReason(line().copy(optionText = "a\uD800b")))
        assertEquals(PriceTraceV4SubmissionReason.OPTION_TEXT_UNREPRESENTABLE, PriceTracePurchaseOptions.incompatibilityReason(line().copy(optionItems = listOf(PurchaseOption("invalid-unicode", "\uDC00")))))
    }

    @Test fun optionChangesAndReorderRotateRevisionFingerprintsIdempotencyAndCheckpoint() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val requests = mutableListOf<ProjectionRequest>()
        val submitter = object : IngestionProjectionSubmitter {
            override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
                requests += request
                return ProjectionSubmission.Success("remote-${requests.size}", "{\"checkpoint\":\"old\"}")
            }
        }
        val selected = setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, IngestionProjection.CASHOS_TRANSACTION)
        val useCase = CanonicalIngestionUseCase(store, selected.associateWith { submitter })
        val imported = useCase.importJson(YeonsikOcrV4Json.encode(withOptions()), "option-revision", "option-session") as CanonicalImportResult.Success
        var confirmed = useCase.confirm("option-session", imported.envelope, verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW)
        assertFalse(confirmed.result is IngestionStartResult.Failure)
        useCase.submitSelected("option-session", confirmed.envelope, selected)
        val mutations: List<(CanonicalReviewController) -> Boolean> = listOf(
            { it.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Add("추가 옵션")) },
            { it.updateField("$optionPath[option-4].description", "의미 변경") },
            { it.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Delete("option-4")) },
            { it.mutatePurchaseOptions("purchase-1", "0", PurchaseOptionListMutation.Reorder(options.map { option -> option.optionKey }.reversed())) },
        )
        mutations.forEach { mutate ->
            val previous = useCase.session("option-session")!!
            val previousRequests = requests.takeLast(selected.size).associateBy { it.projection }
            val controller = CanonicalReviewController(confirmed.envelope)
            assertTrue(mutate(controller))
            val revised = (useCase.reviseCanonicalDraft("option-session", controller.state.value.envelope) as IngestionStartResult.Success).session
            assertEquals(previous.revisionSeq + 1, revised.revisionSeq)
            assertNotEquals(previous.canonicalFingerprint, revised.canonicalFingerprint)
            assertNull(revised.verifiedCanonicalFingerprint)
            selected.forEach { projection ->
                val before = previous.projections.single { it.projection == projection }
                val after = revised.projections.single { it.projection == projection }
                assertNotEquals(before.projectionPayloadFingerprint, after.projectionPayloadFingerprint)
                assertEquals(before.projectionRevisionSeq + 1, after.projectionRevisionSeq)
                assertNull(after.idempotencyKey)
                assertNull(after.metadataJson)
                assertNull(after.remoteId)
                assertEquals(ProjectionStatus.PENDING, after.status)
            }
            confirmed = useCase.confirm("option-session", controller.state.value.envelope, verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW)
            useCase.submitSelected("option-session", confirmed.envelope, selected)
            selected.forEach { projection ->
                val before = previousRequests.getValue(projection)
                val after = requests.last { it.projection == projection }
                assertNotEquals(before.idempotencyKey, after.idempotencyKey)
                assertNull(after.previousMetadataJson)
            }
        }
    }

    private fun example(name: String) = sequenceOf(File("examples", name), File("../examples", name)).first(File::isFile).readText()
    private fun legacy(): YeonsikOcrEnvelope {
        val source = YeonsikOcrV4Json.decode(example("yeonsik-ocr.v4.purchase.example.json"), "options-test")
        val record = source.purchaseRecords.single()
        return source.copy(productCandidates = emptyList(), purchaseRecords = listOf(record.copy(
            purchaseKind = PurchaseKind.RESTAURANT,
            lineItems = record.lineItems.map { it.copy(productClientKey = null) },
        )))
    }
    private fun line() = legacy().purchaseRecords.single().lineItems.single()
    private fun withOptions(values: List<PurchaseOption> = options) = replaceLine(legacy(), line().copy(optionText = null, optionItems = values))
    private fun replaceLine(envelope: YeonsikOcrEnvelope, line: PurchaseRecordLine) = envelope.copy(purchaseRecords = listOf(envelope.purchaseRecords.single().copy(lineItems = listOf(line))))
    private fun CanonicalReviewController.options() = state.value.envelope.purchaseRecords.single().lineItems.single().optionItems
    private fun lineJson(canonical: String) = Json.parseToJsonElement(canonical).jsonObject.getValue("purchase_records").jsonArray.single().jsonObject.getValue("line_items").jsonArray.single().jsonObject
    private fun replaceOptionsJson(transform: (JsonArray) -> JsonArray) = replaceLineJson(YeonsikOcrV4Json.encode(withOptions())) { JsonObject(it + ("option_items" to transform(it.getValue("option_items").jsonArray))) }
    private fun replaceLineJson(canonical: String, transform: (JsonObject) -> JsonObject): String {
        val root = Json.parseToJsonElement(canonical).jsonObject
        val record = root.getValue("purchase_records").jsonArray.single().jsonObject
        return JsonObject(root + ("purchase_records" to JsonArray(listOf(JsonObject(record + ("line_items" to JsonArray(listOf(transform(record.getValue("line_items").jsonArray.single().jsonObject))))))))).toString()
    }
}
