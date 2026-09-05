package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.ingestion.PriceTraceIdentityJson
import com.pricetrace.receiptscanner.ingestion.PriceTraceProductIdentityJson
import com.pricetrace.receiptscanner.publisher.PriceObservationJson
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.SocketTimeoutException

sealed interface PriceTraceCanonicalOutcome {
    data class Success(val response: JsonObject) : PriceTraceCanonicalOutcome
    data class Failure(val kind: PriceObservationFailureKind, val message: String? = null) : PriceTraceCanonicalOutcome
}

sealed interface PriceTraceProductReadOutcome {
    data class Success(val revision: String) : PriceTraceProductReadOutcome
    data class Failure(val kind: PriceObservationFailureKind, val message: String? = null) : PriceTraceProductReadOutcome
}

/** PriceTrace's verified receipt.v2 and merchant-only candidate RPC boundary. */
internal class PriceTraceCanonicalGateway(
    private val store: PriceTraceSupabaseStore,
    private val transport: PriceObservationHttpTransport = HttpsPriceObservationHttpTransport(),
) {
    suspend fun submitVerifiedReceipt(idempotencyKey: String, receipt: ReceiptV2): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitReceiptOnce(idempotencyKey, receipt, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) return first
        val refreshed = refresh(initial) ?: return first
        return submitReceiptOnce(idempotencyKey, receipt, refreshed)
    }

    suspend fun submitMerchantCandidate(idempotencyKey: String, merchant: com.pricetrace.receiptscanner.ingestion.MerchantCandidate): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitMerchantOnce(idempotencyKey, merchant, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) return first
        val refreshed = refresh(initial) ?: return first
        return submitMerchantOnce(idempotencyKey, merchant, refreshed)
    }

    suspend fun submitProductCandidates(
        idempotencyKey: String,
        candidates: List<ProductCandidate>,
    ): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitProductCandidatesOnce(idempotencyKey, candidates, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return submitProductCandidatesOnce(idempotencyKey, candidates, refreshed)
    }

    /** Reads the exact PriceTrace product revision required by the cross-service link contract. */
    suspend fun readExactProductRevision(catalogProductId: String): PriceTraceProductReadOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = readExactProductRevisionOnce(catalogProductId, initial)
        if (first !is PriceTraceProductReadOutcome.Failure ||
            first.kind != PriceObservationFailureKind.AUTHENTICATION
        ) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return readExactProductRevisionOnce(catalogProductId, refreshed)
    }

    private suspend fun readExactProductRevisionOnce(
        catalogProductId: String,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceProductReadOutcome {
        return try {
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = "/rest/v1/rpc/get_product_read_v1",
                    body = PriceObservationJson.encodeProductReadRequest(
                        query = null,
                        limit = 1,
                        catalogProductId = catalogProductId,
                    ),
                ),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceProductReadOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
            }
            val read = PriceObservationJson.decodeProductRead(response.body)
            require(read.products.size == 1 && read.products.single().catalogProductId == catalogProductId) {
                "PriceTrace exact product read did not return the requested catalog product"
            }
            PriceTraceProductReadOutcome.Success(read.revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
        } catch (_: IOException) {
            PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.NETWORK)
        } catch (error: Exception) {
            PriceTraceProductReadOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
        }
    }

    private suspend fun submitReceiptOnce(
        idempotencyKey: String,
        receipt: ReceiptV2,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        val sanitized = receipt.copy(
            document = receipt.document.copy(
                source = receipt.document.source.copy(sourceImages = emptyList(), rawText = null),
            ),
            payments = receipt.payments.map { payment -> payment.copy(reference = null) },
        )
        require(sanitized.lineItems.flatMap { it.identifiers }.all { it.scheme == "merchant_sku" }) {
            "PriceTrace verified receipt accepts only merchant_sku identifiers"
        }
        val body = buildJsonObject {
            put("p_idempotency_key", JsonPrimitive(idempotencyKey))
            put("p_receipt", json.parseToJsonElement(ReceiptV2Json.encodeCanonical(sanitized)))
        }.encode()
        val response = transport.execute(request(config, "POST", "/rest/v1/rpc/submit_verified_receipt_v2", body = body))
        if (response.statusCode !in 200..299) {
            return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
        }
        PriceTraceCanonicalOutcome.Success(decodeResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun submitMerchantOnce(
        idempotencyKey: String,
        merchant: com.pricetrace.receiptscanner.ingestion.MerchantCandidate,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        val merchantJson = buildJsonObject {
            put("merchant_name", JsonPrimitive(merchant.name))
            put("branch_name", merchant.branchName?.let(::JsonPrimitive) ?: JsonNull)
            put("business_kind", JsonPrimitive(merchant.businessKind.wireValue))
            put("business_registration_number", merchant.businessRegistrationNumber?.let(::JsonPrimitive) ?: JsonNull)
            put("address", merchant.address?.let(::JsonPrimitive) ?: JsonNull)
            put("phone", merchant.phone?.let(::JsonPrimitive) ?: JsonNull)
            put("source_namespace", merchant.sourceNamespace?.let(::JsonPrimitive) ?: JsonNull)
            put("source_location_code", merchant.sourceLocationCode?.let(::JsonPrimitive) ?: JsonNull)
        }
        val body = buildJsonObject {
            put("p_idempotency_key", JsonPrimitive(idempotencyKey))
            put("p_merchant", merchantJson)
            put("p_user_verified", JsonPrimitive(true))
        }.encode()
        val response = transport.execute(request(config, "POST", "/rest/v1/rpc/submit_merchant_identity_candidate_v1", body = body))
        if (response.statusCode !in 200..299) {
            return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
        }
        PriceTraceCanonicalOutcome.Success(decodeResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private suspend fun submitProductCandidatesOnce(
        idempotencyKey: String,
        candidates: List<ProductCandidate>,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        require(candidates.isNotEmpty()) { "product_candidates_required" }
        val responses = mutableListOf<JsonObject>()
        candidates.forEach { candidate ->
            val candidateKey = StableIds.sha256("$idempotencyKey|candidate=${candidate.clientKey}")
            val body = buildJsonObject {
                put("p_idempotency_key", JsonPrimitive(candidateKey))
                put("p_candidate", productCandidateJson(candidate))
            }.encode()
            // The RPC is a PriceTrace-owned contract. The OCR-App sends facts only;
            // CatalogProduct IDs and product revisions are accepted only from its response.
            val response = transport.execute(
                request(config, "POST", "/rest/v1/rpc/submit_product_candidate_v1", body = body),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceCanonicalOutcome.Failure(
                    classify(response),
                    response.body.takeIf(String::isNotBlank),
                )
            }
            responses += decodeResponse(response.body)
        }
        val products = candidates.zip(responses).mapNotNull { (candidate, response) ->
            response.requiredStringOrNull("catalogProductId", "catalog_product_id")?.let { catalogId ->
                buildJsonObject {
                    put("clientKey", JsonPrimitive(candidate.clientKey))
                    put("catalogProductId", JsonPrimitive(catalogId))
                    put("productRevision", response["productRevision"] ?: response["product_revision"] ?: JsonNull)
                }
            }
        }
        PriceTraceCanonicalOutcome.Success(buildJsonObject {
            put("schemaVersion", JsonPrimitive("product-candidate.v1"))
            put("contract", JsonPrimitive("PRICETRACE_PRODUCT_CANDIDATE"))
            put("responses", JsonArray(responses))
            put("products", JsonArray(products))
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NETWORK)
    } catch (error: Exception) {
        PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.CONTRACT, error.message)
    }

    private fun productCandidateJson(candidate: ProductCandidate): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive("PRICETRACE_PRODUCT_CANDIDATE"))
        put("contract_version", JsonPrimitive("product-candidate.v1"))
        put("source_app", JsonPrimitive("pricetrace_ocr_app"))
        put("source_version", candidate.sourceVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("candidate_type", JsonPrimitive(candidate.candidateType))
        put("product_name", JsonPrimitive(candidate.productName))
        put("brand", candidate.effectiveBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", candidate.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("specification", candidate.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("content_amount", candidate.contentAmount?.let(::JsonPrimitive) ?: JsonNull)
        put("content_unit", candidate.contentUnit?.let(::JsonPrimitive) ?: JsonNull)
        put("package_count", candidate.packageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("variant", candidate.variant?.let(::JsonPrimitive) ?: JsonNull)
        put("identifiers", JsonArray(candidateIdentifiers(candidate)))
        put("evidence", JsonArray(candidate.evidence.flatMap { evidence ->
            evidence.sourceAttachmentIds.map { attachmentId -> buildJsonObject {
                put("source_type", JsonPrimitive(evidence.sourceType))
                put("source_ref", JsonPrimitive(evidence.sourceRef ?: evidence.source ?: attachmentId))
                put("field", JsonPrimitive(evidence.field))
                put("observed_value", evidence.observedValue?.let(::JsonPrimitive)
                    ?: JsonPrimitive(candidate.productName))
                evidence.contentHash?.takeIf { it.matches(Regex("^sha256:[a-f0-9]{64}$")) }
                    ?.let { put("content_hash", JsonPrimitive(it)) }
            }}
        }))
        put("provenance", buildJsonObject {
            candidate.evidence.firstOrNull()?.sourceAttachmentIds?.firstOrNull()
                ?.let { put("capture_id", JsonPrimitive(it)) }
            put("extraction_method", JsonPrimitive("mixed"))
            put("extractor", JsonPrimitive("ocr-app"))
            candidate.sourceVersion?.let { put("extractor_version", JsonPrimitive(it)) }
            candidate.sourceVersion?.let { put("source_revision", JsonPrimitive(it)) }
        })
    }

    private fun candidateIdentifiers(candidate: ProductCandidate): List<JsonObject> {
        val values = linkedMapOf<String, String>()
        candidate.barcode?.let { values["gtin"] = it }
        candidate.ean?.let { values["ean"] = it }
        candidate.upc?.let { values["upc"] = it }
        return values.map { (scheme, value) -> buildJsonObject {
            put("scheme", JsonPrimitive(scheme))
            put("value", JsonPrimitive(value))
        }}
    }

    private fun decodeResponse(value: String): JsonObject {
        val element = json.parseToJsonElement(value)
        val row = when (element) {
            is JsonObject -> element
            else -> element.jsonArray.single().jsonObject
        }
        require(listOf("receiptId", "candidateId", "catalogProductId").any { row[it] is JsonPrimitive }) {
            "PriceTrace canonical response is missing receiptId/candidateId/catalogProductId"
        }
        return row
    }

    private fun JsonObject.requiredStringOrNull(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    private fun classify(response: PriceObservationHttpResponse): PriceObservationFailureKind {
        if (response.statusCode == 401 || response.statusCode == 403) return PriceObservationFailureKind.AUTHENTICATION
        if (response.statusCode == 408) return PriceObservationFailureKind.NETWORK_TIMEOUT
        if (response.statusCode in 500..599) return PriceObservationFailureKind.SERVER
        val body = response.body.lowercase()
        return when {
            response.statusCode == 409 || body.contains("idempotency key") -> PriceObservationFailureKind.IDEMPOTENCY_MISMATCH
            else -> PriceObservationFailureKind.CONTRACT
        }
    }

    private suspend fun refresh(config: PriceTraceSupabaseConfig): PriceTraceSupabaseConfig? = try {
        if (config.refreshToken.isBlank()) return null
        val response = transport.execute(
            request(
                config = config,
                method = "POST",
                path = "/auth/v1/token?grant_type=refresh_token",
                authenticated = false,
                body = buildJsonObject { put("refresh_token", JsonPrimitive(config.refreshToken)) }.encode(),
            ),
        )
        if (response.statusCode !in 200..299) return null
        val root = json.parseToJsonElement(response.body).jsonObject
        val user = root["user"] as? JsonObject
        store.saveSession(
            userId = (user?.get("id") as? JsonPrimitive)?.contentOrNull ?: config.userId,
            email = (user?.get("email") as? JsonPrimitive)?.contentOrNull ?: config.email,
            accessToken = (root["access_token"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            refreshToken = (root["refresh_token"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        ).getOrNull()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun request(
        config: PriceTraceSupabaseConfig,
        method: String,
        path: String,
        authenticated: Boolean = true,
        body: String? = null,
    ): PriceObservationHttpRequest = PriceObservationHttpRequest(
        method = method,
        url = config.url.trimEnd('/') + path,
        headers = buildMap {
            put("apikey", config.publishableKey)
            put("Accept", "application/json")
            if (authenticated) put("Authorization", "Bearer ${config.accessToken}")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
        },
        body = body,
    )

    private fun JsonElement.encode(): String = json.encodeToString(JsonElement.serializer(), this)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal class PriceTraceCanonicalProjectionSubmitter(
    private val gateway: PriceTraceCanonicalGateway,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        val envelope = request.envelope ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        return when (request.projection) {
            IngestionProjection.PRICETRACE_RECEIPT,
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> {
                val receipt = envelope.receipt
                    ?: return ProjectionSubmission.Failure("receipt_artifact_missing", retryable = false)
                when (val result = gateway.submitVerifiedReceipt(request.idempotencyKey, receipt)) {
                    is PriceTraceCanonicalOutcome.Success -> {
                        // Parse at the authority boundary; the raw response remains durable metadata.
                        try {
                            PriceTraceIdentityJson.decode(result.response)
                        } catch (_: Exception) {
                            return ProjectionSubmission.Failure(
                                "pricetrace_identity_invalid",
                                retryable = false,
                            )
                        }
                        val observationUploaded = result.response.hasCompleteObservations(receipt)
                        ProjectionSubmission.Success(
                            remoteId = result.response.requiredId("receiptId"),
                            metadataJson = result.response.encode(),
                            alsoUploaded = when (request.projection) {
                                IngestionProjection.PRICETRACE_RECEIPT -> if (observationUploaded) {
                                    setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
                                } else {
                                    emptySet()
                                }
                                IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> setOf(IngestionProjection.PRICETRACE_RECEIPT)
                                else -> emptySet()
                            },
                            primaryUploaded = request.projection != IngestionProjection.PRICETRACE_PRICE_OBSERVATION || observationUploaded,
                            primaryPendingReason = if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION && !observationUploaded) {
                                "price_observation_incomplete"
                            } else {
                                null
                            },
                        )
                    }
                    is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
                        message = result.message ?: result.kind.name,
                        retryable = result.kind.retryable,
                    )
                }
            }
            IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE -> {
                val merchant = envelope.merchantCandidate
                    ?: return ProjectionSubmission.Failure("merchant_candidate_missing", retryable = false)
                when (val result = gateway.submitMerchantCandidate(request.idempotencyKey, merchant)) {
                    is PriceTraceCanonicalOutcome.Success -> ProjectionSubmission.Success(
                        remoteId = result.response.requiredId("candidateId"),
                        metadataJson = result.response.encode(),
                    )
                    is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
                        message = result.message ?: result.kind.name,
                        retryable = result.kind.retryable,
                    )
                }
            }
            else -> ProjectionSubmission.Failure("unsupported_pricetrace_projection", retryable = false)
        }
    }

    private fun JsonObject.requiredId(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("PriceTrace response is missing $key")

    private fun JsonObject.hasCompleteObservations(receipt: ReceiptV2): Boolean {
        val observationIds = (this["observationIds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
        if (observationIds.isEmpty()) return false

        val lineResults = (this["lines"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: return false
        val expectedLineIds = receipt.lineItems.map { it.id }.toSet()
        val returnedLineIds = lineResults.mapNotNull { it.stringField("sourceLineId") }.toSet()
        if (lineResults.size != receipt.lineItems.size || returnedLineIds != expectedLineIds) return false

        val observationLines = lineResults.filter { it.stringField("resolutionStatus") != "semantic_only" }
        return observationLines.isNotEmpty() && observationLines.all { line ->
            val observationId = line.stringField("observationId")
                ?: line.stringField("restaurantObservationId")
            observationId != null && observationId in observationIds
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    private fun JsonObject.encode(): String = Json.encodeToString(JsonObject.serializer(), this)
}
