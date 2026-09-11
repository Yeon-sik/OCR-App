package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Contract
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Json
import com.pricetrace.receiptscanner.publisher.PriceTracePurchaseObservationV4Payload
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.ingestion.ProductCandidateBarcode
import com.pricetrace.receiptscanner.ingestion.StandalonePriceObservation
import com.pricetrace.receiptscanner.ingestion.StandalonePriceObservationKind
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
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
class PriceTraceCanonicalGateway(
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

    /** Publishes receipt-independent v3 price facts through PriceTrace's identity boundary. */
    suspend fun submitStandalonePriceObservations(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
    ): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitStandaloneOnce(idempotencyKey, envelope, initial)
        if (first !is PriceTraceCanonicalOutcome.Failure || first.kind != PriceObservationFailureKind.AUTHENTICATION) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return submitStandaloneOnce(idempotencyKey, envelope, refreshed)
    }

    /** Publishes one or more purchase records through PriceTrace's confirmed V4 RPC. */
    suspend fun submitPurchasePriceObservationsV4(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
        contract: PriceTracePurchaseObservationV4Contract = PriceTracePurchaseObservationV4Contract(),
    ): PriceTraceCanonicalOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) return PriceTraceCanonicalOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val first = submitPurchaseV4Once(idempotencyKey, envelope, initial, contract)
        if (first !is PriceTraceCanonicalOutcome.Failure ||
            first.kind != PriceObservationFailureKind.AUTHENTICATION
        ) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return submitPurchaseV4Once(idempotencyKey, envelope, refreshed, contract)
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

    private suspend fun submitStandaloneOnce(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
        config: PriceTraceSupabaseConfig,
    ): PriceTraceCanonicalOutcome = try {
        require(envelope.priceObservations.isNotEmpty()) { "standalone_price_observation_missing" }
        require(envelope.merchantCandidate != null) { "merchant_candidate_missing" }
        require(envelope.priceObservations.all { it.netAmountMinor != null }) {
            "price_observation_net_amount_required"
        }
        val responses = mutableListOf<JsonObject>()
        envelope.priceObservations.forEach { observation ->
            val observationKey = StableIds.sha256("$idempotencyKey|observation=${observation.clientKey}")
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = "/rest/v1/rpc/ingest_verified_standalone_price_observation_v1",
                    body = buildJsonObject {
                        put("p_idempotency_key", JsonPrimitive(observationKey))
                        put("p_observation", standaloneObservationJson(observation, envelope))
                    }.encode(),
                ),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceCanonicalOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
            }
            responses += decodeStandaloneResponse(response.body)
        }
        PriceTraceCanonicalOutcome.Success(buildJsonObject {
            put("schemaVersion", JsonPrimitive("receipt-independent-price-observation.v3"))
            put("observations", JsonArray(responses))
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

    private suspend fun submitPurchaseV4Once(
        idempotencyKey: String,
        envelope: YeonsikOcrEnvelope,
        config: PriceTraceSupabaseConfig,
        contract: PriceTracePurchaseObservationV4Contract,
    ): PriceTraceCanonicalOutcome = try {
        val records = envelope.purchaseRecords.filter { it.priceObservationEligible }
        require(records.isNotEmpty()) { "purchase_price_observation_missing" }
        val responses = records.map { record ->
            val recordKey = StableIds.sha256("$idempotencyKey|purchase_record=${record.clientKey}")
            val payload = PriceTracePurchaseObservationV4Payload(
                contract = contract,
                purchaseRecord = record,
                verificationBasis = envelope.review.verificationBasis,
            )
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = contract.rpcPath,
                    body = buildJsonObject {
                        put("p_idempotency_key", JsonPrimitive(recordKey))
                        put("p_purchase", payload.toJson())
                    }.encode(),
                ),
            )
            if (response.statusCode !in 200..299) {
                return PriceTraceCanonicalOutcome.Failure(
                    classify(response),
                    response.body.takeIf(String::isNotBlank),
                )
            }
            PriceTracePurchaseObservationV4Json.decodeResponse(response.body)
        }
        PriceTraceCanonicalOutcome.Success(buildJsonObject {
            put("schemaVersion", JsonPrimitive("purchase-price-observation.v4"))
            put("contractVersion", JsonPrimitive("purchase-price.v4"))
            put("sources", JsonArray(responses.map { it.raw }))
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

    private fun standaloneObservationJson(
        observation: StandalonePriceObservation,
        envelope: YeonsikOcrEnvelope,
    ): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive("receipt-independent-price-observation.v3"))
        put("contract_version", JsonPrimitive("price-observation.v3"))
        put("kind", JsonPrimitive(observation.kind.wireValue))
        put("verification_basis", JsonPrimitive(
            when (envelope.review.verificationBasis) {
                com.pricetrace.receiptscanner.ingestion.VerificationBasis.SOURCE_EVIDENCE -> "source_evidence"
                com.pricetrace.receiptscanner.ingestion.VerificationBasis.MANUAL_CANONICAL_REVIEW -> "manual_canonical_review"
            },
        ))
        put("transcription_status", JsonPrimitive("user_verified"))
        observation.observedOn?.let { put("observed_on", JsonPrimitive(it)) }
        observation.observedAt?.let { put("observed_at", JsonPrimitive(it)) }
        put("currency", JsonPrimitive(observation.currency))
        put("gross_price", observation.grossAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("discount", observation.discountAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("net_price", observation.netAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("quantity", observation.quantity?.let { quantity ->
            val expectedUnit = when (observation.kind) {
                StandalonePriceObservationKind.RETAIL_PURCHASE -> "each"
                StandalonePriceObservationKind.RESTAURANT_PURCHASE -> "serving"
            }
            require(quantity.unit == expectedUnit) {
                "PriceTrace standalone quantity unit must be $expectedUnit"
            }
            JsonPrimitive(quantity.value.toLong())
        } ?: JsonNull)
        put("unit_price", observation.unitPriceAmountMinor?.let(::JsonPrimitive) ?: JsonNull)
        put("merchant", merchantObservationJson(envelope, observation.kind))
        when (observation.kind) {
            StandalonePriceObservationKind.RETAIL_PURCHASE -> {
                val candidate = envelope.productCandidates.singleOrNull {
                    it.clientKey == observation.productClientKey
                } ?: error("retail_product_candidate_missing")
                put("product", retailProductObservationJson(candidate))
            }
            StandalonePriceObservationKind.RESTAURANT_PURCHASE -> {
                put("item", buildJsonObject {
                    put("item_name", JsonPrimitive(requireNotNull(observation.itemName)))
                    put("serving_label", JsonPrimitive("1회 제공"))
                    put("category_label", JsonPrimitive("restaurant"))
                })
            }
        }
    }

    private fun merchantObservationJson(
        envelope: YeonsikOcrEnvelope,
        kind: StandalonePriceObservationKind,
    ): JsonObject {
        val merchant = requireNotNull(envelope.merchantCandidate)
        return buildJsonObject {
            put("merchant_name", JsonPrimitive(merchant.name))
            merchant.branchName?.let { put("branch_name", JsonPrimitive(it)) }
            merchant.sourceNamespace?.let { put("source_namespace", JsonPrimitive(it)) }
            merchant.sourceLocationCode?.let {
                put(
                    if (kind == StandalonePriceObservationKind.RETAIL_PURCHASE) "source_code" else "source_location_code",
                    JsonPrimitive(it),
                )
            }
            merchant.businessRegistrationNumber?.let { put("business_registration_number", JsonPrimitive(it)) }
            merchant.address?.let { put("address", JsonPrimitive(it)) }
            merchant.phone?.let { put("phone", JsonPrimitive(it)) }
        }
    }

    private fun retailProductObservationJson(candidate: ProductCandidate): JsonObject = buildJsonObject {
        put("product_client_key", JsonPrimitive(candidate.clientKey))
        put("merchant_sku", candidate.merchantSku?.let(::JsonPrimitive) ?: JsonNull)
        put("product_name", JsonPrimitive(candidate.productName))
        put("brand", candidate.brand?.let(::JsonPrimitive) ?: JsonNull)
        put("sub_brand", candidate.subBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", candidate.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("specification", candidate.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("variant", candidate.variant?.let(::JsonPrimitive) ?: JsonNull)
        put("identifiers", JsonArray(candidate.barcodes.map { barcode -> buildJsonObject {
            put("scheme", JsonPrimitive(priceTraceIdentifierScheme(barcode)))
            put("value", JsonPrimitive(barcode.value.filterNot { it == ' ' || it == '-' }))
        }}))
    }

    private fun productCandidateJson(candidate: ProductCandidate): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive("PRICETRACE_PRODUCT_CANDIDATE"))
        put("contract_version", JsonPrimitive("product-candidate.v1"))
        put("source_app", JsonPrimitive("pricetrace_ocr_app"))
        put("client_key", JsonPrimitive(candidate.clientKey))
        put("sub_brand", candidate.subBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("source_version", candidate.sourceVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("candidate_type", JsonPrimitive(candidate.candidateType))
        put("product_name", JsonPrimitive(candidate.productName))
        put("brand", candidate.brand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", candidate.manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("specification", candidate.specification?.let(::JsonPrimitive) ?: JsonNull)
        put("content_amount", candidate.contentAmount?.let(::JsonPrimitive) ?: JsonNull)
        put("content_unit", candidate.contentUnit?.let(::JsonPrimitive) ?: JsonNull)
        put("package_count", candidate.packageCount?.let(::JsonPrimitive) ?: JsonNull)
        put("variant", candidate.variant?.let(::JsonPrimitive) ?: JsonNull)
        put("identifiers", JsonArray(candidateIdentifiers(candidate)))
        put("evidence", JsonArray(candidate.evidence.map { evidence ->
            val sourceRef = evidence.sourceRef?.takeIf(String::isNotBlank)
                ?: evidence.source?.takeIf(String::isNotBlank)
                ?: evidence.sourceAttachmentIds.firstOrNull()
                ?: error("product candidate evidence source_ref is required")
            buildJsonObject {
                put("source_type", JsonPrimitive(evidence.sourceType))
                put("source_ref", JsonPrimitive(sourceRef))
                put("field", JsonPrimitive(evidence.field))
                put("observed_value", evidence.observedValue?.let(::JsonPrimitive) ?: JsonNull)
                put("content_hash", evidence.contentHash?.let(::JsonPrimitive) ?: JsonNull)
            }
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
        return candidate.barcodes.map { barcode -> buildJsonObject {
            put("scheme", JsonPrimitive(priceTraceIdentifierScheme(barcode)))
            put("value", JsonPrimitive(barcode.value))
        }}
    }

    /** Convert the Project's barcode observation type to PriceTrace's existing identifier scheme. */
    private fun priceTraceIdentifierScheme(barcode: ProductCandidateBarcode): String =
        when (barcode.type.lowercase().replace("-", "").replace("_", "")) {
            "ean", "ean8", "ean13" -> "ean"
            "upc", "upca", "upce" -> "upc"
            "gtin", "gtin8", "gtin12", "gtin13", "gtin14", "barcode" -> "gtin"
            else -> when (barcode.value.filterNot { it == ' ' || it == '-' }.length) {
                8, 13 -> "ean"
                12 -> "upc"
                14 -> "gtin"
                else -> error("unsupported product candidate barcode type: ${barcode.type}")
            }
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

    private fun decodeStandaloneResponse(value: String): JsonObject {
        val element = json.parseToJsonElement(value)
        val row = when (element) {
            is JsonObject -> element
            else -> element.jsonArray.single().jsonObject
        }
        require(row.requiredStringOrNull("observationId", "observation_id", "priceObservationId", "id") != null) {
            "PriceTrace standalone response is missing observationId"
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

class PriceTraceCanonicalProjectionSubmitter(
    private val gateway: PriceTraceCanonicalGateway,
) : IngestionProjectionSubmitter {
    override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
        val envelope = request.envelope ?: return ProjectionSubmission.Failure("canonical_envelope_missing", retryable = false)
        return when (request.projection) {
            IngestionProjection.PRICETRACE_RECEIPT,
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION -> {
                if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION &&
                    envelope.purchaseRecords.isNotEmpty()
                ) {
                    if (envelope.review.status != com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus.READY) {
                        return ProjectionSubmission.Failure("canonical_review_required", retryable = false)
                    }
                    return when (val result = gateway.submitPurchasePriceObservationsV4(
                        request.idempotencyKey,
                        envelope,
                    )) {
                        is PriceTraceCanonicalOutcome.Success -> {
                            val remoteId = (result.response["sources"] as? JsonArray)
                                ?.firstOrNull()
                                ?.let { it as? JsonObject }
                                ?.purchaseSourceId()
                                ?: return ProjectionSubmission.Failure(
                                    "pricetrace_purchase_source_identity_invalid",
                                    retryable = false,
                                )
                            ProjectionSubmission.Success(
                                remoteId = remoteId,
                                metadataJson = result.response.encode(),
                            )
                        }
                        is PriceTraceCanonicalOutcome.Failure -> ProjectionSubmission.Failure(
                            message = result.message ?: result.kind.name,
                            retryable = result.kind.retryable,
                        )
                    }
                }
                if (request.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION &&
                    envelope.priceObservations.isNotEmpty()
                ) {
                    if (envelope.priceObservations.any { it.netAmountMinor == null }) {
                        return ProjectionSubmission.Failure(
                            "price_observation_net_amount_required",
                            retryable = false,
                        )
                    }
                    if (envelope.review.status != com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus.READY) {
                        return ProjectionSubmission.Failure("canonical_review_required", retryable = false)
                    }
                    when (val result = gateway.submitStandalonePriceObservations(request.idempotencyKey, envelope)) {
                        is PriceTraceCanonicalOutcome.Success -> {
                            val observations = (result.response["observations"] as? JsonArray).orEmpty()
                            val remoteId = observations.firstOrNull()?.let {
                                (it as? JsonObject)?.standaloneId()
                            } ?: return ProjectionSubmission.Failure("pricetrace_observation_identity_invalid", retryable = false)
                            return ProjectionSubmission.Success(remoteId = remoteId, metadataJson = result.response.encode())
                        }
                        is PriceTraceCanonicalOutcome.Failure -> return ProjectionSubmission.Failure(
                            message = result.message ?: result.kind.name,
                            retryable = result.kind.retryable,
                        )
                    }
                }
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

    private fun JsonObject.standaloneId(): String? = listOf(
        "observationId", "observation_id", "priceObservationId", "id",
    ).firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    private fun JsonObject.purchaseSourceId(): String? =
        (this["purchaseSourceId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

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
