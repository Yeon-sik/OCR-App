package com.pricetrace.receiptocr.fitness

import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.ProductCandidate
import com.pricetrace.receiptscanner.ingestion.ProductCandidateBarcode
import com.pricetrace.receiptscanner.ingestion.ProductCandidateEvidence
import com.pricetrace.receiptscanner.ingestion.PriceTraceProductIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionIdentity
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.SourceAttachment
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessProductNutritionLinkProjectionSubmitterTest {
    @Test
    fun receiptLinksExistingNutritionToRawProductWithoutCatalogueAndRequiresOcrApproval() = runTest {
        val receipt = com.pricetrace.receiptscanner.export.ReceiptV2Json.decode(
            java.io.File("../examples/receipt.v2.example.json").readText())
        val lineId = receipt.lineItems.first().id
        val identity = com.pricetrace.receiptscanner.ingestion.PriceTraceIdentity("receipt-1", "store-1", lines = listOf(
            com.pricetrace.receiptscanner.ingestion.PriceTraceLineIdentity(sourceLineId = lineId,
                receiptItemId = "receipt-item-1", productId = "product-raw-1", storeProductId = "store-product-1")))
        val verified = request(ProjectionIdentity(priceTrace = identity),
            """[{"nutrition_food_id":"food-1","canonical_import_id":"import-1","input_contract":"nutrition-label.v1"}]""")
            .copy(envelope = envelope().copy(receipt = receipt), receiptUserVerified = true)
        val transport = QueueTransport(NutritionHttpResponse(200,
            """{"id":"link-1","canonical_import_id":"import-1","nutrition_food_id":"food-1","store_product_id":"store-product-1","product_id":"product-raw-1","status":"linked"}"""))
        val submitter = FitnessProductNutritionLinkProjectionSubmitter(NutritionSupabaseGateway(FakeStore(signedIn()), transport),
            PriceTraceProductRevisionReader { error("Raw receipt product must not read catalogue") })
        val unverified = submitter.submit(verified.copy(receiptUserVerified = false)) as ProjectionSubmission.Failure
        assertTrue(unverified.requiresReview)
        assertTrue(transport.requests.isEmpty())
        assertEquals("link-1", (submitter.submit(verified) as ProjectionSubmission.Success).remoteId)
        assertEquals(1, transport.requests.size)
        val sent = Json.parseToJsonElement(transport.requests.single().body!!).jsonObject
        val source = sent["p_pricetrace_identity"]!!.jsonObject
        assertEquals("store-product-1", source["store_product_id"]!!.jsonPrimitive.content)
        assertEquals("single_verified_product", source["binding_basis"]!!.jsonPrimitive.content)
        assertFalse(source.containsKey("catalog_product_id"))
        assertTrue(transport.requests.single().url.endsWith("/attach_verified_receipt_product_nutrition_v1"))
        val ambiguousReceipt = receipt.copy(lineItems = receipt.lineItems + receipt.lineItems.first().copy(id = "second-product"))
        val ambiguous = submitter.submit(verified.copy(envelope = verified.envelope!!.copy(receipt = ambiguousReceipt))) as ProjectionSubmission.Failure
        assertTrue(ambiguous.message.contains("receipt_nutrition_binding_requires_ocr_review"))
        assertEquals(1, transport.requests.size)
        val explicit = (verified.envelope!!.nutrition.single() as IngestionNutrition.ProductLabel).copy(lineId = lineId)
        assertEquals("explicit_line", VerifiedReceiptProductNutrition.identity(verified.copy(
            envelope = verified.envelope!!.copy(receipt = ambiguousReceipt, nutrition = listOf(explicit))), explicit)["binding_basis"]!!.jsonPrimitive.content)
    }

    @Test
    fun receiptNutritionResponseCannotSubstituteProductIdentity() = runTest {
        val identity = kotlinx.serialization.json.buildJsonObject { put("store_product_id", kotlinx.serialization.json.JsonPrimitive("store-product-1")); put("product_id", kotlinx.serialization.json.JsonPrimitive("product-1")) }
        val transport = QueueTransport(NutritionHttpResponse(200,
            """{"id":"link-1","canonical_import_id":"import-1","nutrition_food_id":"food-1","store_product_id":"store-product-1","product_id":"different-product","status":"linked"}"""))
        val result = NutritionSupabaseGateway(FakeStore(signedIn()), transport).attachVerifiedReceiptProductNutrition(
            VerifiedReceiptProductNutritionPayload("import-1", "food-1", identity)) as VerifiedReceiptProductNutritionOutcome.Failure
        assertEquals(NutritionGatewayFailure.CONTRACT, result.reason)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun resolvedProductAndNutritionFoodProduceARevisionBoundLinkProposal() = runTest {
        val revision = "sha256:" + "b".repeat(64)
        val readIds = mutableListOf<String>()
        val transport = QueueTransport(
            NutritionHttpResponse(
                200,
                """[{"id":"link-1","action":"link","identity":{"namespace":"pricetrace","catalogProductId":"catalog-1","nutritionFoodId":"food-1"},"status":"pending","sourceRevision":"$revision"}]""",
            ),
        )
        val submitter = FitnessProductNutritionLinkProjectionSubmitter(
            nutritionGateway = NutritionSupabaseGateway(FakeStore(signedIn()), transport),
            productRevisionReader = PriceTraceProductRevisionReader { catalogProductId ->
                readIds += catalogProductId
                ProductRevisionReadOutcome.Success(ProductRevisionReadResult(revision))
            },
        )

        val result = submitter.submit(
            request(
                resolvedIdentity = ProjectionIdentity(
                    productCandidates = mapOf(
                        "product-1" to PriceTraceProductIdentity(
                            candidateClientKey = "product-1",
                            catalogProductId = "catalog-1",
                            productRevision = null,
                        ),
                    ),
                ),
                dependencyMetadata = """[{"nutrition_food_id":"food-1","canonical_import_id":"nutrition-1"}]""",
            ),
        )

        val success = result as ProjectionSubmission.Success
        assertEquals("link-1", success.remoteId)
        assertEquals(listOf("catalog-1"), readIds)
        val request = transport.requests.single()
        assertEquals(
            "https://nutrition.example.com/rest/v1/rpc/propose_product_nutrition_link_v1",
            request.url,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
        assertEquals("catalog-1", body["p_catalog_product_id"]?.jsonPrimitive?.content)
        assertEquals("food-1", body["p_nutrition_food_id"]?.jsonPrimitive?.content)
        assertEquals(revision, body["p_source_revision"]?.jsonPrimitive?.content)
        assertEquals("product-1", body["p_source"]!!.jsonObject["candidateClientKey"]?.jsonPrimitive?.content)
        assertEquals("yeonsik-ocr.v2", body["p_source"]!!.jsonObject["sourceSchema"]?.jsonPrimitive?.content)
    }

    @Test
    fun unresolvedProductRevisionStaysRetryableAndDoesNotSubmitLink() = runTest {
        val transport = QueueTransport()
        val submitter = FitnessProductNutritionLinkProjectionSubmitter(
            nutritionGateway = NutritionSupabaseGateway(FakeStore(signedIn()), transport),
            productRevisionReader = PriceTraceProductRevisionReader {
                ProductRevisionReadOutcome.Failure(NutritionGatewayFailure.NETWORK, "product read temporarily unavailable")
            },
        )

        val result = submitter.submit(
            request(
                resolvedIdentity = ProjectionIdentity(
                    productCandidates = mapOf(
                        "product-1" to PriceTraceProductIdentity("product-1", "catalog-1"),
                    ),
                ),
                dependencyMetadata = """[{"nutrition_food_id":"food-1"}]""",
            ),
        )

        val failure = result as ProjectionSubmission.Failure
        assertEquals("product read temporarily unavailable", failure.message)
        assertTrue(failure.retryable)
        assertFalse(transport.requests.isNotEmpty())
    }

    private fun request(
        resolvedIdentity: ProjectionIdentity,
        dependencyMetadata: String,
    ) = ProjectionRequest(
        ingestionId = "ingestion-product-link",
        projection = IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
        canonicalPayload = "{}",
        resolvedIdentity = resolvedIdentity,
        idempotencyKey = "product-link-key",
        envelope = envelope(),
        localDocumentId = "local-product",
        revisionSeq = 3,
        canonicalFingerprint = "c".repeat(64),
        dependencyMetadataJson = mapOf(IngestionProjection.FITNESS_NUTRITION to dependencyMetadata),
    )

    private fun envelope() = YeonsikOcrEnvelope(
        mode = IngestionMode.PACKAGED_PRODUCT,
        schemaVersion = YEONSIK_OCR_V2_SCHEMA,
        source = IngestionSource(
            producer = "chatgpt",
            sourceFiles = listOf(SourceAttachment("product-photo-1", SourceAttachmentType.PRODUCT_PHOTO)),
        ),
        productCandidates = listOf(
            ProductCandidate(
                clientKey = "product-1",
                productName = "Test cereal",
                brand = "Test brand",
                manufacturer = "Test Foods",
                specification = "Original",
                contentAmount = 500.0,
                contentUnit = "g",
                packageCount = 1,
                variant = "Original",
                barcodes = listOf(ProductCandidateBarcode(type = "ean13", value = "8801234567890")),
                evidence = listOf(
                    ProductCandidateEvidence(
                        sourceAttachmentIds = listOf("product-photo-1"),
                        source = "product photo",
                        sourceType = "product_photo",
                        sourceRef = "product-photo-1",
                        field = "product_name",
                        observedValue = "Test cereal",
                    ),
                ),
            ),
        ),
        nutrition = listOf(IngestionNutrition.ProductLabel("product-1", verifiedDraft())),
    )

    private fun verifiedDraft() = NutritionLabelDraft(
        documentId = "nutrition-1",
        productName = "Test cereal",
        basisAmount = 100.0,
        basisUnit = "g",
        nutrients = NutritionField.requiredFields.associateWith { 10.0 },
    ).asUserVerified("2026-09-06T08:00:00+09:00")

    private fun signedIn() = NutritionSupabaseConfig(
        url = "https://nutrition.example.com",
        publishableKey = "publishable-key-with-safe-length",
        userId = "user-1",
        email = "fit@example.com",
        accessToken = "access-token",
        refreshToken = "refresh-token",
    )

    private class QueueTransport(vararg responses: NutritionHttpResponse) : NutritionHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<NutritionHttpRequest>()

        override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class FakeStore(initial: NutritionSupabaseConfig) : NutritionSupabaseStore {
        private var config = initial

        override fun read(): NutritionSupabaseConfig = config

        override fun saveConnection(url: String, publishableKey: String): Result<NutritionSupabaseConfig> = runCatching {
            config = NutritionSupabaseConfig(url = url, publishableKey = publishableKey)
            config
        }

        override fun saveSession(
            userId: String,
            email: String,
            accessToken: String,
            refreshToken: String,
        ): Result<NutritionSupabaseConfig> = runCatching {
            config = config.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
            config
        }

        override fun clearSession(): Boolean {
            config = config.copy(userId = "", email = "", accessToken = "", refreshToken = "")
            return true
        }
    }
}
