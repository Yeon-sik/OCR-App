package com.pricetrace.receiptocr

import com.pricetrace.receiptscanner.ingestion.CanonicalIngestionUseCase
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionProjectionSubmitter
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.MerchantCandidate
import com.pricetrace.receiptscanner.ingestion.ProjectionRequest
import com.pricetrace.receiptscanner.ingestion.ProjectionSubmission
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.ingestion.SourceAttachment
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V4_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV4Json
import com.pricetrace.receiptscanner.review.ReviewDestination
import com.pricetrace.receiptscanner.review.ReviewDestinationStatus
import com.pricetrace.receiptscanner.review.ReviewViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveCheckpoint
import com.pricetrace.receiptscanner.ingestion.EvidenceArchivePort
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveRequest
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveResult
import com.pricetrace.receiptscanner.ingestion.EvidenceVerificationEventResult
import com.pricetrace.receiptscanner.ingestion.YEONSIK_BUNDLE_VERSION
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleManifest
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleManifestCodec

class AndroidCanonicalJsonValidatorTest {
    @Test
    fun `android review derives PriceTrace status and reason per V4 purchase record`() = runBlocking {
        val source = YeonsikOcrV4Json.decode(
            readExample("yeonsik-ocr.v4.purchase.example.json"),
            "android-mixed-review",
        )
        val compatible = source.purchaseRecords.single().copy(clientKey = "purchase-compatible")
        val incompatible = compatible.copy(
            clientKey = "purchase-incompatible",
            totals = compatible.totals.copy(
                subtotalAmountKrw = 500,
                grandTotalAmountKrw = 500,
                paidAmountKrw = 500,
            ),
            lineItems = compatible.lineItems.map { line ->
                line.copy(
                    quantity = 0.5,
                    unitPriceAmountKrw = 1000,
                    grossAmountKrw = 500,
                    netAmountKrw = 500,
                )
            },
        )
        val canonical = YeonsikOcrV4Json.encode(
            source.copy(purchaseRecords = listOf(compatible, incompatible)),
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(InMemoryIngestionSessionStore()),
            newLocalDocumentId = { "android-mixed-review-document" },
            newIngestionId = { "android-mixed-review-ingestion" },
        )

        val state = validator.importJson(canonical, AndroidCanonicalJsonValidatorState())
        assertTrue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in requireNotNull(state.plan).eligible)
        assertTrue(IngestionProjection.CASHOS_TRANSACTION in requireNotNull(state.plan).eligible)
        assertFalse(canonical.contains("pricetrace_v4_"))
        val model = ReviewViewModel.fromCanonical(
            envelope = requireNotNull(state.envelope),
            selectedProjections = state.selectedProjections,
        )
        fun badge(recordKey: String) = model.rows
            .single { it.id == "purchase:$recordKey:플랫폼" }
            .destinations.single { it.destination == ReviewDestination.PRICE_TRACE }

