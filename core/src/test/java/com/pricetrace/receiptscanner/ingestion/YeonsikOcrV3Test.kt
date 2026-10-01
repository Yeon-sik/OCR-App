package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.importer.CanonicalDraft
import com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome
import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.review.CanonicalReviewController
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
import kotlinx.serialization.json.jsonArray
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
        assertEquals("product_photo", envelope.productCandidates.single().evidence.first().sourceType)
        assertEquals(
            listOf("product-photo-1"),
            envelope.productCandidates.single().effectiveSourceAttachmentIds,
        )
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
        assertEquals(
            envelope.priceObservations.single().netAmountMinor,
            roundTripped.priceObservations.single().netAmountMinor,
        )
        val priceWire = parse(YeonsikOcrV3Json.encode(envelope))
            .getValue("price_observations").jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "client_key", "kind", "product_client_key", "item_name", "observed_on", "observed_at",
                "currency", "quantity", "unit_price_amount_minor", "gross_amount_minor",
                "discount_amount_minor", "net_amount_minor", "source_attachment_ids", "evidence", "confidence",
            ),
            priceWire.keys,
        )
        assertEquals("KRW", priceWire.getValue("currency").jsonPrimitive.content)
        assertEquals("each", priceWire.getValue("quantity").jsonObject.getValue("unit").jsonPrimitive.content)
        assertFalse(priceWire.containsKey("unit_price"))
        assertFalse(priceWire.containsKey("gross"))
        assertFalse(priceWire.containsKey("discount"))
        assertFalse(priceWire.containsKey("net"))
        assertEquals(
            setOf("source_type", "source_attachment_ids", "field", "observed_value"),
            priceWire.getValue("evidence").jsonArray.single().jsonObject.keys,
        )
        assertEquals(
            listOf("product-photo-1"),
            priceWire.getValue("evidence").jsonArray.single().jsonObject
                .getValue("source_attachment_ids").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(null, roundTripped.productCandidates.single().merchantSku)
        val encodedReview = parse(YeonsikOcrV3Json.encode(envelope))["review"]!!.jsonObject
        assertEquals(setOf("status", "blocking_issues", "warnings"), encodedReview.keys)
    }

    @Test
    fun `v3 strict decode accepts text-only retail evidence without attachments`() {
        val envelope = YeonsikOcrV3Json.decode(textOnlyRetailJson(), "v3-text-only")
        val candidate = envelope.productCandidates.single()
        val evidence = candidate.evidence

        assertTrue(envelope.source.sourceFiles.isEmpty())
        assertTrue(candidate.sourceAttachmentIds.isEmpty())
        assertEquals("user_statement", evidence.first().sourceType)
        assertEquals("user-statement:purchase-20260910-1", evidence.first().sourceRef)
        assertTrue(evidence.all { it.sourceAttachmentIds.isEmpty() })
        val price = envelope.priceObservations.single()
        assertEquals("KRW", price.currency)
        assertEquals(1.0, price.quantity?.value)
        assertEquals("each", price.quantity?.unit)
        assertEquals(null, price.unitPriceAmountMinor)
        assertEquals(null, price.grossAmountMinor)
        assertEquals(null, price.discountAmountMinor)
        assertEquals(2900L, price.netAmountMinor)
        assertTrue(price.sourceAttachmentIds.isEmpty())
        assertEquals("user_statement", price.evidence.single().sourceType)
        assertTrue(price.evidence.single().sourceAttachmentIds.isEmpty())
        assertEquals("net_amount_minor", price.evidence.single().field)
        assertEquals("2900", price.evidence.single().observedValue)

        val candidateWire = parse(YeonsikOcrV3Json.encode(envelope))
            .getValue("product_candidates").jsonArray.single().jsonObject
        assertTrue(candidateWire.getValue("source_attachment_ids").jsonArray.isEmpty())
        val evidenceWire = candidateWire.getValue("evidence").jsonArray
        assertEquals(2, evidenceWire.size)
        assertTrue(evidenceWire.all { it.jsonObject.getValue("source_type").jsonPrimitive.content == "user_statement" })
        assertTrue(evidenceWire.all { it.jsonObject.getValue("source_ref").jsonPrimitive.content == "user-statement:purchase-20260910-1" })
        assertTrue(evidenceWire.all { it.jsonObject.getValue("content_hash") == JsonNull })
        val priceEvidenceWire = parse(YeonsikOcrV3Json.encode(envelope))
            .getValue("price_observations").jsonArray.single().jsonObject
            .getValue("evidence").jsonArray.single().jsonObject
        assertEquals(
            setOf("source_type", "source_attachment_ids", "field", "observed_value"),
            priceEvidenceWire.keys,
        )
        assertTrue(priceEvidenceWire.getValue("source_attachment_ids").jsonArray.isEmpty())
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, envelope.review.status)

        val persisted = YeonsikOcrV3Json.decode(
            textOnlyRetailJson(),
            "v3-text-only-persisted",
            preservePersistedVerification = true,
        )
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, persisted.review.status)
        val producerReview = parse(textOnlyRetailJson()).getValue("review").jsonObject
        assertEquals(setOf("status", "blocking_issues", "warnings"), producerReview.keys)
    }

    @Test
    fun `v3 amount validation remains nullable and checks only complete equations`() {
        val textOnly = YeonsikOcrV3Json.decode(textOnlyRetailJson(), "v3-nullable-amounts")
        assertEquals(null, textOnly.priceObservations.single().unitPriceAmountMinor)
        assertEquals(null, textOnly.priceObservations.single().grossAmountMinor)
        assertEquals(null, textOnly.priceObservations.single().discountAmountMinor)
        assertEquals(2900L, textOnly.priceObservations.single().netAmountMinor)

        val root = parse(retailJson())
        val price = root.getValue("price_observations").jsonArray.single().jsonObject
        val mismatched = JsonObject(price.toMutableMap().apply {
            put("unit_price_amount_minor", JsonPrimitive(1200))
        })
        val invalid = JsonObject(root.toMutableMap().apply {
            put("price_observations", JsonArray(listOf(mismatched)))
        })
        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV3Json.decode(encode(invalid), "v3-amount-mismatch")
        }

        val discountMismatch = JsonObject(price.toMutableMap().apply {
            put("discount_amount_minor", JsonPrimitive(100))
        })
        val invalidDiscount = JsonObject(root.toMutableMap().apply {
            put("price_observations", JsonArray(listOf(discountMismatch)))
        })
        assertThrows(IllegalArgumentException::class.java) {
            YeonsikOcrV3Json.decode(encode(invalidDiscount), "v3-discount-mismatch")
        }
    }

    @Test
    fun `net-null standalone draft is not PriceTrace eligible or submitted`() = runBlocking {
        val root = parse(retailJson())
        val price = root.getValue("price_observations").jsonArray.single().jsonObject
        val draft = JsonObject(price.toMutableMap().apply { put("net_amount_minor", JsonNull) })
        val input = JsonObject(root.toMutableMap().apply {
            put("price_observations", JsonArray(listOf(draft)))
        })
        val submitter = RecordingSubmitter()
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION to submitter),
        )
        val imported = useCase.importJson(
            encode(input),
            localDocumentId = "v3-net-null",
            ingestionId = "v3-net-null-ingestion",
        ) as CanonicalImportResult.Success

        assertEquals(null, imported.envelope.priceObservations.single().netAmountMinor)
        assertFalse(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION in
                useCase.plan(imported.envelope).eligible,
        )

        val confirmation = useCase.confirm(
            imported.session.ingestionId,
            imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(confirmation.result is IngestionStartResult.Success)
        val states = useCase.submitSelected(
            imported.session.ingestionId,
            confirmation.envelope,
            setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        )
        assertEquals(
            ProjectionStatus.DISABLED,
            states.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.status,
        )
        assertTrue(submitter.requests.isEmpty())
    }

    @Test
    fun `v3 text-only candidate without explicit evidence derives a logical user statement ref`() {
        val root = parse(textOnlyRetailJson())
        val originalCandidate = root.getValue("product_candidates").jsonArray.single().jsonObject
        val legacyCandidate = JsonObject(originalCandidate.toMutableMap().apply { remove("evidence") })
        val input = JsonObject(root.toMutableMap().apply {
            put("product_candidates", JsonArray(listOf(legacyCandidate)))
        })

        val envelope = YeonsikOcrV3Json.decode(encode(input), "v3-text-only-derived-ref")
        val candidate = envelope.productCandidates.single()
        assertTrue(candidate.sourceAttachmentIds.isEmpty())
        assertTrue(candidate.evidence.all { it.sourceType == "user_statement" })
        assertTrue(candidate.evidence.all {
            it.sourceRef?.startsWith("user-statement:sha256:") == true
        })
    }

    @Test
    fun `v3 user statement evidence may derive missing ref and nullable hash from source text`() {
        val root = parse(textOnlyRetailJson())
        val candidate = root.getValue("product_candidates").jsonArray.single().jsonObject
        val evidence = candidate.getValue("evidence").jsonArray.map { item ->
            JsonObject(item.jsonObject.toMutableMap().apply {
                remove("source_ref")
                remove("content_hash")
            })
        }
        val input = JsonObject(root.toMutableMap().apply {
            put(
                "product_candidates",
                JsonArray(listOf(JsonObject(candidate.toMutableMap().apply {
                    put("evidence", JsonArray(evidence))
                }))),
            )
        })

        val decoded = YeonsikOcrV3Json.decode(encode(input), "v3-text-only-missing-optional-evidence")
        assertTrue(decoded.productCandidates.single().evidence.all {
            it.sourceRef?.startsWith("user-statement:sha256:") == true
        })
    }

    @Test
    fun `user statement evidence without source ref or user text is rejected`() {
        val root = parse(textOnlyRetailJson())
        val source = root.getValue("source").jsonObject
        val invalidSource = JsonObject(source.toMutableMap().apply { put("user_text", JsonNull) })
        val candidate = root.getValue("product_candidates").jsonArray.single().jsonObject
        val evidence = candidate.getValue("evidence").jsonArray.first().jsonObject
        val invalidEvidence = JsonObject(evidence.toMutableMap().apply { put("source_ref", JsonNull) })
        val invalidCandidate = JsonObject(candidate.toMutableMap().apply {
            put("evidence", JsonArray(listOf(invalidEvidence)))
        })
        val invalid = JsonObject(root.toMutableMap().apply {
            put("source", invalidSource)
            put("product_candidates", JsonArray(listOf(invalidCandidate)))
        })

        assertThrows(IllegalStateException::class.java) {
            YeonsikOcrV3Json.decode(encode(invalid), "v3-text-only-missing-source")
        }
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
        assertEquals("serving", price.quantity?.unit)
        assertEquals("menu_photo", price.evidence.single().sourceType)
        assertEquals(listOf("menu-photo-1"), price.evidence.single().sourceAttachmentIds)
        assertEquals("price-1", menu.priceObservationClientKey)
        assertEquals(null, envelope.receipt)
        val roundTripped = YeonsikOcrV3Json.decode(
            YeonsikOcrV3Json.encode(envelope),
            "v3-restaurant-round-trip",
        )
        assertEquals(
            envelope.priceObservations.single().netAmountMinor,
            roundTripped.priceObservations.single().netAmountMinor,
        )
        assertEquals(
            envelope.priceObservations.single().evidence,
            roundTripped.priceObservations.single().evidence,
        )
        assertTrue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in CanonicalProjectionPlanner.plan(envelope).eligible)
        assertTrue(IngestionProjection.FITNESS_NUTRITION in CanonicalProjectionPlanner.plan(envelope).eligible)
        assertEquals(
            setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
            CanonicalProjectionPlanner.plan(envelope).dependencies[IngestionProjection.FITNESS_NUTRITION],
        )
        assertFalse(IngestionProjection.CASHOS_RECEIPT in CanonicalProjectionPlanner.plan(envelope).eligible)
    }

    @Test
    fun `v3 restaurant example uses the SPEC price observation shape`() {
        val envelope = YeonsikOcrV3Json.decode(
            exampleJson("yeonsik-ocr.v3.restaurant.example.json"),
            "v3-restaurant-example",
        )
        val price = envelope.priceObservations.single()
        assertEquals(StandalonePriceObservationKind.RESTAURANT_PURCHASE, price.kind)
        assertEquals("KRW", price.currency)
        assertEquals(StandalonePriceObservationQuantity(1.0, "serving"), price.quantity)
        assertEquals(10_000L, price.netAmountMinor)
        assertEquals(listOf("menu-photo-1"), price.sourceAttachmentIds)
        assertEquals("menu_photo", price.evidence.single().sourceType)
        assertEquals(listOf("menu-photo-1"), price.evidence.single().sourceAttachmentIds)

        val roundTripped = YeonsikOcrV3Json.decode(
            YeonsikOcrV3Json.encode(envelope),
            "v3-restaurant-example-round-trip",
        )
        assertEquals(price, roundTripped.priceObservations.single())
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
    fun `text-only source evidence still requires the existing product image gate`() {
        val envelope = YeonsikOcrV3Json.decode(textOnlyRetailJson(), "v3-text-only-gate")
        val artifacts = setOf(
            IngestionArtifactKeys.productCandidate("product-brandx-chicken-20260910"),
            IngestionArtifactKeys.priceObservation("price-brandx-chicken-20260910"),
        )

        val sourceEvidence = IngestionEvidenceGate.evaluate(
            envelope = envelope,
            evidence = emptyList(),
            artifactKeys = artifacts,
            verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        )
        assertFalse(sourceEvidence.isAllowed)
        assertTrue(sourceEvidence.blockingIssues.isNotEmpty())

        val manual = IngestionEvidenceGate.evaluate(
            envelope = envelope,
            evidence = emptyList(),
            artifactKeys = artifacts,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
            explicitUserConfirmation = true,
        )
        assertTrue(manual.isAllowed)
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
        assertTrue(envelope.review.blockingIssues.none { it == "source_image_required" })

        val persistedInput = JsonObject(parse(retailJson()).toMutableMap().apply {
            put("review", reviewJson(includeAuthorityFields = true))
        })
        val persisted = YeonsikOcrV3Json.decode(
            encode(persistedInput),
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
        assertTrue(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE in plan.eligible)
        assertEquals(
            setOf(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE),
            plan.dependencies[IngestionProjection.PRICETRACE_PRICE_OBSERVATION],
        )
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
        assertEquals(1, product.requests.size)
        assertTrue(product.requests.single().projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
        assertTrue(store.get(imported.session.ingestionId)!!.projections.none {
            it.projection == IngestionProjection.CASHOS_RECEIPT && it.status == ProjectionStatus.UPLOADED
        })
        assertEquals(1, product.requests.size)
    }

    @Test
    fun `text-only manual review submits product candidate before standalone retail price`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val order = mutableListOf<IngestionProjection>()
        val product = RecordingSubmitter(onSubmit = { order += it.projection })
        val price = RecordingSubmitter(onSubmit = { order += it.projection })
        val useCase = CanonicalIngestionUseCase(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to product,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to price,
            ),
        )
        val imported = useCase.importJson(
            textOnlyRetailJson(),
            localDocumentId = "v3-text-only-submit",
            ingestionId = "v3-text-only-submit-ingestion",
        ) as CanonicalImportResult.Success
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, imported.envelope.review.status)

        val confirmation = useCase.confirm(
            imported.session.ingestionId,
            imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(confirmation.result is IngestionStartResult.Success)
        assertEquals(IngestionReviewStatus.READY, confirmation.envelope.review.status)

        val states = useCase.submitSelected(
            imported.session.ingestionId,
            confirmation.envelope,
            setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        )
        assertEquals(
            listOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            ),
            order,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            states.single { it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE }.status,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            states.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.status,
        )
    }

    @Test
    fun `selected standalone menu nutrition submits its price dependency but not CashOS`() = runBlocking {
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
        assertTrue(price.requests.size == 1)
        assertEquals(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            price.requests.single().projection,
        )
        assertEquals(
            ProjectionStatus.UPLOADED,
            states.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.status,
        )
        assertTrue(states.single { it.projection == IngestionProjection.CASHOS_RECEIPT }.status == ProjectionStatus.DISABLED)
    }

    @Test
    fun `selected meal submits its Fitness nutrition dependency first`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val order = mutableListOf<IngestionProjection>()
        val submitters = listOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION to RecordingSubmitter(onSubmit = { order += it.projection }),
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
        assertTrue(IngestionProjection.FITNESS_MEAL in plan.eligible)
        assertEquals(
            ProjectionStatus.PENDING,
            useCase.session(imported.session.ingestionId)!!.projections
                .single { it.projection == IngestionProjection.FITNESS_MEAL }.status,
        )
        assertEquals(
            setOf(IngestionProjection.FITNESS_NUTRITION),
            plan.dependencies[IngestionProjection.FITNESS_MEAL],
        )
        val states = useCase.submitSelected(
            imported.session.ingestionId,
            confirmation.envelope,
            setOf(IngestionProjection.FITNESS_MEAL),
        )
        val mealState = states.single { it.projection == IngestionProjection.FITNESS_MEAL }
        assertEquals(ProjectionStatus.UPLOADED, mealState.status)
        assertEquals(
            listOf(
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.FITNESS_MEAL,
            ),
            order,
        )
    }

    @Test
    fun `PT merchant OCR resolution preserves server metadata and resumes Fitness after edited facts are confirmed`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val priceTrace = OcrResolutionRecordingSubmitter()
        val fitness = RecordingSubmitter()
        val useCase = CanonicalIngestionUseCase(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to priceTrace,
                IngestionProjection.FITNESS_NUTRITION to fitness,
            ),
        )
        val imported = useCase.importJson(
            value = exampleJson("yeonsik-ocr.v2.restaurant.example.json"),
            localDocumentId = "pt-ocr-resolution",
            ingestionId = "pt-ocr-resolution-ingestion",
        ) as CanonicalImportResult.Success
        val firstConfirmation = useCase.confirm(
            imported.session.ingestionId,
            imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(firstConfirmation.result is IngestionStartResult.Success)

        val firstSubmit = useCase.submitSelected(
            imported.session.ingestionId,
            firstConfirmation.envelope,
            setOf(IngestionProjection.PRICETRACE_RECEIPT),
        )
        assertEquals(ProjectionStatus.UPLOADED, firstSubmit.single {
            it.projection == IngestionProjection.PRICETRACE_RECEIPT
        }.status)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, useCase.session(imported.session.ingestionId)?.reviewStatus)
        assertEquals(0, priceTrace.resolutionRequests.size)
        assertTrue(fitness.requests.isEmpty())

        val pendingMetadata = requireNotNull(useCase.session(imported.session.ingestionId)?.projections
            ?.single { it.projection == IngestionProjection.PRICETRACE_RECEIPT }?.metadataJson)
        val pendingResolution = requireNotNull(PriceTraceIdentityJson.ocrResolution(pendingMetadata))
        assertEquals("pt-resolution-42", pendingResolution.resolutionId)
        assertEquals("merchant_identity_ambiguous", pendingResolution.reasonCode)
        assertEquals(listOf("merchant_name", "branch_name"), pendingResolution.requiredSourceFacts)

        val editor = CanonicalReviewController(firstConfirmation.envelope)
        assertTrue(editor.updateMerchantName("Reviewed Restaurant"))
        val revised = useCase.reviseCanonicalDraft(
            imported.session.ingestionId,
            editor.state.value.envelope,
        ) as IngestionStartResult.Success
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, revised.session.reviewStatus)
        assertEquals(
            pendingMetadata,
            revised.session.projections.single { it.projection == IngestionProjection.PRICETRACE_RECEIPT }.metadataJson,
        )

        val secondConfirmation = useCase.confirm(
            imported.session.ingestionId,
            editor.state.value.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )

        assertTrue("${secondConfirmation.result}", secondConfirmation.result is IngestionStartResult.Success)
        assertEquals(1, priceTrace.receiptSubmissionCount)
        assertEquals(1, priceTrace.resolutionRequests.size)
        assertEquals("pt-resolution-42", priceTrace.resolutionRequests.single().resolutionId)
        assertEquals("Reviewed Restaurant", priceTrace.resolutionRequests.single().merchant.name)
        assertEquals(1, fitness.requests.size)
        assertEquals(IngestionProjection.FITNESS_NUTRITION, fitness.requests.single().projection)
        assertEquals(
            IngestionReviewStatus.READY,
            useCase.session(imported.session.ingestionId)?.reviewStatus,
        )
        assertEquals(
            "resolved",
            PriceTraceIdentityJson.ocrResolution(
                useCase.session(imported.session.ingestionId)?.projections
                    ?.single { it.projection == IngestionProjection.PRICETRACE_RECEIPT }?.metadataJson,
            )?.status,
        )
    }

    @Test
    fun `PT receipt menu OCR resolution uses exact source line and resumes Fitness after review`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val priceTrace = ReceiptMenuResolutionRecordingSubmitter()
        val fitness = RecordingSubmitter()
        val useCase = CanonicalIngestionUseCase(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to priceTrace,
                IngestionProjection.FITNESS_NUTRITION to fitness,
            ),
        )
        val imported = useCase.importJson(
            value = exampleJson("yeonsik-ocr.v2.restaurant.example.json"),
            localDocumentId = "pt-menu-ocr-resolution",
            ingestionId = "pt-menu-ocr-resolution-ingestion",
        ) as CanonicalImportResult.Success
        val firstConfirmation = useCase.confirm(
            imported.session.ingestionId,
            imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(firstConfirmation.result is IngestionStartResult.Success)

        val firstSubmit = useCase.submitSelected(
            imported.session.ingestionId,
            firstConfirmation.envelope,
            setOf(IngestionProjection.PRICETRACE_RECEIPT),
        )
        assertEquals(ProjectionStatus.UPLOADED, firstSubmit.single {
            it.projection == IngestionProjection.PRICETRACE_RECEIPT
        }.status)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, useCase.session(imported.session.ingestionId)?.reviewStatus)
        assertTrue(fitness.requests.isEmpty())
        val pendingMetadata = requireNotNull(useCase.session(imported.session.ingestionId)?.projections
            ?.single { it.projection == IngestionProjection.PRICETRACE_RECEIPT }?.metadataJson)
        val pending = PriceTraceIdentityJson.receiptMenuOcrResolutions(pendingMetadata).single()
        assertEquals("pt-line-resolution-1", pending.resolution.resolutionId)
        assertEquals("line-1", pending.sourceLineId)
        assertEquals("menu_identity_ambiguous", pending.resolution.reasonCode)
        assertEquals(listOf("item_name", "source_product_code_namespace", "source_product_code"), pending.resolution.requiredSourceFacts)

        val editor = CanonicalReviewController(firstConfirmation.envelope)
        assertTrue(editor.updateField("nutrition[food-1].menu_name", "Reviewed Noodles"))
        assertTrue(editor.updateField("receipt.merchant.catalog_namespace", "menu-source"))
        assertTrue(editor.updateField("receipt.line_items[line-1].merchant_sku", "SKU-17"))
        val revised = useCase.reviseCanonicalDraft(imported.session.ingestionId, editor.state.value.envelope)
        assertTrue(revised is IngestionStartResult.Success)
        assertEquals(
            pendingMetadata,
            useCase.session(imported.session.ingestionId)?.projections
                ?.single { it.projection == IngestionProjection.PRICETRACE_RECEIPT }?.metadataJson,
        )

        val secondConfirmation = useCase.confirm(
            imported.session.ingestionId,
            editor.state.value.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )

        assertTrue("${secondConfirmation.result}", secondConfirmation.result is IngestionStartResult.Success)
        assertEquals(1, priceTrace.receiptSubmissionCount)
        assertEquals(1, priceTrace.menuResolutionRequests.size)
        val resolvedRequest = priceTrace.menuResolutionRequests.single()
        assertEquals("pt-line-resolution-1", resolvedRequest.resolutionId)
        assertEquals("line-1", resolvedRequest.sourceLineId)
        assertEquals("Reviewed Noodles", resolvedRequest.menuFacts.itemName)
        assertEquals("menu-source", resolvedRequest.menuFacts.sourceMenuCodeNamespace)
        assertEquals("SKU-17", resolvedRequest.menuFacts.sourceMenuCode)
        assertEquals(1, fitness.requests.size)
        assertEquals(IngestionProjection.FITNESS_NUTRITION, fitness.requests.single().projection)
        assertEquals(
            IngestionReviewStatus.READY,
            useCase.session(imported.session.ingestionId)?.reviewStatus,
        )
        assertEquals("resolved", PriceTraceIdentityJson.tryDecode(
            useCase.session(imported.session.ingestionId)?.projections
                ?.single { it.projection == IngestionProjection.PRICETRACE_RECEIPT }?.metadataJson,
        )?.lines?.single()?.resolutionStatus)
    }

    @Test
    fun `PT standalone menu OCR resolution exact observation resumes Fitness after review`() = runBlocking {
        val store = InMemoryIngestionSessionStore()
        val priceTrace = StandaloneMenuResolutionRecordingSubmitter()
        val fitness = RecordingSubmitter()
        val useCase = CanonicalIngestionUseCase(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to priceTrace,
                IngestionProjection.FITNESS_NUTRITION to fitness,
            ),
        )
        val imported = useCase.importJson(
            value = restaurantJson(),
            localDocumentId = "pt-standalone-menu-ocr-resolution",
            ingestionId = "pt-standalone-menu-ocr-resolution-ingestion",
        ) as CanonicalImportResult.Success
        val firstConfirmation = useCase.confirm(
            imported.session.ingestionId,
            imported.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )
        assertTrue(firstConfirmation.result is IngestionStartResult.Success)

        useCase.submitSelected(
            imported.session.ingestionId,
            firstConfirmation.envelope,
            setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION),
        )
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, useCase.session(imported.session.ingestionId)?.reviewStatus)
        assertTrue(fitness.requests.isEmpty())
        val pendingMetadata = requireNotNull(useCase.session(imported.session.ingestionId)?.projections
            ?.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }?.metadataJson)
        val pending = PriceTraceIdentityJson.standaloneMenuOcrResolutions(pendingMetadata).single()
        assertEquals("pt-standalone-resolution-1", pending.resolution.resolutionId)
        assertEquals("price-1", pending.priceObservationClientKey)
        assertEquals("menu_identity_ambiguous", pending.resolution.reasonCode)
        assertEquals(listOf("item_name", "source_menu_code_namespace", "source_menu_code"), pending.resolution.requiredSourceFacts)

        val editor = CanonicalReviewController(firstConfirmation.envelope)
        assertTrue(editor.updateField("merchant_candidate.name", "Reviewed Restaurant"))
        assertTrue(editor.updateField("nutrition[nutrition-1].menu_name", "Reviewed Noodles"))
        val revised = useCase.reviseCanonicalDraft(imported.session.ingestionId, editor.state.value.envelope)
        assertTrue(revised is IngestionStartResult.Success)
        assertEquals(
            pendingMetadata,
            useCase.session(imported.session.ingestionId)?.projections
                ?.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }?.metadataJson,
        )

        val secondConfirmation = useCase.confirm(
            imported.session.ingestionId,
            editor.state.value.envelope,
            verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,
        )

        assertTrue("${secondConfirmation.result}", secondConfirmation.result is IngestionStartResult.Success)
        assertEquals(1, priceTrace.menuResolutionRequests.size)
        val resolvedRequest = priceTrace.menuResolutionRequests.single()
        assertEquals("pt-standalone-resolution-1", resolvedRequest.resolutionId)
        assertEquals("price-1", resolvedRequest.priceObservationClientKey)
        assertEquals("Reviewed Restaurant", resolvedRequest.merchant.name)
        assertEquals("Reviewed Noodles", resolvedRequest.menuFacts.itemName)
        assertEquals(1, fitness.requests.size)
        assertEquals(IngestionProjection.FITNESS_NUTRITION, fitness.requests.single().projection)
        assertEquals(
            IngestionReviewStatus.READY,
            useCase.session(imported.session.ingestionId)?.reviewStatus,
        )
        val resolvedMetadata = requireNotNull(useCase.session(imported.session.ingestionId)?.projections
            ?.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }?.metadataJson)
        val resolvedResponse = PriceTraceIdentityJson.standaloneMenuOcrResolutions(resolvedMetadata)
        assertTrue(resolvedResponse.isEmpty())
        val response = Json.parseToJsonElement(resolvedMetadata).jsonObject
            .getValue("observations").jsonArray.single().jsonObject.getValue("response").jsonObject
        assertEquals("exact", response["menuResolutionStatus"]?.jsonPrimitive?.content)
        assertEquals("restaurant-1", response["authoritativeIds"]?.jsonObject?.get("restaurantId")?.jsonPrimitive?.content)
        assertEquals(1, priceTrace.submitIdempotencyKeys.size)
        assertEquals(1, priceTrace.submitIdempotencyKeys.distinct().size)
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

    private class OcrResolutionRecordingSubmitter :
        IngestionProjectionSubmitter,
        OcrMerchantIdentityResolutionSubmitter {
        var receiptSubmissionCount = 0
        val resolutionRequests = mutableListOf<OcrMerchantIdentityResolutionRequest>()

        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
            receiptSubmissionCount += 1
            return ProjectionSubmission.Success(
                remoteId = "pt-receipt-42",
                metadataJson = Json.encodeToString(JsonObject.serializer(), needsOcrResolutionResponse()),
                requiresReview = true,
            )
        }

        override suspend fun resolveMerchantIdentity(
            request: OcrMerchantIdentityResolutionRequest,
        ): ProjectionSubmission {
            resolutionRequests += request
            return ProjectionSubmission.Success(
                remoteId = "pt-receipt-42",
                metadataJson = Json.encodeToString(JsonObject.serializer(), resolvedOcrResolutionResponse()),
            )
        }

        private fun needsOcrResolutionResponse() = buildJsonObject {
            put("receiptId", JsonPrimitive("pt-receipt-42"))
            put("merchantResolutionStatus", JsonPrimitive("needs_ocr_resolution"))
            put("ocrResolution", buildJsonObject {
                put("schemaVersion", JsonPrimitive("ocr-resolution.v1"))
                put("status", JsonPrimitive("needs_ocr_resolution"))
                put("resolutionId", JsonPrimitive("pt-resolution-42"))
                put("reasonCode", JsonPrimitive("merchant_identity_ambiguous"))
                put("requiredSourceFacts", JsonArray(listOf(
                    JsonPrimitive("merchant_name"), JsonPrimitive("branch_name"),
                )))
            })
        }

        private fun resolvedOcrResolutionResponse() = buildJsonObject {
            put("receiptId", JsonPrimitive("pt-receipt-42"))
            put("merchantResolutionStatus", JsonPrimitive("exact"))
            put("restaurantId", JsonPrimitive("restaurant-42"))
            put("restaurantLocationId", JsonPrimitive("location-42"))
            put("ocrResolution", buildJsonObject {
                put("schemaVersion", JsonPrimitive("ocr-resolution.v1"))
                put("status", JsonPrimitive("resolved"))
                put("resolutionId", JsonPrimitive("pt-resolution-42"))
                put("reasonCode", JsonNull)
                put("requiredSourceFacts", JsonArray(emptyList()))
            })
            put("lines", JsonArray(listOf(buildJsonObject {
                put("sourceLineId", JsonPrimitive("line-1"))
                put("resolutionStatus", JsonPrimitive("resolved"))
                put("restaurantMenuId", JsonPrimitive("menu-42"))
                put("catalogProductId", JsonPrimitive("catalog-42"))
            })))
        }
    }

    private class ReceiptMenuResolutionRecordingSubmitter :
        IngestionProjectionSubmitter,
        OcrMenuIdentityResolutionSubmitter {
        var receiptSubmissionCount = 0
        val menuResolutionRequests = mutableListOf<OcrReceiptMenuResolutionRequest>()

        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission.Success {
            receiptSubmissionCount += 1
            val sourceLineId = request.envelope!!.nutrition.filterIsInstance<IngestionNutrition.RestaurantEstimate>()
                .single().lineId!!
            return ProjectionSubmission.Success(
                remoteId = "pt-receipt-menu-42",
                metadataJson = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("receiptId", JsonPrimitive("pt-receipt-menu-42"))
                    put("merchantResolutionStatus", JsonPrimitive("exact"))
                    put("lines", JsonArray(listOf(buildJsonObject {
                        put("sourceLineId", JsonPrimitive(sourceLineId))
                        put("resolutionStatus", JsonPrimitive("needs_ocr_resolution"))
                        put("restaurantMenuId", JsonNull)
                        put("catalogProductId", JsonNull)
                        put("ocrResolution", buildJsonObject {
                            put("schemaVersion", JsonPrimitive("ocr-resolution.v1"))
                            put("status", JsonPrimitive("needs_ocr_resolution"))
                            put("resolutionId", JsonPrimitive("pt-line-resolution-1"))
                            put("reasonCode", JsonPrimitive("menu_identity_ambiguous"))
                            put("requiredSourceFacts", JsonArray(listOf(
                                JsonPrimitive("item_name"),
                                JsonPrimitive("source_product_code_namespace"),
                                JsonPrimitive("source_product_code"),
                            )))
                        })
                    })))
                }),
                requiresReview = true,
            )
        }

        override suspend fun resolveReceiptMenuIdentity(
            request: OcrReceiptMenuResolutionRequest,
        ): ProjectionSubmission = run {
            menuResolutionRequests += request
            ProjectionSubmission.Success(
                remoteId = "pt-receipt-menu-42",
                metadataJson = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("receiptId", JsonPrimitive("pt-receipt-menu-42"))
                    put("merchantResolutionStatus", JsonPrimitive("exact"))
                    put("restaurantId", JsonPrimitive("restaurant-42"))
                    put("restaurantLocationId", JsonPrimitive("location-42"))
                    put("lines", JsonArray(listOf(buildJsonObject {
                        put("sourceLineId", JsonPrimitive(request.sourceLineId))
                        put("resolutionStatus", JsonPrimitive("resolved"))
                        put("restaurantMenuId", JsonPrimitive("menu-42"))
                        put("catalogProductId", JsonPrimitive("catalog-42"))
                        put("ocrResolution", JsonNull)
                    })))
                }),
            )
        }

        override suspend fun resolveStandaloneMenuIdentity(
            request: OcrStandaloneMenuResolutionRequest,
        ): ProjectionSubmission = error("receipt menu resolver cannot resolve standalone rows")
    }

    private class StandaloneMenuResolutionRecordingSubmitter :
        IngestionProjectionSubmitter,
        OcrMenuIdentityResolutionSubmitter {
        val menuResolutionRequests = mutableListOf<OcrStandaloneMenuResolutionRequest>()
        val submitIdempotencyKeys = mutableListOf<String>()

        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
            submitIdempotencyKeys += request.idempotencyKey
            val priceKey = requireNotNull(request.envelope).priceObservations.single().clientKey
            return ProjectionSubmission.Success(
                remoteId = "pt-standalone-resolution-1",
                metadataJson = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("observations", JsonArray(listOf(buildJsonObject {
                        put("priceObservationClientKey", JsonPrimitive(priceKey))
                        put("response", buildJsonObject {
                            put("kind", JsonPrimitive("restaurant_purchase"))
                            put("observationId", JsonNull)
                            put("authorityStatus", JsonPrimitive("needs_ocr_resolution"))
                            put("merchantResolutionStatus", JsonPrimitive("needs_ocr_resolution"))
                            put("menuResolutionStatus", JsonPrimitive("needs_ocr_resolution"))
                            put("authoritativeIds", buildJsonObject {
                                put("restaurantId", JsonNull)
                                put("restaurantLocationId", JsonNull)
                                put("restaurantMenuId", JsonNull)
                                put("catalogProductId", JsonNull)
                            })
                            put("ocrResolution", buildJsonObject {
                                put("schemaVersion", JsonPrimitive("ocr-resolution.v1"))
                                put("status", JsonPrimitive("needs_ocr_resolution"))
                                put("resolutionId", JsonPrimitive("pt-standalone-resolution-1"))
                                put("reasonCode", JsonPrimitive("menu_identity_ambiguous"))
                                put("requiredSourceFacts", JsonArray(listOf(
                                    JsonPrimitive("item_name"),
                                    JsonPrimitive("source_menu_code_namespace"),
                                    JsonPrimitive("source_menu_code"),
                                )))
                            })
                        })
                    })))
                }),
                primaryUploaded = false,
                primaryPendingReason = "pricetrace_standalone_menu_identity_requires_review",
                requiresReview = true,
            )
        }

        override suspend fun resolveReceiptMenuIdentity(
            request: OcrReceiptMenuResolutionRequest,
        ): ProjectionSubmission = error("standalone resolver cannot resolve receipt lines")

        override suspend fun resolveStandaloneMenuIdentity(
            request: OcrStandaloneMenuResolutionRequest,
        ): ProjectionSubmission {
            menuResolutionRequests += request
            return ProjectionSubmission.Success(
                remoteId = "pt-observation-42",
                metadataJson = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("restaurant_purchase"))
                    put("observationId", JsonPrimitive("pt-observation-42"))
                    put("authorityStatus", JsonPrimitive("exact"))
                    put("merchantResolutionStatus", JsonPrimitive("exact"))
                    put("menuResolutionStatus", JsonPrimitive("exact"))
                    put("ocrResolution", JsonNull)
                    put("authoritativeIds", buildJsonObject {
                        put("restaurantId", JsonPrimitive("restaurant-1"))
                        put("restaurantLocationId", JsonPrimitive("location-1"))
                        put("restaurantMenuId", JsonPrimitive("menu-1"))
                        put("catalogProductId", JsonPrimitive("catalog-1"))
                    })
                }),
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
             put("merchant_sku", JsonNull)
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

    private fun textOnlyRetailJson(): String =
        exampleJson("yeonsik-ocr.v3.text-only-retail.example.json")

    private fun exampleJson(name: String): String = sequenceOf(
        java.io.File("examples", name),
        java.io.File("../examples", name),
    ).firstOrNull(java.io.File::isFile)?.readText()
        ?: error("example not found: $name")

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
             put("product_client_key", JsonNull)
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
        put("currency", JsonPrimitive("KRW"))
        put("quantity", buildJsonObject {
            put("value", JsonPrimitive(1.0))
            put("unit", JsonPrimitive("each"))
        })
        put("unit_price_amount_minor", JsonPrimitive(1300))
        put("gross_amount_minor", JsonPrimitive(1300))
        put("discount_amount_minor", JsonPrimitive(0))
        put("net_amount_minor", JsonPrimitive(1300))
        put("source_attachment_ids", JsonArray(listOf(JsonPrimitive("product-photo-1"))))
        put("evidence", JsonArray(listOf(buildJsonObject {
            put("source_type", JsonPrimitive("product_photo"))
            put("source_attachment_ids", JsonArray(listOf(JsonPrimitive("product-photo-1"))))
            put("field", JsonPrimitive("net_amount_minor"))
            put("observed_value", JsonPrimitive("1300"))
        })))
        put("confidence", JsonPrimitive(0.9))
    }

    private fun restaurantPriceJson() = buildJsonObject {
        put("client_key", JsonPrimitive("price-1"))
        put("kind", JsonPrimitive("restaurant_purchase"))
        put("product_client_key", JsonNull)
        put("item_name", JsonPrimitive("Noodles"))
        put("observed_on", JsonPrimitive("2026-09-07"))
        put("observed_at", JsonNull)
        put("currency", JsonPrimitive("KRW"))
        put("quantity", buildJsonObject {
            put("value", JsonPrimitive(1.0))
            put("unit", JsonPrimitive("serving"))
        })
        put("unit_price_amount_minor", JsonPrimitive(10000))
        put("gross_amount_minor", JsonPrimitive(10000))
        put("discount_amount_minor", JsonPrimitive(0))
        put("net_amount_minor", JsonPrimitive(10000))
        put("source_attachment_ids", JsonArray(listOf(JsonPrimitive("menu-photo-1"))))
        put("evidence", JsonArray(listOf(buildJsonObject {
            put("source_type", JsonPrimitive("menu_photo"))
            put("source_attachment_ids", JsonArray(listOf(JsonPrimitive("menu-photo-1"))))
            put("field", JsonPrimitive("net_amount_minor"))
            put("observed_value", JsonPrimitive("10000"))
        })))
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

    private fun reviewJson(includeAuthorityFields: Boolean = false) = buildJsonObject {
        put("status", JsonPrimitive(if (includeAuthorityFields) "ready" else "needs_review"))
        put("blocking_issues", JsonArray(emptyList<JsonElement>()))
        put("warnings", JsonArray(emptyList<JsonElement>()))
        if (includeAuthorityFields) {
            put("verification_basis", JsonPrimitive("MANUAL_CANONICAL_REVIEW"))
            put("user_verified", JsonPrimitive(true))
        }
    }

    private fun parse(value: String): JsonObject = json.parseToJsonElement(value).jsonObject

    private fun encode(value: JsonElement): String = json.encodeToString(JsonElement.serializer(), value)

    private companion object {
        val json = Json { explicitNulls = true; ignoreUnknownKeys = false; prettyPrint = true }
    }
}
