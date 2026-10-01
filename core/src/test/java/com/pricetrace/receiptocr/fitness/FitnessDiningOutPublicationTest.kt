package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionOrchestrator
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentity
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import com.pricetrace.receiptscanner.ingestion.PriceTraceLineIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV3Json
import com.pricetrace.receiptscanner.nutrition.NutritionContract
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FitnessDiningOutPublicationTest {
    @Test
    fun receiptBackedExactIdentityImportsThenPublishesExistingRestaurant() = runBlocking {
        val envelope = receiptEnvelope()
        val identity = receiptIdentity(listOf("line-1"), firstId = 101)
        val backend = FakeNutritionBackend()

        val result = FitnessCanonicalProjectionSubmitter(gateway(backend)).submit(
            request(envelope, "receipt-existing", identity),
        )

        assertTrue("$result", result is ProjectionSubmission.Success)
        assertEquals(1, backend.canonicalCreateCount)
        assertEquals(1, backend.publicationCommitCount)
        assertEquals("/rest/v1/rpc/import_canonical_nutrition_v2", backend.requests[0].url.substringAfter("nutrition.example"))
        assertEquals("/rest/v1/rpc/publish_verified_ocr_dining_out_nutrition_v1", backend.requests[1].url.substringAfter("nutrition.example"))
        assertEquals("public", backend.publicationRows.single()["visibility"]?.jsonPrimitive?.content)
        assertEquals("null", backend.canonicalBodies.single()["p_pricetrace_identity"].toString())
        assertExactIdentity(identity, "line-1", backend.publicationBodies.single())
    }

    @Test
    fun receiptFreeNewRestaurantPublishesOnlyWhenPriceTraceReturnsExactIdentity() = runBlocking {
        val source = YeonsikOcrV3Json.decode(readExample("yeonsik-ocr.v3.restaurant.example.json"), "local-v3-new-restaurant")
        val estimate = receiptEnvelope().nutrition.single() as IngestionNutrition.RestaurantEstimate
        val envelope = source.copy(nutrition = listOf(IngestionNutrition.RestaurantMenuEstimate(
            clientKey = "restaurant-menu-1",
            menuName = "Noodles",
            priceObservationClientKey = source.priceObservations.single().clientKey,
            estimate = estimate.estimate,
        )))
        val rowIdentity = menuIdentity(firstId = 202)
        val priceResponse = standaloneMetadata(rowIdentity, source.priceObservations.single().clientKey)
        val backend = FakeNutritionBackend()

        val result = FitnessCanonicalProjectionSubmitter(gateway(backend)).submit(
            request(
                envelope,
                "receipt-free-exact",
                dependencyMetadataJson = mapOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION to priceResponse),
            ),
        )

        assertTrue("$result", result is ProjectionSubmission.Success)
        assertEquals(1, backend.canonicalCreateCount)
        assertEquals(1, backend.publicationCommitCount)
        assertEquals("null", backend.canonicalBodies.single()["p_pricetrace_identity"].toString())
        assertExactIdentity(rowIdentity, backend.publicationBodies.single())
    }

    @Test
    fun legacyStandaloneFourIdsWithoutResolutionStatusesNeverCallPublicationRpc() = runBlocking {
        val source = YeonsikOcrV3Json.decode(readExample("yeonsik-ocr.v3.restaurant.example.json"), "legacy-standalone")
        val estimate = receiptEnvelope().nutrition.single() as IngestionNutrition.RestaurantEstimate
        val priceKey = source.priceObservations.single().clientKey
        val envelope = source.copy(nutrition = listOf(IngestionNutrition.RestaurantMenuEstimate(
            clientKey = "legacy-menu",
            menuName = "Noodles",
            priceObservationClientKey = priceKey,
            estimate = estimate.estimate,
        )))
        val backend = FakeNutritionBackend()

        val result = FitnessCanonicalProjectionSubmitter(gateway(backend)).submit(
            request(
                envelope,
                "legacy-standalone-no-status",
                dependencyMetadataJson = mapOf(
                    IngestionProjection.PRICETRACE_PRICE_OBSERVATION to legacyStandaloneMetadata(menuIdentity(601), priceKey),
                ),
            ),
        )

        assertTrue("$result", result is ProjectionSubmission.Failure)
        assertTrue((result as ProjectionSubmission.Failure).requiresReview)
        assertEquals(1, backend.canonicalCreateCount)
        assertEquals(0, backend.publicationCommitCount)
        assertEquals(1, backend.requests.size)
        assertTrue(backend.requests.single().url.endsWith("/rpc/import_canonical_nutrition_v3"))
        assertEquals("food-estimate.v1", backend.canonicalBodies.single()["p_input_contract"]?.jsonPrimitive?.content)
    }

    @Test
    fun standaloneRestaurantIdentityFailsClosedForEveryNonResolvedStatus() {
        val statuses = listOf(null, "unknown", "pending", "unverified", "ambiguous", "needs_ocr_resolution")
        fun ids() = buildJsonObject {
            put("restaurantId", JsonPrimitive(id(901)))
            put("restaurantLocationId", JsonPrimitive(id(902)))
            put("restaurantMenuId", JsonPrimitive(id(903)))
            put("catalogProductId", JsonPrimitive(id(904)))
        }

        statuses.forEach { status ->
            val unresolvedMerchant = buildJsonObject {
                put("kind", JsonPrimitive("restaurant_purchase"))
                status?.let { put("merchantResolutionStatus", JsonPrimitive(it)) }
                put("menuResolutionStatus", JsonPrimitive("resolved"))
                put("authoritativeIds", ids())
            }
            val unresolvedMenu = buildJsonObject {
                put("kind", JsonPrimitive("restaurant_purchase"))
                put("merchantResolutionStatus", JsonPrimitive("exact"))
                status?.let { put("menuResolutionStatus", JsonPrimitive(it)) }
                put("authoritativeIds", ids())
            }
            val unresolvedOcrResolution = buildJsonObject {
                put("kind", JsonPrimitive("restaurant_purchase"))
                put("merchantResolutionStatus", JsonPrimitive("exact"))
                put("menuResolutionStatus", JsonPrimitive("resolved"))
                put("ocrResolution", buildJsonObject {
                    status?.let { put("status", JsonPrimitive(it)) }
                })
                put("authoritativeIds", ids())
            }

            assertEquals(null, PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(unresolvedMerchant))
            assertEquals(null, PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(unresolvedMenu))
            assertEquals(null, PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(unresolvedOcrResolution))
        }

        val explicitNullStatuses = buildJsonObject {
            put("kind", JsonPrimitive("restaurant_purchase"))
            put("merchantResolutionStatus", JsonNull)
            put("menuResolutionStatus", JsonNull)
            put("authoritativeIds", ids())
        }
        val genericLegacyResolutionStatus = buildJsonObject {
            put("kind", JsonPrimitive("restaurant_purchase"))
            put("merchantResolutionStatus", JsonPrimitive("exact"))
            put("resolutionStatus", JsonPrimitive("resolved"))
            put("authoritativeIds", ids())
        }
        assertEquals(null, PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(explicitNullStatuses))
        assertEquals(null, PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(genericLegacyResolutionStatus))
    }

    @Test
    fun v5AmbiguityStatusOverridesAnyStandaloneIdsAndRequiresOcrReview() {
        val standalone = buildJsonObject {
            put("kind", JsonPrimitive("restaurant_purchase"))
            put("merchantResolutionStatus", JsonPrimitive("exact"))
            put("ocrResolution", buildJsonObject {
                put("schemaVersion", JsonPrimitive("ocr-resolution.v1"))
                put("status", JsonPrimitive("needs_ocr_resolution"))
                put("resolutionId", JsonPrimitive(id(999)))
            })
            put("authoritativeIds", buildJsonObject {
                put("restaurantId", JsonPrimitive(id(901)))
                put("restaurantLocationId", JsonPrimitive(id(902)))
                put("restaurantMenuId", JsonPrimitive(id(903)))
                put("catalogProductId", JsonPrimitive(id(904)))
            })
        }

        assertTrue(PriceTraceIdentityJson.requiresOcrReview(standalone))
        assertEquals(null, PriceTraceIdentityJson.exactRestaurantMenuFromStandaloneResponse(standalone))
        assertTrue(PriceTraceIdentityJson.requiresOcrReview(buildJsonObject {
            put("observations", JsonArray(listOf(buildJsonObject {
                put("priceObservationClientKey", JsonPrimitive("price-1"))
                put("response", standalone)
            })))
        }))
    }

    @Test
    fun pendingOcrResolutionsAreFoundAfterExactRowsWithNullResolutionMetadata() {
        val lineMetadata = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("lines", JsonArray(listOf(
                buildJsonObject {
                    put("sourceLineId", JsonPrimitive("line-exact"))
                    put("resolutionStatus", JsonPrimitive("resolved"))
                    put("ocrResolution", JsonNull)
                },
                buildJsonObject {
                    put("sourceLineId", JsonPrimitive("line-pending"))
                    put("resolutionStatus", JsonPrimitive("needs_ocr_resolution"))
                    put("ocrResolution", buildJsonObject {
                        put("status", JsonPrimitive("needs_ocr_resolution"))
                        put("resolutionId", JsonPrimitive(id(999)))
                        put("reasonCode", JsonPrimitive("menu_identity_ambiguous"))
                        put("requiredSourceFacts", JsonArray(emptyList()))
                    })
                },
            )))
        })
        val standaloneMetadata = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("observations", JsonArray(listOf(
                buildJsonObject {
                    put("priceObservationClientKey", JsonPrimitive("price-exact"))
                    put("response", buildJsonObject { put("ocrResolution", JsonNull) })
                },
                buildJsonObject {
                    put("priceObservationClientKey", JsonPrimitive("price-pending"))
                    put("response", buildJsonObject {
                        put("ocrResolution", buildJsonObject {
                            put("status", JsonPrimitive("needs_ocr_resolution"))
                            put("resolutionId", JsonPrimitive(id(998)))
                            put("requiredSourceFacts", JsonArray(emptyList()))
                        })
                    })
                },
            )))
        })

        assertEquals(
            listOf("line-pending"),
            PriceTraceIdentityJson.receiptMenuOcrResolutions(lineMetadata).map { it.sourceLineId },
        )
        assertEquals(
            listOf("price-pending"),
            PriceTraceIdentityJson.standaloneMenuOcrResolutions(standaloneMetadata)
                .map { it.priceObservationClientKey },
        )
    }

    @Test
    fun ambiguousRestaurantDoesNotCallFitnessAndRequiresOcrReview() = runBlocking {
        val envelope = receiptEnvelope()
        val ambiguous = PriceTraceIdentity(
            receiptId = id(303),
            merchantResolutionStatus = "needs_ocr_resolution",
            lines = listOf(PriceTraceLineIdentity(
                sourceLineId = "line-1",
                lineOrdinal = 1,
                resolutionStatus = "needs_ocr_resolution",
            )),
        )
        val backend = FakeNutritionBackend()

        val result = FitnessCanonicalProjectionSubmitter(gateway(backend)).submit(
            request(envelope, "ambiguous-receipt", ambiguous),
        )

        assertTrue("$result", result is ProjectionSubmission.Failure)
        result as ProjectionSubmission.Failure
        assertTrue(result.requiresReview)
        assertFalse(result.retryable)
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun multiLineReceiptPublishesOnlyEstimatedItemsByExactSourceLine() = runBlocking {
        val one = receiptEnvelope().nutrition.single() as IngestionNutrition.RestaurantEstimate
        val two = one.copy(clientKey = "food-2", lineId = "line-2", menuName = "Soup")
        val three = one.copy(clientKey = "food-3", lineId = "line-3", menuName = "Rice")
        val base = receiptEnvelope()
        val receipt = requireNotNull(base.receipt)
        val line = receipt.lineItems.single()
        val envelope = base.copy(
            receipt = receipt.copy(lineItems = listOf(line, line.copy(id = "line-2"), line.copy(id = "line-3"))),
            nutrition = listOf(one, two), // Receipt line 3 has no photo/estimate and must not be created.
        )
        val identity = receiptIdentity(listOf("line-1", "line-2", "line-3"), firstId = 401)
        val backend = FakeNutritionBackend()

        val result = FitnessCanonicalProjectionSubmitter(gateway(backend)).submit(
            request(envelope, "receipt-multi-line", identity),
        )

        assertTrue("$result", result is ProjectionSubmission.Success)
        assertEquals(2, backend.canonicalCreateCount)
        assertEquals(2, backend.publicationCommitCount)
        val menuIds = backend.publicationBodies.map { it.string("p_restaurant_menu_id") }.toSet()
        assertEquals(setOf(id(411), id(413)), menuIds)
        assertFalse(menuIds.contains(id(415)))
        assertEquals(setOf("Noodles", "Soup"), backend.canonicalBodies.map { it.string("p_food_name") }.toSet())
        assertFalse(backend.canonicalBodies.any { it.string("p_food_name") == three.menuName })
    }

    @Test
    fun publicationRetryReplaysCanonicalImportAndUsesSamePublicationKey() = runBlocking {
        val envelope = receiptEnvelope()
        val backend = FakeNutritionBackend().apply { commitAndDropNextPublicationResponse = true }
        val request = request(envelope, "partial-publication", receiptIdentity(listOf("line-1"), firstId = 501))
        val submitter = FitnessCanonicalProjectionSubmitter(gateway(backend))

        val first = submitter.submit(request) as ProjectionSubmission.Failure
        assertTrue(first.retryable)
        assertNotNull(first.metadataJson)
        assertEquals(1, backend.canonicalCreateCount)
        assertEquals(1, backend.publicationCommitCount)

        val retry = submitter.submit(request)

        assertTrue("$retry", retry is ProjectionSubmission.Success)
        assertEquals(1, backend.canonicalCreateCount)
        assertEquals(1, backend.publicationCommitCount)
        assertEquals(2, backend.canonicalBodies.size)
        assertEquals(2, backend.publicationBodies.size)
        assertEquals(
            backend.publicationBodies.first().string("p_idempotency_key"),
            backend.publicationBodies.last().string("p_idempotency_key"),
        )
        assertEquals(
            backend.canonicalBodies.first().string("p_idempotency_key"),
            backend.canonicalBodies.last().string("p_idempotency_key"),
        )
    }

    @Test
    fun identicalBundleRetryDoesNotCreateDuplicateNutritionOrPublication() = runBlocking {
        val envelope = receiptEnvelope()
        val backend = FakeNutritionBackend()
        val submitter = FitnessCanonicalProjectionSubmitter(gateway(backend))
        val request = request(envelope, "same-bundle", receiptIdentity(listOf("line-1"), firstId = 601))

        assertTrue(submitter.submit(request) is ProjectionSubmission.Success)
        assertTrue(submitter.submit(request) is ProjectionSubmission.Success)

        assertEquals(1, backend.canonicalCreateCount)
        assertEquals(1, backend.publicationCommitCount)
        assertEquals(2, backend.canonicalBodies.size)
        assertEquals(2, backend.publicationBodies.size)
    }

    @Test
    fun packagedProductKeepsExistingImportAndReceiptFreeInsufficientIdentityStaysUnresolved() = runBlocking {
        val parsedPackaged = YeonsikOcrV2Json.decode(readExample("yeonsik-ocr.v2.packaged-product.example.json"), "local-packaged")
        val label = parsedPackaged.nutrition.single() as IngestionNutrition.ProductLabel
        val packaged = parsedPackaged.copy(nutrition = listOf(label.copy(
            draft = label.draft.copy(category = NutritionContract.DEFAULT_CATEGORY)
                .asUserVerified("2026-09-27T00:00:00Z"),
        )))
        val packagedBackend = FakeNutritionBackend()
        val packagedResult = FitnessCanonicalProjectionSubmitter(gateway(packagedBackend)).submit(
            request(packaged, "packaged-product"),
        )
        assertTrue("$packagedResult", packagedResult is ProjectionSubmission.Success)
        assertEquals(0, packagedBackend.publicationCommitCount)

        val unresolved = YeonsikOcrV2Json.decode(
            readExample("yeonsik-ocr.v2.restaurant-food-photo.example.json"),
            "local-receipt-free-unresolved",
        )
        val unresolvedBackend = FakeNutritionBackend()
        val unresolvedResult = FitnessCanonicalProjectionSubmitter(gateway(unresolvedBackend)).submit(
            request(unresolved, "receipt-free-no-exact-identity"),
        ) as ProjectionSubmission.Failure

        assertTrue(unresolvedResult.requiresReview)
        assertEquals(1, unresolvedBackend.canonicalCreateCount) // Existing private estimate import remains replay-safe.
        assertEquals(0, unresolvedBackend.publicationCommitCount)
        assertEquals("null", unresolvedBackend.canonicalBodies.single()["p_pricetrace_identity"].toString())
    }

    @Test
    fun orchestratorPersistsNeedsReviewWhenPublicationNeedsMoreIdentity() = runBlocking {
        val envelope = receiptEnvelope()
        val identity = receiptIdentity(listOf("line-1"), firstId = 701)
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest) = ProjectionSubmission.Success(
                        remoteId = identity.receiptId,
                        metadataJson = Json.encodeToString(JsonObject.serializer(), PriceTraceIdentityJson.encode(identity)),
                    )
                },
                IngestionProjection.FITNESS_NUTRITION to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest) = ProjectionSubmission.Failure(
                        message = "restaurant_menu_identity_requires_ocr_review:food-1",
                        retryable = false,
                        requiresReview = true,
                    )
                },
            ),
            now = { "2026-09-27T00:00:00Z" },
        )
        val evidence = listOf(
            LocalEvidence("receipt-1", SourceAttachmentType.RECEIPT, fileReadable = true),
            LocalEvidence("food-1", SourceAttachmentType.FOOD_PHOTO, fileReadable = true),
        )
        val started = orchestrator.start("review-state", "local-review-state", envelope, evidence)
        assertTrue("start result: $started", started is IngestionStartResult.Success)
        orchestrator.markUserVerified("review-state", envelope, evidence)
        orchestrator.markNutritionVerified("review-state", envelope, evidence)
        assertEquals(ProjectionStatus.UPLOADED, orchestrator.submitProjection(
            "review-state", IngestionProjection.PRICETRACE_RECEIPT, envelope,
        ).status)

        assertEquals(ProjectionStatus.BLOCKED, orchestrator.submitProjection(
            "review-state", IngestionProjection.FITNESS_NUTRITION, envelope,
        ).status)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, store.get("review-state")?.reviewStatus)
    }

    @Test
    fun acceptedPriceTraceReceiptWithV5AmbiguityImmediatelyNeedsOcrReview() = runBlocking {
        val envelope = receiptEnvelope()
        val identity = receiptIdentity(listOf("line-1"), firstId = 801)
        val response = buildJsonObject {
            put("receiptId", JsonPrimitive(identity.receiptId))
            put("merchantResolutionStatus", JsonPrimitive("needs_ocr_resolution"))
            put("ocrResolution", buildJsonObject {
                put("schemaVersion", JsonPrimitive("ocr-resolution.v1"))
                put("status", JsonPrimitive("needs_ocr_resolution"))
            })
            put("lines", JsonArray(listOf(buildJsonObject {
                put("sourceLineId", JsonPrimitive("line-1"))
                put("resolutionStatus", JsonPrimitive("needs_ocr_resolution"))
            })))
        }
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(
            store = store,
            submitters = mapOf(
                IngestionProjection.PRICETRACE_RECEIPT to object : IngestionProjectionSubmitter {
                    override suspend fun submit(request: ProjectionRequest) = ProjectionSubmission.Success(
                        remoteId = identity.receiptId,
                        metadataJson = Json.encodeToString(JsonObject.serializer(), response),
                        requiresReview = PriceTraceIdentityJson.requiresOcrReview(response),
                    )
                },
            ),
            now = { "2026-09-27T00:00:00Z" },
        )
        val evidence = listOf(
            LocalEvidence("receipt-1", SourceAttachmentType.RECEIPT, fileReadable = true),
            LocalEvidence("food-1", SourceAttachmentType.FOOD_PHOTO, fileReadable = true),
        )
        val started = orchestrator.start("v5-needs-review", "local-v5-needs-review", envelope, evidence)
        assertTrue("start result: $started", started is IngestionStartResult.Success)
        orchestrator.markUserVerified("v5-needs-review", envelope, evidence)
        orchestrator.markNutritionVerified("v5-needs-review", envelope, evidence)

        val receiptState = orchestrator.submitProjection(
            "v5-needs-review", IngestionProjection.PRICETRACE_RECEIPT, envelope,
        )

        assertEquals(ProjectionStatus.UPLOADED, receiptState.status)
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, store.get("v5-needs-review")?.reviewStatus)
    }

    private fun request(
        envelope: YeonsikOcrEnvelope,
        key: String,
        identity: PriceTraceIdentity? = null,
        dependencyMetadataJson: Map<IngestionProjection, String> = emptyMap(),
    ) = ProjectionRequest(
        ingestionId = "test-ingestion",
        projection = IngestionProjection.FITNESS_NUTRITION,
        canonicalPayload = "{}",
        resolvedIdentity = identity?.let(::ProjectionIdentity),
        idempotencyKey = key,
        envelope = envelope,
        localDocumentId = "test-document",
        revisionSeq = 1,
        dependencyMetadataJson = dependencyMetadataJson,
    )

    private fun receiptEnvelope(): YeonsikOcrEnvelope {
        val decoded = YeonsikOcrV2Json.decode(
            readExample("yeonsik-ocr.v2.restaurant.example.json"),
            "local-restaurant-receipt",
        )
        return decoded.copy(
            nutrition = decoded.nutrition.filterIsInstance<IngestionNutrition.RestaurantEstimate>(),
            consumption = emptyList(),
            targets = setOf(IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.FITNESS_NUTRITION),
        )
    }

    private fun receiptIdentity(sourceLineIds: List<String>, firstId: Int): PriceTraceIdentity = PriceTraceIdentity(
        receiptId = id(firstId),
        restaurantId = id(firstId + 1),
        restaurantLocationId = id(firstId + 2),
        merchantResolutionStatus = "exact",
        lines = sourceLineIds.mapIndexed { index, sourceLineId ->
            PriceTraceLineIdentity(
                sourceLineId = sourceLineId,
                lineOrdinal = index + 1,
                restaurantMenuId = id(firstId + 10 + index * 2),
                catalogProductId = id(firstId + 11 + index * 2),
                resolutionStatus = "resolved",
            )
        },
    )

    private fun menuIdentity(firstId: Int) = com.pricetrace.receiptscanner.ingestion.PriceTraceRestaurantMenuIdentity(
        restaurantId = id(firstId),
        restaurantLocationId = id(firstId + 1),
        restaurantMenuId = id(firstId + 2),
        catalogProductId = id(firstId + 3),
    )

    private fun standaloneMetadata(
        identity: com.pricetrace.receiptscanner.ingestion.PriceTraceRestaurantMenuIdentity,
        clientKey: String,
    ): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("observations", JsonArray(listOf(buildJsonObject {
                put("priceObservationClientKey", JsonPrimitive(clientKey))
                put("response", buildJsonObject {
                    put("kind", JsonPrimitive("restaurant_purchase"))
                    put("authorityStatus", JsonPrimitive("exact"))
                    put("merchantResolutionStatus", JsonPrimitive("exact"))
                    put("menuResolutionStatus", JsonPrimitive("exact"))
                    put("observationId", JsonPrimitive("observation-$clientKey"))
                    put("ocrResolution", JsonNull)
                    put("authoritativeIds", buildJsonObject {
                        put("restaurantId", JsonPrimitive(identity.restaurantId))
                        put("restaurantLocationId", JsonPrimitive(identity.restaurantLocationId))
                        put("restaurantMenuId", JsonPrimitive(identity.restaurantMenuId))
                        put("catalogProductId", JsonPrimitive(identity.catalogProductId))
                    })
                })
            })))
        },
    )

    private fun legacyStandaloneMetadata(
        identity: com.pricetrace.receiptscanner.ingestion.PriceTraceRestaurantMenuIdentity,
        clientKey: String,
    ): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("observations", JsonArray(listOf(buildJsonObject {
                put("priceObservationClientKey", JsonPrimitive(clientKey))
                put("response", buildJsonObject {
                    put("kind", JsonPrimitive("restaurant_purchase"))
                    put("authoritativeIds", buildJsonObject {
                        put("restaurantId", JsonPrimitive(identity.restaurantId))
                        put("restaurantLocationId", JsonPrimitive(identity.restaurantLocationId))
                        put("restaurantMenuId", JsonPrimitive(identity.restaurantMenuId))
                        put("catalogProductId", JsonPrimitive(identity.catalogProductId))
                    })
                })
            })))
        },
    )

    private fun assertExactIdentity(
        identity: PriceTraceIdentity,
        sourceLineId: String,
        publicationBody: JsonObject,
    ) = assertExactIdentity(
        PriceTraceIdentityJson.exactRestaurantMenuForSourceLine(identity, sourceLineId)!!,
        publicationBody,
    )

    private fun assertExactIdentity(identity: com.pricetrace.receiptscanner.ingestion.PriceTraceRestaurantMenuIdentity, publicationBody: JsonObject) {
        assertEquals(identity.restaurantId, publicationBody.string("p_restaurant_id"))
        assertEquals(identity.restaurantLocationId, publicationBody.string("p_restaurant_location_id"))
        assertEquals(identity.restaurantMenuId, publicationBody.string("p_restaurant_menu_id"))
        assertEquals(identity.catalogProductId, publicationBody.string("p_catalog_product_id"))
    }

    private fun id(value: Int): String = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }

    private fun gateway(backend: FakeNutritionBackend) = NutritionSupabaseGateway(FakeNutritionStore(), backend)

    private class FakeNutritionStore : NutritionSupabaseStore {
        private var config = NutritionSupabaseConfig(
            url = "https://nutrition.example",
            publishableKey = "abcdefghijklmnopqrst",
            userId = "test-user",
            email = "test@example.com",
            accessToken = "test-access-token",
        )

        override fun read(): NutritionSupabaseConfig = config
        override fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig> =
            Result.success(config.copy(url = url, publishableKey = publishableKey).also { config = it })
        override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String): Result<NutritionSupabaseConfig> =
            Result.success(config.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken).also { config = it })
        override fun clearSession(): Boolean {
            config = config.copy(userId = "", accessToken = "", refreshToken = "")
            return true
        }
    }

    private class FakeNutritionBackend : NutritionHttpTransport {
        val requests = mutableListOf<NutritionHttpRequest>()
        val canonicalBodies = mutableListOf<JsonObject>()
        val publicationBodies = mutableListOf<JsonObject>()
        val publicationRows = mutableListOf<JsonObject>()
        private val canonicalLedger = linkedMapOf<String, Pair<String, String>>()
        private val publicationLedger = linkedMapOf<String, JsonObject>()
        var canonicalCreateCount = 0
            private set
        var publicationCommitCount = 0
            private set
        var commitAndDropNextPublicationResponse = false

        override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
            requests += request
            val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
            return when {
                request.url.endsWith("/rest/v1/rpc/import_canonical_nutrition_v2") ||
                    request.url.endsWith("/rest/v1/rpc/import_canonical_nutrition_v3") -> {
                    canonicalBodies += body
                    val key = body.string("p_idempotency_key")
                    val previous = canonicalLedger[key]
                    val replayed = previous != null
                    val (canonicalId, foodId) = previous ?: run {
                        canonicalCreateCount += 1
                        val created = "canonical-$canonicalCreateCount" to "nutrition-$canonicalCreateCount"
                        canonicalLedger[key] = created
                        created
                    }
                    NutritionHttpResponse(200, canonicalImportResponse(canonicalId, foodId, replayed, body.string("p_input_contract")))
                }
                request.url.endsWith("/rest/v1/rpc/publish_verified_ocr_dining_out_nutrition_v1") -> {
                    publicationBodies += body
                    val key = body.string("p_idempotency_key")
                    val existing = publicationLedger[key]
                    val response: JsonObject
                    if (existing == null) {
                        publicationCommitCount += 1
                        response = publicationResponse(body, replayed = false)
                        publicationLedger[key] = response
                        if (commitAndDropNextPublicationResponse) {
                            commitAndDropNextPublicationResponse = false
                            return NutritionHttpResponse(503, "publication response lost after commit")
                        }
                    } else {
                        response = publicationResponse(body, replayed = true)
                    }
                    publicationRows += response
                    NutritionHttpResponse(200, Json.encodeToString(JsonArray.serializer(), JsonArray(listOf(response))))
                }
                else -> error("Unexpected Nutrition request ${request.url}")
            }
        }

        private fun canonicalImportResponse(
            canonicalId: String,
            foodId: String,
            replayed: Boolean,
            inputContract: String,
        ): String = Json.encodeToString(JsonArray.serializer(), JsonArray(listOf(buildJsonObject {
            put("canonical_import_id", JsonPrimitive(canonicalId))
            put("idempotent_replay", JsonPrimitive(replayed))
            put("nutrition_food_id", JsonPrimitive(foodId))
            put("input_contract", JsonPrimitive(inputContract))
            put("projection_source_type", JsonPrimitive("food_image_estimate"))
            put("projection_import_id", JsonPrimitive("projection-$canonicalId"))
            put("catalog_product_id", JsonPrimitive("00000000-0000-4000-8000-000000000999"))
            put("estimation_evidence_id", JsonPrimitive("evidence-$canonicalId"))
            put("visibility", JsonPrimitive("private"))
        })))

        private fun publicationResponse(body: JsonObject, replayed: Boolean): JsonObject = buildJsonObject {
            put("canonical_import_id", body["p_canonical_import_id"]!!)
            put("nutrition_food_id", body["p_nutrition_food_id"]!!)
            put("restaurant_id", body["p_restaurant_id"]!!)
            put("restaurant_location_id", body["p_restaurant_location_id"]!!)
            put("restaurant_menu_id", body["p_restaurant_menu_id"]!!)
            put("catalog_product_id", body["p_catalog_product_id"]!!)
            put("nutrition_link_id", JsonPrimitive("nutrition-link-${body.string("p_nutrition_food_id")}"))
            put("nutrition_link_revision", JsonPrimitive(1))
            put("visibility", JsonPrimitive("public"))
            put("food_revision", JsonPrimitive(1))
            put("publication_revision", JsonPrimitive(1))
            put("published_at", JsonPrimitive("2026-09-27T00:00:00Z"))
            put("replayed", JsonPrimitive(replayed))
        }
    }

}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Missing JSON string $key")