        assertEquals(ReviewDestinationStatus.PLANNED, badge("purchase-compatible").status)
        assertEquals(ReviewDestinationStatus.CONDITION_UNMET, badge("purchase-incompatible").status)
        assertEquals(
            "pricetrace_v4_quantity_positive_integer_required",
            badge("purchase-incompatible").reason,
        )
        assertTrue(badge("purchase-incompatible").projectionStatuses.isEmpty())
    }

    @Test
    fun `android v2 merchant bundles distinguish text evidence missing evidence and image evidence`() = runBlocking {
        fun validator() = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(InMemoryIngestionSessionStore()),
            evidenceArchivePort = SuccessfulArchivePort(),
            bundleRoot = Files.createTempDirectory("android-v2-merchant-bundle").toFile(),
        )

        val textValidator = validator()
        var textState = textValidator.importBundle(
            ByteArrayInputStream(textOnlyBundle(v2MerchantCanonical(userText = "텍스트로 상호를 확인했습니다."))),
            "merchant-text.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals(YEONSIK_OCR_V2_SCHEMA, textState.envelope?.schemaVersion)
        assertTrue(textState.evidence.isEmpty())
        assertEquals(AndroidEvidenceArchiveStatus.ARCHIVED, textState.bundle?.archiveStatus)
        textState = textValidator.confirm(textState)
        assertEquals(IngestionReviewStatus.READY, textState.envelope?.review?.status)
        assertTrue(textState.error == null)

        val missingValidator = validator()
        var missingState = missingValidator.importBundle(
            ByteArrayInputStream(textOnlyBundle(v2MerchantCanonical(userText = null))),
            "merchant-missing-evidence.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        missingState = missingValidator.confirm(missingState)
        assertTrue(missingState.error.orEmpty().contains("source_image_required"))
        assertTrue(missingState.envelope?.review?.status != IngestionReviewStatus.READY)

        val imageValidator = validator()
        var imageState = imageValidator.importBundle(
            ByteArrayInputStream(
                evidenceBundle(
                    canonical = v2MerchantCanonical(
                        userText = null,
                        sourceFiles = listOf(SourceAttachment("merchant-receipt-1", SourceAttachmentType.RECEIPT)),
                        sourceAttachmentIds = listOf("merchant-receipt-1"),
                    ),
                    sourceFileId = "merchant-receipt-1",
                    type = SourceAttachmentType.RECEIPT,
                ),
            ),
            "merchant-image.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals(listOf("merchant-receipt-1"), imageState.evidence.map { it.attachmentId })
        imageState = imageValidator.confirm(imageState)
        assertEquals(IngestionReviewStatus.READY, imageState.envelope?.review?.status)
        assertTrue(imageState.error == null)
    }

    @Test
    fun `invalid Android bundle clears active canonical state and blocks confirmation`() = runBlocking {
        val validator = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(InMemoryIngestionSessionStore()),
            bundleRoot = Files.createTempDirectory("android-invalid-bundle-test").toFile(),
        )
        val state = validator.importBundle(
            ByteArrayInputStream("not-a-zip".toByteArray()),
            "broken.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals(AndroidBundleValidationStatus.INVALID, state.bundleValidationStatus)
        assertTrue(state.envelope == null)
        assertTrue(runCatching { validator.confirm(state) }.exceptionOrNull()?.message?.contains("Bundle is invalid.") == true)
    }

    @Test
    fun `android bundle import defaults to source evidence and archives before confirm`() = runBlocking {
        val archive = SuccessfulArchivePort()
        val validator = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(InMemoryIngestionSessionStore()),
            evidenceArchivePort = archive,
            bundleRoot = Files.createTempDirectory("android-bundle-test").toFile(),
            newLocalDocumentId = { "android-bundle-document" },
            newIngestionId = { "android-bundle-ingestion" },
        )
        val canonical = readExample("yeonsik-ocr.v4.purchase.text-only.example.json")
        var state = validator.importBundle(
            ByteArrayInputStream(textOnlyBundle(canonical)),
            "purchase.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals(VerificationBasis.SOURCE_EVIDENCE, state.verificationBasis)
        assertEquals(AndroidBundleValidationStatus.VALID, state.bundle?.validationStatus)
        assertEquals(AndroidEvidenceArchiveStatus.ARCHIVED, state.bundle?.archiveStatus)
        assertFalse(state.bundle?.verificationEventRecorded ?: true)

        state = validator.confirm(state)
        assertTrue(state.bundle?.verificationEventRecorded == true)
        assertEquals(IngestionReviewStatus.READY, state.envelope?.review?.status)
    }

    @Test
    fun `android bundle recovery restores manifest paths checkpoint and verification event`() = runBlocking {
        val sessionStore = InMemoryIngestionSessionStore()
        val recoveryStore = InMemoryAndroidBundleStateStore()
        val archive = SuccessfulArchivePort()
        val bundleRoot = Files.createTempDirectory("android-bundle-recovery").toFile()
        val validator = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(sessionStore),
            evidenceArchivePort = archive,
            bundleRoot = bundleRoot,
            newLocalDocumentId = { "android-recovery-document" },
            newIngestionId = { "android-recovery-ingestion" },
            bundleStateStore = recoveryStore,
        )
        var state = validator.importBundle(
            ByteArrayInputStream(evidenceBundle(readExample("yeonsik-ocr.v3.restaurant.example.json"))),
            "recovery.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        state = validator.confirm(state)
        assertTrue(state.bundle?.verificationEventRecorded == true)

        val restarted = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(sessionStore),
            evidenceArchivePort = archive,
            bundleRoot = bundleRoot,
            bundleStateStore = recoveryStore,
        ).restoreBundleState()

        assertNotNull(restarted)
        assertEquals(state.bundle?.manifestJson, restarted?.bundle?.manifestJson)
        assertEquals(state.bundle?.evidencePaths, restarted?.bundle?.evidencePaths)
        assertTrue(restarted?.bundle?.evidencePaths?.values?.all { File(it).isFile } == true)
        assertEquals(state.bundle?.archiveCheckpoint, restarted?.bundle?.archiveCheckpoint)
        assertTrue(restarted?.bundle?.verificationEventRecorded == true)
        assertEquals(state.ingestionId, restarted?.ingestionId)
    }

    @Test
    fun `duplicate bundle restores its durable record instead of the active record`() = runBlocking {
        val sessionStore = InMemoryIngestionSessionStore()
        val recoveryStore = InMemoryAndroidBundleStateStore()
        val bundleRoot = Files.createTempDirectory("android-bundle-duplicate-recovery").toFile()
        var sequence = 0
        val validator = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(sessionStore),
            evidenceArchivePort = SuccessfulArchivePort(),
            bundleRoot = bundleRoot,
            newLocalDocumentId = { "android-duplicate-document-${++sequence}" },
            newIngestionId = { "android-duplicate-ingestion-$sequence" },
            bundleStateStore = recoveryStore,
        )
        val canonical = readExample("yeonsik-ocr.v3.restaurant.example.json")
        val first = validator.importBundle(
            ByteArrayInputStream(evidenceBundle(canonical)),
            "first.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        val second = validator.importBundle(
            ByteArrayInputStream(evidenceBundle(canonical, "alternate")),
            "second.yeonsik",
            first,
        )
        val firstIngestionId = requireNotNull(first.ingestionId) { "first import failed: ${first.error}" }
        val secondIngestionId = requireNotNull(second.ingestionId) { "second import failed: ${second.error}" }
        assertTrue(firstIngestionId != secondIngestionId)
        assertEquals(secondIngestionId, recoveryStore.loadActive()?.ingestionId)

        val duplicate = validator.importBundle(
            ByteArrayInputStream(evidenceBundle(canonical)),
            "duplicate.yeonsik",
            second,
        )

        assertEquals(firstIngestionId, duplicate.ingestionId)
        assertEquals("first.yeonsik", duplicate.bundle?.sourceName)
        assertEquals(first.bundle?.evidencePaths, duplicate.bundle?.evidencePaths)
        assertEquals(firstIngestionId, recoveryStore.load(firstIngestionId)?.ingestionId)
    }

    @Test
    fun `completed bundle keeps its durable record but is removed from active recovery`() = runBlocking {
        val sessionStore = InMemoryIngestionSessionStore()
        val recoveryStore = InMemoryAndroidBundleStateStore()
        val archive = SuccessfulArchivePort()
        val useCase = CanonicalIngestionUseCase(
            store = sessionStore,
            submitters = mapOf(
                IngestionProjection.CASHOS_TRANSACTION to RecordingSubmitter(mutableListOf()),
            ),
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            evidenceArchivePort = archive,
            bundleRoot = Files.createTempDirectory("android-bundle-completed").toFile(),
            newLocalDocumentId = { "android-completed-document" },
            newIngestionId = { "android-completed-ingestion" },
            bundleStateStore = recoveryStore,
        )
        var state = validator.importBundle(
            ByteArrayInputStream(textOnlyBundle(readExample("yeonsik-ocr.v4.purchase.text-only.example.json"))),
            "completed.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        state = validator.confirm(state)
        state = validator.submit(state)

        val ingestionId = requireNotNull(state.ingestionId)
        assertEquals(null, recoveryStore.loadActive())
        assertNotNull(recoveryStore.load(ingestionId))
        assertEquals(
            com.pricetrace.receiptscanner.ingestion.ProjectionStatus.UPLOADED,
            state.session?.projections?.single { it.projection == IngestionProjection.CASHOS_TRANSACTION }?.status,
        )
    }

    @Test
    fun `bundle parse stays locked while standalone JSON starts a new ingestion`() = runBlocking {
        val sessionStore = InMemoryIngestionSessionStore()
        val recoveryStore = InMemoryAndroidBundleStateStore()
        var ingestionSequence = 0
        val validator = AndroidCanonicalJsonValidator(
            useCase = CanonicalIngestionUseCase(sessionStore),
            evidenceArchivePort = SuccessfulArchivePort(),
            bundleRoot = Files.createTempDirectory("android-bundle-isolation").toFile(),
            newLocalDocumentId = { "android-isolation-document-${++ingestionSequence}" },
            newIngestionId = { "android-isolation-ingestion-${ingestionSequence}" },
            bundleStateStore = recoveryStore,
        )
        val bundleState = validator.importBundle(
            ByteArrayInputStream(textOnlyBundle(readExample("yeonsik-ocr.v4.purchase.text-only.example.json"))),
            "isolation.yeonsik",
            AndroidCanonicalJsonValidatorState(),
        )
        val blocked = validator.importJson(
            bundleState.rawJson,
            bundleState,
        )
        assertEquals(bundleState.ingestionId, blocked.ingestionId)
        assertNotNull(blocked.bundle)
        assertTrue(blocked.error.orEmpty().contains("read-only"))

        val standalone = validator.importJson(
            bundleState.rawJson,
            blocked,
            startNewIngestion = true,
        )
        assertEquals(null, standalone.bundle)
        assertTrue(standalone.ingestionId != bundleState.ingestionId)
        assertEquals(null, standalone.session?.bundleFingerprint)
        assertNotNull(sessionStore.get(bundleState.ingestionId!!))
    }
    @Test
    fun `android validator uses the same core plan and confirms a no image manual review`() = runBlocking {
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            now = { "2026-09-08T00:00:00Z" },
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            newLocalDocumentId = { "android-parity-document" },
            newIngestionId = { "android-parity-ingestion" },
        )

        val imported = validator.importJson(merchantJson(), AndroidCanonicalJsonValidatorState())
        val envelope = requireNotNull(imported.envelope)
        val plan = requireNotNull(imported.plan)
        assertEquals(CanonicalProjectionPlanner.plan(envelope), plan)
        assertEquals(setOf(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE), plan.eligible)
        assertEquals(plan.eligible, imported.selectedProjections)
        val draftReview = Json.parseToJsonElement(imported.canonicalJson)
            .jsonObject["review"]!!.jsonObject
        assertEquals(setOf("status", "blocking_issues", "warnings"), draftReview.keys)
        assertFalse(imported.canonicalJson.contains("verification_basis"))

        val confirmed = validator.confirm(imported)
        assertEquals(IngestionReviewStatus.READY, confirmed.envelope?.review?.status)
        assertEquals(VerificationBasis.MANUAL_CANONICAL_REVIEW, confirmed.envelope?.review?.verificationBasis)
        val persistedReview = Json.parseToJsonElement(confirmed.canonicalJson)
            .jsonObject["review"]!!.jsonObject
        assertTrue(persistedReview.containsKey("verification_basis"))
        assertTrue(persistedReview.containsKey("user_verified"))
        assertNotNull(confirmed.session)
        assertTrue(confirmed.error == null)
    }

    @Test
    fun `android text-only manual review submits product before standalone price`() = runBlocking {
        val order = mutableListOf<IngestionProjection>()
        val product = RecordingSubmitter(order)
        val price = RecordingSubmitter(order)
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to product,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to price,
            ),
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            newLocalDocumentId = { "android-text-only-document" },
            newIngestionId = { "android-text-only-ingestion" },
        )

        var state = validator.importJson(
            readExample("yeonsik-ocr.v3.text-only-retail.example.json"),
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals(IngestionReviewStatus.NEEDS_REVIEW, state.envelope?.review?.status)
        assertEquals(
            setOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            ),
            state.plan?.eligible,
        )

        state = validator.confirm(state)
        assertEquals(IngestionReviewStatus.READY, state.envelope?.review?.status)
        assertEquals(VerificationBasis.MANUAL_CANONICAL_REVIEW, state.envelope?.review?.verificationBasis)

        state = validator.submit(state)
        assertEquals(
            listOf(
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            ),
            order,
        )
        val session = requireNotNull(state.session)
        assertEquals(
            com.pricetrace.receiptscanner.ingestion.ProjectionStatus.UPLOADED,
            session.projections.single {
                it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE
            }.status,
        )
        assertEquals(
            com.pricetrace.receiptscanner.ingestion.ProjectionStatus.UPLOADED,
            session.projections.single {
                it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION
            }.status,
        )
    }

    @Test
    fun `android validator imports purchase v4 and submits CashOS without creating Fitness`() = runBlocking {
        val order = mutableListOf<IngestionProjection>()
        val cashOs = RecordingSubmitter(order)
        val priceTrace = RecordingSubmitter(order)
        val useCase = CanonicalIngestionUseCase(
            store = InMemoryIngestionSessionStore(),
            submitters = mapOf(
                IngestionProjection.CASHOS_TRANSACTION to cashOs,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION to priceTrace,
            ),
        )
        val validator = AndroidCanonicalJsonValidator(
            useCase = useCase,
            newLocalDocumentId = { "android-purchase-v4-document" },
            newIngestionId = { "android-purchase-v4-ingestion" },
        )

        var state = validator.importJson(
            readExample("yeonsik-ocr.v4.purchase.text-only.example.json"),
            AndroidCanonicalJsonValidatorState(),
        )
        assertEquals("yeonsik-ocr.v4", state.envelope?.schemaVersion)
        assertEquals(setOf(IngestionProjection.CASHOS_TRANSACTION), state.plan?.eligible)
        assertEquals(setOf(IngestionProjection.CASHOS_TRANSACTION), state.selectedProjections)

        state = validator.confirm(state)
        assertEquals(IngestionReviewStatus.READY, state.envelope?.review?.status)
        state = validator.submit(state)

        assertEquals(listOf(IngestionProjection.CASHOS_TRANSACTION), order)
        val session = requireNotNull(state.session)
        assertEquals(
            com.pricetrace.receiptscanner.ingestion.ProjectionStatus.UPLOADED,
            session.projections.single { it.projection == IngestionProjection.CASHOS_TRANSACTION }.status,
        )
        assertTrue(session.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }
            .status == com.pricetrace.receiptscanner.ingestion.ProjectionStatus.DISABLED)
        assertTrue(session.projections.single { it.projection == IngestionProjection.FITNESS_NUTRITION }
            .status == com.pricetrace.receiptscanner.ingestion.ProjectionStatus.DISABLED)
    }

    private class RecordingSubmitter(
        private val order: MutableList<IngestionProjection>,
    ) : IngestionProjectionSubmitter {
        override suspend fun submit(request: ProjectionRequest): ProjectionSubmission {
            order += request.projection
            return ProjectionSubmission.Success("android-" + request.projection.wireValue)
        }
    }

    private class SuccessfulArchivePort : EvidenceArchivePort {
        override suspend fun archive(
            request: EvidenceArchiveRequest,
            checkpoint: EvidenceArchiveCheckpoint,
        ): EvidenceArchiveResult = EvidenceArchiveResult.Success(
            checkpoint.copy(
                canonicalArtifactId = checkpoint.canonicalArtifactId ?: "artifact-1",
                bundleFingerprint = request.bundle.bundleFingerprint,
            ),
        )

        override suspend fun recordVerification(
            canonicalArtifactId: String,
            basis: VerificationBasis,
            result: String,
            issues: List<String>,
        ): EvidenceVerificationEventResult = EvidenceVerificationEventResult.Success
    }

    private fun textOnlyBundle(canonical: String): ByteArray {
        val canonicalBytes = canonical.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = YeonsikBundleManifest(YEONSIK_BUNDLE_VERSION, "canonical.json", hash, emptyList())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("canonical.json"))
            zip.write(canonicalBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(YeonsikBundleManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun evidenceBundle(
        canonical: String,
        variant: String = "",
        sourceFileId: String = "menu-photo-1",
        type: SourceAttachmentType = SourceAttachmentType.MENU_PHOTO,
    ): ByteArray {
        val canonicalBytes = canonical.toByteArray()
        val evidenceBytes = "menu evidence$variant".toByteArray()
        val evidencePath = if (variant.isBlank()) "evidence/menu.jpg" else "evidence/menu-$variant.jpg"
        val originalFilename = evidencePath.substringAfterLast('/')
        val evidence = com.pricetrace.receiptscanner.ingestion.YeonsikBundleEvidence(
            sourceFileId = sourceFileId,
            type = type,
            path = evidencePath,
            sha256 = MessageDigest.getInstance("SHA-256").digest(evidenceBytes)
                .joinToString("") { "%02x".format(it) },
            mimeType = "image/jpeg",
            byteSize = evidenceBytes.size.toLong(),
            originalFilename = originalFilename,
        )
        val manifest = YeonsikBundleManifest(
            YEONSIK_BUNDLE_VERSION,
            "canonical.json",
            MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
                .joinToString("") { "%02x".format(it) },
            listOf(evidence),
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("canonical.json"))
            zip.write(canonicalBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(YeonsikBundleManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(evidencePath))
            zip.write(evidenceBytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }

    private fun merchantJson(): String = Json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schema_version", JsonPrimitive("yeonsik-ocr.v3"))
            put("mode", JsonPrimitive("merchant"))
            put("source", buildJsonObject {
                put("producer", JsonPrimitive("chatgpt"))
                put("source_files", JsonArray(emptyList<JsonElement>()))
                put("user_text", JsonNull)
            })
            put("merchant_candidate", buildJsonObject {
                put("name", JsonPrimitive("Parity Merchant"))
                put("business_kind", JsonPrimitive("retail"))
                put("branch_name", JsonNull)
                put("address", JsonNull)
                put("phone", JsonNull)
                put("business_registration_number", JsonNull)
                put("source_attachment_ids", JsonArray(emptyList<JsonElement>()))
                put("source_namespace", JsonNull)
                put("source_location_code", JsonNull)
            })
            put("receipt", JsonNull)
            put("product_candidates", JsonArray(emptyList<JsonElement>()))
            put("price_observations", JsonArray(emptyList<JsonElement>()))
            put("nutrition", JsonArray(emptyList<JsonElement>()))
            put("consumption", JsonArray(emptyList<JsonElement>()))
            put("classification_hints", buildJsonObject { put("cashos", buildJsonObject {}) })
            put("links", JsonArray(emptyList<JsonElement>()))
            put("projection_targets", JsonArray(listOf(JsonPrimitive("cashos_receipt"))))
            put("review", buildJsonObject {
                put("status", JsonPrimitive("ready"))
                put("blocking_issues", JsonArray(emptyList<JsonElement>()))
                put("warnings", JsonArray(emptyList<JsonElement>()))
                put("verification_basis", JsonPrimitive("SOURCE_EVIDENCE"))
                put("user_verified", JsonPrimitive(true))
            })
        },
    )

    private fun v2MerchantCanonical(
        userText: String?,
        sourceFiles: List<SourceAttachment> = emptyList(),
        sourceAttachmentIds: List<String> = emptyList(),
    ): String = YeonsikOcrV2Json.encode(
        YeonsikOcrEnvelope(
            mode = IngestionMode.MERCHANT,
            source = IngestionSource("chatgpt", sourceFiles, userText),
            merchantCandidate = MerchantCandidate(
                name = "V2 Merchant",
                sourceAttachmentIds = sourceAttachmentIds,
            ),
            schemaVersion = YEONSIK_OCR_V2_SCHEMA,
        ),
    )
}
