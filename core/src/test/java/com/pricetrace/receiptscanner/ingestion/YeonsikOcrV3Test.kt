package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.nutrition.NutritionField
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class YeonsikOcrV3Test {
    @Test
    fun `v3 strict decode preserves observable sub brand facts on round trip`() {
        val source = retailJson()
        val envelope = YeonsikOcrV3Json.decode(source, "v3-retail")

        assertEquals(YEONSIK_OCR_V3_SCHEMA, envelope.schemaVersion)
        assertEquals("Brand Sub", envelope.productCandidates.single().subBrandName)
        assertEquals(
            setOf(
                "schema_version", "mode", "source", "merchant_candidate", "receipt",
                "product_candidates", "price_observations", "nutrition", "consumption",
                "classification_hints", "links", "projection_targets", "review",
            ),
            parse(YeonsikOcrV3Json.encode(envelope)).keys,
        )

        val roundTripped = YeonsikOcrV3Json.decode(
            YeonsikOcrV3Json.encode(envelope),
            "v3-retail-round-trip",
        )
        assertEquals("Brand Sub", roundTripped.productCandidates.single().subBrand)
        assertEquals(envelope.priceObservations.single().net, roundTripped.priceObservations.single().net)
    }

    @Test
    fun `v3 strict decoder rejects unknown top level fields`() {
        val root = parse(retailJson())
        val invalid = JsonObject(root.toMutableMap().apply {
            put("unexpected", JsonPrimitive(true))
        })

        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV3Json.decode(encode(invalid), "v3-strict")
        }
    }

    @Test
    fun `v3 supports standalone restaurant price and linked menu nutrition`() {
        val envelope = YeonsikOcrV3Json.decode(restaurantJson(), "v3-restaurant")
        val price = envelope.priceObservations.single()
        val menu = envelope.nutrition.single() as IngestionNutrition.RestaurantMenuEstimate

        assertEquals(StandalonePriceObservationKind.RESTAURANT_PURCHASE, price.kind)
        assertEquals("Noodles", price.itemName)
        assertEquals("price-1", menu.priceObservationClientKey)
        assertEquals(null, envelope.receipt)
        assertTrue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in CanonicalProjectionPlanner.plan(envelope).eligible)
        assertTrue(IngestionProjection.FITNESS_NUTRITION in CanonicalProjectionPlanner.plan(envelope).eligible)
        assertFalse(IngestionProjection.CASHOS_RECEIPT in CanonicalProjectionPlanner.plan(envelope).eligible)
    }

    @Test
    fun `manual canonical review can confirm without image while source evidence cannot`() {
        val envelope = YeonsikOcrV3Json.decode(retailJson(), "v3-no-image")
        val artifacts = setOf(
            IngestionArtifactKeys.productCandidate("product-1"),
            IngestionArtifactKeys.priceObservation("price-1"),
        )

        assertFalse(IngestionEvidenceGate.evaluate(envelope, emptyList(), artifactKeys = artifacts).isAllowed)
        assertFalse(
            IngestionEvidenceGate.evaluate(
                envelope,
                emptyList(),
                artifactKeys = artifacts,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            ).isAllowed,
        )
        assertTrue(
            IngestionEvidenceGate.evaluate(
                envelope,
                emptyList(),
                artifactKeys = artifacts,
                verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
                explicitUserConfirmation = true,
            ).isAllowed,
        )
    }

    @Test
    fun `restaurant menu source evidence uses the menu image gate`() {
        val envelope = YeonsikOcrV3Json.decode(restaurantJson(), "v3-menu-evidence")
        val artifacts = setOf(
            IngestionArtifactKeys.priceObservation("price-1"),
            IngestionArtifactKeys.nutrition("nutrition-1"),
        )

        assertTrue(
            IngestionEvidenceGate.evaluate(
                envelope,
                listOf(LocalEvidence("menu-photo-1", SourceAttachmentType.MENU_PHOTO, true)),
                artifactKeys = artifacts,
            ).isAllowed,
        )
    }

    @Test
    fun `manual confirmation still runs v3 domain validation`() {
        val valid = YeonsikOcrV3Json.decode(retailJson(), "v3-domain-validation")
        val invalid = valid.copy(merchantCandidate = null)

        val result = IngestionEvidenceGate.evaluate(
            invalid,
            emptyList(),
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            explicitUserConfirmation = true,
        )
        assertFalse(result.isAllowed)
        assertTrue(result.blockingIssues.single().startsWith("canonical_domain_invalid:"))
    }

    @Test
    fun `external review authority fields are parsed but cannot self authorize`() {
        val envelope = YeonsikOcrV3Json.decode(retailJson(), "v3-external-review")
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, envelope.review.status)
        assertEquals(VerificationBasis.SOURCE_EVIDENCE, envelope.review.verificationBasis)
        assertTrue(envelope.review.blockingIssues.isNotEmpty())

        val persisted = YeonsikOcrV3Json.decode(
            retailJson(),
            "v3-persisted-review",
            preservePersistedVerification = true,
        )
        assertEquals(IngestionReviewStatus.READY, persisted.review.status)
        assertEquals(VerificationBasis.MANUAL_CANONICAL_REVIEW, persisted.review.verificationBasis)
    }

    @Test
    fun `v3 incomplete consumption does not expose meal but complete consumption does`() {
        val incomplete = YeonsikOcrV3Json.decode(restaurantJson(withCompleteConsumption = false), "v3-incomplete")
        val complete = YeonsikOcrV3Json.decode(restaurantJson(withCompleteConsumption = true), "v3-complete")

        assertFalse(IngestionProjection.FITNESS_MEAL in CanonicalProjectionPlanner.plan(incomplete).eligible)
        assertTrue(IngestionProjection.FITNESS_MEAL in CanonicalProjectionPlanner.plan(complete).eligible)
    }

    @Test
    fun `selected standalone price submission is PriceTrace only and keeps retry idempotent`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val price = RecordingSubmitter(
            mutableListOf(
                ProjectionSubmission.Failure("temporary", retryable = true),
                ProjectionSubmission.Success("observation-1"),
            ),
        )
        val product = RecordingSubmitter()
        val useCase = CanonicalIngestionUseCase(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to price,
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to product,
            ),
        )
        val imported = useCase.importJson(
            retailJson(),
            localDocumentId = "v3-selected-price",
            ingestionId = "v3-selected-price-ingestion",
        ) as CanonicalImportResult.Success
        val plan = useCase.plan(imported.envelope)
        assertTrue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in plan.eligible)
        assertFalse(IngestionProjection.CASHOS_RECEIPT in plan.eligible)

        val confirmation = useCase.confirm(
            ingestionId = imported.session.ingestionId,
            envelope = imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(confirmation.result is IngestionStartResult.Success)

        val failed = useCase.submitSelected(
            imported.session.ingestionId,
            confirmation.envelope,
            setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        ).single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }
        assertEquals(ProjectionStatus.FAILED, failed.status)

        val uploaded = useCase.retrySelected(
            imported.session.ingestionId,
            confirmation.envelope,
            setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        ).single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }
        assertEquals(ProjectionStatus.UPLOADED, uploaded.status)
        assertEquals(2, price.requests.size)
        assertEquals(price.requests[0].idempotencyKey, price.requests[1].idempotencyKey)
        assertTrue(store.get(imported.session.ingestionId)!!.projections.none {
            it.projection == IngestionProjection.CASHOS_RECEIPT && it.status == ProjectionStatus.UPLOADED
        })
        assertTrue(product.requests.isEmpty())
    }

    @Test
    fun `selected fitness nutrition submission does not submit standalone price or CashOS`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val fitness = RecordingSubmitter()
        val price = RecordingSubmitter()
        val useCase = CanonicalIngestionUseCase(
            store = store,
            submitters = mapOf(
                IngestionProjection.FITNESS_NUTRITION to fitness,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to price,
            ),
        )
        val imported = useCase.importJson(
            restaurantJson(),
            localDocumentId = "v3-selected-fitness",
            ingestionId = "v3-selected-fitness-ingestion",
        ) as CanonicalImportResult.Success
        val confirmation = useCase.confirm(
            imported.session.ingestionId,
            imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(confirmation.result is IngestionStartResult.Success)

        val states = useCase.submitSelected(
            imported.session.ingestionId,
            confirmation.envelope,
            setOf(IngestionProjection.FITNESS_NUTRITION),
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            states.single { it.projection == IngestionProjection.FITNESS_NUTRITION }.status,
        )
        assertTrue(fitness.requests.size == 1)
        assertTrue(price.requests.isEmpty())
        assertTrue(states.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.status == ProjectionStatus.PENDING)
        assertTrue(states.single { it.projection == IngestionProjection.CASHOS_RECEIPT }.status == ProjectionStatus.DISABLED)
    }

    @Test
    fun `selected meal submits its Fitness nutrition dependency first`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val order = mutableListOf<IngestionProjection>()
        val submitters = listOf(
            IngestionProjection.FITNESS_NUTRITION to RecordingSubmitter(onSubmit = { order += it.projection }),
            IngestionProjection.FITNESS_MEAL to RecordingSubmitter(onSubmit = { order += it.projection }),
        ).toMap()
        val useCase = CanonicalIngestionUseCase(store = store, submitters = submitters)
        val imported = useCase.importJson(
            restaurantJson(withCompleteConsumption = true),
            localDocumentId = "v3-selected-meal",
            ingestionId = "v3-selected-meal-ingestion",
        ) as CanonicalImportResult.Success
        val confirmation = useCase.confirm(
            imported.session.ingestionId,
            imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(confirmation.result is IngestionStartResult.Success)

        val plan = useCase.plan(confirmation.envelope)
        assertEquals(
            setOf(IngestionProjection.FITNESS_NUTRITION),
            plan.dependencies[IngestionProjection.FITNESS_MEAL],
        )
        useCase.submitSelected(
            imported.session.ingestionId,
            confirmation.envelope,
            setOf(IngestionProjection.FITNESS_MEAL),
        )
        assertEquals(
            listOf(IngestionProjection.FITNESS_NUTRITION, IngestionProjection.FITNESS_MEAL),
            order,
        )
    }

    @Test
    fun `v1 and v2 remain importable through their separate codecs`() {
        val importer = ExternalJsonImporter()
        listOf(
            "yeonsik-ocr.packaged-product.example.json" to YEONSIK_OCR_SCHEMA,
            "yeonsik-ocr.v2.packaged-product.example.json" to YEONSIK_OCR_V2_SCHEMA,
        ).forEach { (name, schema) ->
            val value = java.io.File("examples", name).takeIf(java.io.File::isFile)
                ?.readText()
                ?: java.io.File("../examples", name).readText()
            val result = importer.import(value, "compat-$name") as ExternalJsonImportOutcome.Success
            val envelope = (result.result.draft as CanonicalDraft.Envelope).value
            assertEquals(schema, envelope.schemaVersion)
            assertNotNull(YeonsikOcrEnvelopeCodec.decode(value, "compat-codec-$name"))
        }
    }

    private class RecordingSubmitter(
        private val responses: MutableList<ProjectionSubmission> = mutableListOf(),
        private val onSubmit: (ProjectionRequest) -> Unit = {},
    ) : IngestionProjectionSubmitter {
        val requests = mutableListOf<ProjectionRequest>()

        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
            requests += request
            onSubmit(request)
            return responses.removeFirstOrNull() ?: ProjectionSubmission.Success(
                remoteId = "remote-${request.projection.wireValue}",
            )
        }
    }

    private fun retailJson(): String = encode(buildJsonObject {
        put("schema_version", JsonPrimitive(YEONSIK_OCR_V3_SCHEMA))
        put("mode", JsonPrimitive("packaged_product"))
        put("source", sourceJson("product-photo-1", "product_photo"))
        put("merchant_candidate", merchantJson("retail"))
        put("receipt", JsonNull)
        put("product_candidates", JsonArray(listOf(buildJsonObject {
            put("client_key", JsonPrimitive("product-1"))
            put("product_name", JsonPrimitive("Demo Drink"))
            put("brand_name", JsonPrimitive("Brand"))
            put("sub_brand_name", JsonPrimitive("Brand Sub"))
            put("manufacturer_name", JsonPrimitive("Demo Foods"))
            put("variant_name", JsonPrimitive("Zero"))
            put("specification_text", JsonPrimitive("500 ml"))
            put("content_amount", JsonPrimitive(500.0))
            put("content_unit", JsonPrimitive("ml"))
            put("package_count", JsonPrimitive(1.0))
            put("barcodes", JsonArray(listOf(buildJsonObject {
                put("scheme", JsonPrimitive("ean13"))
                put("value", JsonPrimitive("8801234567890"))
            })))
            put("source_attachment_ids", JsonArray(listOf(JsonPrimitive("product-photo-1"))))
            put("confidence", JsonPrimitive(0.93))
        })))
        put("price_observations", JsonArray(listOf(retailPriceJson())))
        put("nutrition", JsonArray(emptyList<JsonElement>()))
        put("consumption", JsonArray(emptyList<JsonElement>()))
        put("classification_hints", hintsJson())
        put("links", JsonArray(emptyList<JsonElement>()))
        put("projection_targets", JsonArray(listOf(JsonPrimitive("cashos_receipt"))))
        put("review", reviewJson())
    })

    private fun restaurantJson(withCompleteConsumption: Boolean = false): String = encode(buildJsonObject {
        put("schema_version", JsonPrimitive(YEONSIK_OCR_V3_SCHEMA))
        put("mode", JsonPrimitive("restaurant"))
        put("source", sourceJson("menu-photo-1", "menu_photo"))
        put("merchant_candidate", merchantJson("food_service"))
        put("receipt", JsonNull)
        put("product_candidates", JsonArray(emptyList<JsonElement>()))
        put("price_observations", JsonArray(listOf(restaurantPriceJson())))
        put("nutrition", JsonArray(listOf(buildJsonObject {
            put("client_key", JsonPrimitive("nutrition-1"))
            put("kind", JsonPrimitive("restaurant_menu_estimate"))
            put("line_id", JsonNull)
            put("menu_name", JsonPrimitive("Noodles"))
            put("component_role", JsonNull)
            put("payload", JsonNull)
            put("estimate", estimateJson())
            put("price_observation_client_key", JsonPrimitive("price-1"))
            put("product_label_hierarchy", JsonArray(emptyList<JsonElement>()))
        })))
        put("consumption", if (!withCompleteConsumption) {
            JsonArray(emptyList())
        } else {
            JsonArray(listOf(buildJsonObject {
                put("client_key", JsonPrimitive("consumption-1"))
                put("consumed_at", JsonPrimitive("2026-09-07T12:00:00+09:00"))
                put("status", JsonPrimitive("unverified"))
                put("items", JsonArray(listOf(buildJsonObject {
                    put("nutrition_client_key", JsonPrimitive("nutrition-1"))
                    put("amount", JsonPrimitive(1.0))
                    put("unit", JsonPrimitive("serving"))
                    put("confidence", JsonPrimitive(0.9))
                    put("amount_status", JsonPrimitive("estimated"))
                })))
            }))
        })
        put("classification_hints", hintsJson())
        put("links", JsonArray(emptyList<JsonElement>()))
        put("projection_targets", JsonArray(listOf(JsonPrimitive("cashos_receipt"))))
        put("review", reviewJson())
    })

    private fun retailPriceJson() = buildJsonObject {
        put("client_key", JsonPrimitive("price-1"))
        put("kind", JsonPrimitive("retail_purchase"))
        put("product_client_key", JsonPrimitive("product-1"))
        put("item_name", JsonNull)
        put("observed_on", JsonPrimitive("2026-09-07"))
        put("observed_at", JsonNull)
        put("quantity", JsonPrimitive(1.0))
        put("unit_price", JsonPrimitive(1300))
        put("gross", JsonPrimitive(1300))
        put("discount", JsonPrimitive(0))
        put("net", JsonPrimitive(1300))
        put("evidence", JsonArray(listOf(JsonPrimitive("product-photo-1"))))
        put("confidence", JsonPrimitive(0.9))
    }

    private fun restaurantPriceJson() = buildJsonObject {
        put("client_key", JsonPrimitive("price-1"))
        put("kind", JsonPrimitive("restaurant_purchase"))
        put("product_client_key", JsonNull)
        put("item_name", JsonPrimitive("Noodles"))
        put("observed_on", JsonPrimitive("2026-09-07"))
        put("observed_at", JsonNull)
        put("quantity", JsonPrimitive(1.0))
        put("unit_price", JsonPrimitive(10000))
        put("gross", JsonPrimitive(10000))
        put("discount", JsonPrimitive(0))
        put("net", JsonPrimitive(10000))
        put("evidence", JsonArray(listOf(JsonPrimitive("menu-photo-1"))))
        put("confidence", JsonPrimitive(0.9))
    }

    private fun estimateJson() = buildJsonObject {
        put("estimated", JsonPrimitive(true))
        put("confidence", JsonPrimitive(0.8))
        put("nutrients", JsonObject(NutritionField.entries.associate { field ->
            field.wireKey to JsonPrimitive(100.0)
        }))
        put("ranges", JsonObject(emptyMap()))
        put("provenance", JsonObject(NutritionField.requiredFields.associate { field ->
            field.wireKey to buildJsonObject {
                put("value_status", JsonPrimitive("estimated"))
                put("source_type", JsonPrimitive("menu_reference"))
                put("evidence_refs", JsonArray(listOf(JsonPrimitive("menu-photo-1/${field.wireKey}"))))
            }
        }))
    }

    private fun merchantJson(kind: String) = buildJsonObject {
        put("name", JsonPrimitive(if (kind == "retail") "Demo Mart" else "Demo Restaurant"))
        put("business_kind", JsonPrimitive(kind))
        put("branch_name", JsonPrimitive("Main"))
        put("address", JsonNull)
        put("phone", JsonNull)
        put("business_registration_number", JsonNull)
        put("source_attachment_ids", JsonArray(listOf(JsonPrimitive(if (kind == "retail") "product-photo-1" else "menu-photo-1"))))
        put("source_namespace", JsonPrimitive("demo"))
        put("source_location_code", JsonPrimitive("main"))
    }

    private fun sourceJson(id: String, type: String) = buildJsonObject {
        put("producer", JsonPrimitive("chatgpt"))
        put("source_files", JsonArray(listOf(buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            put("label", JsonNull)
        })))
        put("user_text", JsonNull)
    }

    private fun hintsJson() = buildJsonObject {
        put("cashos", buildJsonObject {
            put("category_hint", JsonNull)
            put("institution_hint", JsonNull)
            put("payment_method_hint", JsonNull)
        })
    }

    private fun reviewJson() = buildJsonObject {
        put("status", JsonPrimitive("ready"))
        put("blocking_issues", JsonArray(emptyList<JsonElement>()))
        put("warnings", JsonArray(emptyList<JsonElement>()))
        put("verification_basis", JsonPrimitive("MANUAL_CANONICAL_REVIEW"))
        put("user_verified", JsonPrimitive(true))
    }

    private fun parse(value: String): JsonObject = json.parseToJsonElement(value).jsonObject

    private fun encode(value: JsonElement): String = json.encodeToString(JsonElement.serializer(), value)

    private companion object {
        val json = Json { explicitNulls = true; ignoreUnknownKeys = false; prettyPrint = true }
    }
}
