package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionOrchestrator
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.SourceAttachment
import com.pricetrace.receiptscanner.ingestion.IngestionMode
import com.pricetrace.receiptscanner.ingestion.IngestionSource
import com.pricetrace.receiptscanner.ingestion.MerchantCandidate
import com.pricetrace.receiptscanner.ingestion.YEONSIK_BUNDLE_VERSION
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V4_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleEvidence
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleManifest
import com.pricetrace.receiptscanner.ingestion.YeonsikBundleManifestCodec
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV4Json
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveCheckpoint
import com.pricetrace.receiptscanner.ingestion.EvidenceArchivePort
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveRequest
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveResult
import com.pricetrace.receiptscanner.ingestion.EvidenceVerificationEventResult
import com.pricetrace.receiptscanner.ingestion.CanonicalRevisionArchiveRequest
import com.pricetrace.receiptscanner.ingestion.CanonicalRevisionArchiveResult
import com.pricetrace.receiptscanner.review.ReviewDestination
import com.pricetrace.receiptscanner.review.ReviewDestinationStatus
import com.pricetrace.receiptscanner.review.ReviewViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopIngestionRegressionTest {
    @Test
    fun `desktop review derives PriceTrace status and reason per V4 purchase record`() = runBlocking {
        val source = YeonsikOcrV4Json.decode(
            readExample("yeonsik-ocr.v4.purchase.example.json"),
            "desktop-mixed-review",
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
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-mixed-review"))
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
            ),
        )

        controller.importJson(canonical)
        val plan = CanonicalProjectionPlanner.plan(requireNotNull(controller.state.value.envelope))
        assertTrue(IngestionProjection.PRICETRACE_PRICE_OBSERVATION in plan.eligible)
        assertTrue(IngestionProjection.CASHOS_TRANSACTION in plan.eligible)
        assertFalse(canonical.contains("pricetrace_v4_"))
        val model = ReviewViewModel.fromCanonical(requireNotNull(controller.state.value.envelope))
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
        assertEquals(YEONSIK_OCR_V4_SCHEMA, controller.state.value.schema)
    }

    @Test
    fun `desktop v2 merchant bundles distinguish text evidence missing evidence and image evidence`() = runBlocking {
        fun controller(store: DesktopSessionStore) = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
                evidenceArchivePortOverride = SuccessfulArchivePort(),
            ),
        )

        val textStore = DesktopSessionStore(Files.createTempDirectory("yeonsik-v2-merchant-text"))
        val textController = controller(textStore)
        textController.importBundle(writeMerchantBundle(textStore.directory, "text", userText = "텍스트로 상호를 확인했습니다."))
        assertEquals(YEONSIK_OCR_V2_SCHEMA, textController.state.value.schema)
        assertTrue(textController.state.value.evidence.isEmpty())
        assertEquals(DesktopEvidenceArchiveStatus.ARCHIVED, textController.state.value.bundleMetadata?.archiveStatus)
        textController.verify(VerificationBasis.SOURCE_EVIDENCE)
        assertEquals(IngestionReviewStatus.READY, textController.state.value.session?.reviewStatus)
        assertTrue(textController.state.value.error == null)

        val missingStore = DesktopSessionStore(Files.createTempDirectory("yeonsik-v2-merchant-missing"))
        val missingController = controller(missingStore)
        missingController.importBundle(writeMerchantBundle(missingStore.directory, "missing", userText = null))
        missingController.verify(VerificationBasis.SOURCE_EVIDENCE)
        assertTrue(missingController.state.value.error.orEmpty().contains("source_image_required"))
        assertTrue(missingController.state.value.session?.reviewStatus != IngestionReviewStatus.READY)

        val imageStore = DesktopSessionStore(Files.createTempDirectory("yeonsik-v2-merchant-image"))
        val imageController = controller(imageStore)
        imageController.importBundle(
            writeMerchantBundle(
                imageStore.directory,
                "image",
                userText = null,
                sourceFiles = listOf(SourceAttachment("merchant-receipt-1", SourceAttachmentType.RECEIPT)),
                sourceAttachmentIds = listOf("merchant-receipt-1"),
            ),
        )
        assertEquals(listOf("merchant-receipt-1"), imageController.state.value.evidence.map { it.attachmentId })
        imageController.verify(VerificationBasis.SOURCE_EVIDENCE)
        assertEquals(IngestionReviewStatus.READY, imageController.state.value.session?.reviewStatus)
        assertTrue(imageController.state.value.error == null)
    }

    @Test
    fun `v1 and v2 examples use the shared importer and persist active projections`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-regression"))
        val bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")))
        val controller = DesktopIngestionController(store = store, bundle = bundle)

        controller.importJson(readExample("yeonsik-ocr.packaged-product.example.json"))
        val v1 = controller.state.value
        assertEquals("yeonsik-ocr.v1", v1.schema)
        assertNotNull(v1.session)
        assertTrue(v1.session!!.projections.any { it.status.wireValue != "disabled" })

        controller.importJson(readExample("yeonsik-ocr.v2.packaged-product.example.json"))
        val v2 = controller.state.value
        assertEquals("yeonsik-ocr.v2", v2.schema)
        assertNotNull(v2.session)
        assertTrue(v2.session!!.projections.any { it.projection == IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK })
    }

    @Test
    fun `invalid json is rejected and evidence gate blocks verification`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-invalid"))
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env"))),
        )

        controller.importJson("{\"schema_version\":\"unsupported\"}")
        assertTrue(controller.state.value.error.orEmpty().contains("UNSUPPORTED_SCHEMA"))
        assertEquals(null, controller.state.value.session)

        controller.importJson(readExample("yeonsik-ocr.packaged-product.example.json"))
        controller.verify()
        assertTrue(controller.state.value.error.orEmpty().contains("required"))
        assertFalse(controller.state.value.session!!.verifiedCanonicalFingerprint == controller.state.value.session!!.canonicalFingerprint)
    }

    @Test
    fun `explicit verify requires attached evidence and survives a new controller`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-verify"))
        val bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")))
        val controller = DesktopIngestionController(store = store, bundle = bundle)
        controller.importJson(readExample("yeonsik-ocr.packaged-product.example.json"))

        val source = store.directory.resolve("source-label.jpg").also { Files.writeString(it, "synthetic label") }
        controller.attachEvidence(listOf(source), com.pricetrace.receiptscanner.ingestion.SourceAttachmentType.NUTRITION_LABEL)
        controller.verify()

        val verified = requireNotNull(controller.state.value.session)
        assertEquals(verified.canonicalFingerprint, verified.verifiedCanonicalFingerprint)
        assertTrue(controller.state.value.artifacts.all { it.verified })

        val restored = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env"))),
        )
        restored.loadLatest()
        assertEquals(verified.ingestionId, restored.state.value.session?.ingestionId)
        assertEquals(verified.verifiedCanonicalFingerprint, restored.state.value.session?.verifiedCanonicalFingerprint)
    }

    @Test
    fun `desktop canonical and projection judgment matches the core orchestrator`() = runBlocking {
        val source = readExample("yeonsik-ocr.v2.restaurant.example.json")
        val localDocumentId = "same-document"
        val imported = requireNotNull(
            (ExternalJsonImporter().import(source, localDocumentId) as? com.pricetrace.receiptscanner.importer.ExternalJsonImportOutcome.Success)
                ?.result,
        )
        val envelope = imported.canonicalEnvelope
        val desktopStore = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-parity"))
        val controller = DesktopIngestionController(
            store = desktopStore,
            bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), desktopStore.directory.resolve("external.env"))),
        )
        controller.importJson(source)
        val desktopSession = requireNotNull(controller.state.value.session)

        val coreStore = InMemoryIngestionSessionStore()
        val coreResult = IngestionOrchestrator(coreStore, submitters = emptyMap()).start(
            ingestionId = "core-ingestion",
            localDocumentId = localDocumentId,
            envelope = envelope,
        )
        val coreSession = (coreResult as IngestionStartResult.Failure).let {
            requireNotNull(coreStore.get("core-ingestion"))
        }

        assertEquals(
            YeonsikOcrEnvelopeCodec.canonicalize(envelope),
            YeonsikOcrEnvelopeCodec.canonicalize(
                YeonsikOcrEnvelopeCodec.decode(
                    controller.state.value.canonicalJson,
                    controller.state.value.localDocumentId!!,
                ),
            ),
        )
        assertEquals(
            coreSession.projections.filter { it.status.wireValue != "disabled" }.map { it.projection }.toSet(),
            desktopSession.projections.filter { it.status.wireValue != "disabled" }.map { it.projection }.toSet(),
        )
    }

    @Test
    fun `desktop v3 uses the core plan and allows no image manual confirmation`() = runBlocking {
        val source = readExample("yeonsik-ocr.v3.merchant.example.json")
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-v3-parity"))
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env"))),
        )

        controller.importJson(source)
        val before = controller.state.value
        val envelope = YeonsikOcrEnvelopeCodec.decode(source, before.localDocumentId!!)
        val plan = CanonicalProjectionPlanner.plan(envelope)
        assertEquals(
            plan.eligible,
            before.session!!.projections.filter { it.status.wireValue != "disabled" }.map { it.projection }.toSet(),
        )
        assertTrue(IngestionProjection.CASHOS_RECEIPT !in plan.eligible)

        controller.verify(VerificationBasis.MANUAL_CANONICAL_REVIEW)
        val confirmed = controller.state.value
        assertEquals(IngestionReviewStatus.READY, confirmed.session?.reviewStatus)
        assertEquals(confirmed.session?.canonicalFingerprint, confirmed.session?.verifiedCanonicalFingerprint)
    }

    @Test
    fun `desktop text-only retail JSON keeps source gate and allows manual canonical confirmation`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-text-only"))
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env"))),
        )

        controller.importJson(readExample("yeonsik-ocr.v3.text-only-retail.example.json"))
        val imported = controller.state.value
        assertTrue(imported.evidence.isEmpty())
        assertTrue(imported.session!!.projections.any {
            it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE
        })
        assertTrue(imported.session!!.projections.any {
            it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION
        })

        controller.verify(VerificationBasis.SOURCE_EVIDENCE)
        val blocked = controller.state.value
        assertTrue(blocked.error.orEmpty().contains("required"))
        assertTrue(
            blocked.session!!.verifiedCanonicalFingerprint != blocked.session!!.canonicalFingerprint,
        )

        controller.verify(VerificationBasis.MANUAL_CANONICAL_REVIEW)
        val confirmed = controller.state.value
        assertEquals(IngestionReviewStatus.READY, confirmed.session?.reviewStatus)
        assertEquals(confirmed.session?.canonicalFingerprint, confirmed.session?.verifiedCanonicalFingerprint)
        assertTrue(confirmed.artifacts.isNotEmpty())
        assertTrue(confirmed.artifacts.all { it.evidenceReady })
    }

    @Test
    fun `desktop v4 attaches history evidence by logical source id and verifies both purchase projections`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-v4-photo"))
        val bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")))
        val controller = DesktopIngestionController(store = store, bundle = bundle)

        controller.importJson(readExample("yeonsik-ocr.v4.purchase.example.json"))
        val imported = controller.state.value
        assertEquals("yeonsik-ocr.v4", imported.schema)
        assertTrue(imported.session!!.projections.any {
            it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION
        })
        assertTrue(imported.session!!.projections.any {
            it.projection == IngestionProjection.CASHOS_TRANSACTION
        })
        assertTrue(imported.session!!.projections.any {
            it.projection == IngestionProjection.FITNESS_NUTRITION &&
                it.status == com.pricetrace.receiptscanner.ingestion.ProjectionStatus.DISABLED
        })

        val orderImage = store.directory.resolve("order-history.jpg").also { Files.writeString(it, "order") }
        val paymentImage = store.directory.resolve("payment-history.jpg").also { Files.writeString(it, "payment") }
        controller.attachEvidence(listOf(orderImage), SourceAttachmentType.ORDER_HISTORY)
        controller.attachEvidence(listOf(paymentImage), SourceAttachmentType.PAYMENT_HISTORY)
        assertEquals(
            setOf("order-history-1", "payment-history-1"),
            controller.state.value.evidence.map { it.attachmentId }.toSet(),
        )

        controller.verify(VerificationBasis.SOURCE_EVIDENCE)
        val verified = controller.state.value
        assertEquals(IngestionReviewStatus.READY, verified.session?.reviewStatus)
        assertEquals(verified.session?.canonicalFingerprint, verified.session?.verifiedCanonicalFingerprint)
        assertTrue(verified.artifacts.isNotEmpty())
        assertTrue(verified.artifacts.all { it.evidenceReady })
        assertTrue(bundle.submitters.containsKey(IngestionProjection.CASHOS_TRANSACTION))
    }

    @Test
    fun `desktop v4 text-only payment record remains CashOS-only`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-console-v4-text"))
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env"))),
        )

        controller.importJson(readExample("yeonsik-ocr.v4.purchase.text-only.example.json"))
        val imported = controller.state.value
        assertEquals(
            setOf(IngestionProjection.CASHOS_TRANSACTION),
            imported.session!!.projections.filter { it.status != com.pricetrace.receiptscanner.ingestion.ProjectionStatus.DISABLED }
                .map { it.projection }.toSet(),
        )
        controller.verify(VerificationBasis.SOURCE_EVIDENCE)
        val verified = controller.state.value
        assertEquals(IngestionReviewStatus.READY, verified.session?.reviewStatus)
        assertEquals(verified.session?.canonicalFingerprint, verified.session?.verifiedCanonicalFingerprint)
    }

    @Test
    fun `desktop failed revision archive preserves original and restores the retried snapshot`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-desktop-revision-retry"))
        val archive = CountingArchivePort(revisionFailuresBeforeSuccess = 1)
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
                evidenceArchivePortOverride = archive,
            ),
        )
        val source = writeMerchantBundle(store.directory, "revision-retry", userText = "K비빔밥")

        controller.importBundle(source)
        controller.verify(VerificationBasis.SOURCE_EVIDENCE)
        val imported = controller.state.value
        val originalRawJson = imported.rawJson
        val originalCanonicalJson = imported.canonicalJson

        controller.reviseStructuredReview {
            it.updateField("merchant_candidate.name", "키키덮밥")
        }
        val failed = controller.state.value

        assertEquals(originalRawJson, failed.rawJson)
        assertEquals(originalCanonicalJson, failed.canonicalJson)
        assertEquals(DesktopCanonicalRevisionArchiveStatus.FAILED, failed.bundleMetadata?.revisionArchiveStatus)
        assertNotNull(failed.bundleMetadata?.pendingRevision)
        assertEquals(1L, failed.bundleMetadata?.pendingRevision?.revisionSeq)
        assertEquals(1, archive.revisionArchiveCalls)

        controller.submit()
        assertTrue(controller.state.value.error.orEmpty().contains("revision", ignoreCase = true))

        controller.retryCanonicalRevision()
        val retried = controller.state.value
        assertEquals("키키덮밥", retried.envelope?.merchantCandidate?.name)
        assertEquals(originalRawJson, retried.rawJson)
        assertEquals(DesktopCanonicalRevisionArchiveStatus.ARCHIVED, retried.bundleMetadata?.revisionArchiveStatus)
        assertEquals(null, retried.bundleMetadata?.pendingRevision)
        assertEquals(1L, retried.bundleMetadata?.archiveCheckpoint?.latestRevisionSeq)
        assertEquals("revision-1", retried.bundleMetadata?.archiveCheckpoint?.latestRevisionId)
        assertEquals(listOf("merchant_candidate.name"), retried.reviewEdits.map { it.fieldPath })
        assertEquals(2, archive.revisionArchiveCalls)

        val restarted = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("restarted.env")),
                evidenceArchivePortOverride = archive,
            ),
        )
        restarted.load(requireNotNull(retried.ingestionId))
        assertEquals("키키덮밥", restarted.state.value.envelope?.merchantCandidate?.name)
        assertEquals(listOf("merchant_candidate.name"), restarted.state.value.reviewEdits.map { it.fieldPath })
    }

    @Test
    fun `desktop batch keeps independent sessions and opens the selected item`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-desktop-batch-two"))
        val archive = CountingArchivePort()
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
                evidenceArchivePortOverride = archive,
            ),
        )
        val coordinator = DesktopBatchCoordinator(controller)
        val first = writeMerchantBundle(store.directory, "batch-first", userText = "첫 번째 출처")
        val second = writeMerchantBundle(store.directory, "batch-second", userText = "두 번째 출처")

        coordinator.importBundles(listOf(first, second))

        val state = coordinator.state.value
        assertEquals(2, state.items.size)
        assertEquals(2, state.items.mapNotNull { it.ingestionId }.toSet().size)
        assertTrue(state.items.all { it.status == DesktopBatchItemStatus.REVIEW_REQUIRED })
        assertEquals(2, archive.archiveCalls)
        val selected = state.items[1]
        coordinator.openItem(selected.itemId)
        assertEquals(selected.ingestionId, controller.state.value.ingestionId)
        controller.verify(VerificationBasis.SOURCE_EVIDENCE)
        coordinator.syncActiveItem()
        assertEquals(DesktopBatchItemStatus.READY_TO_SUBMIT, coordinator.state.value.items[1].status)
    }

    @Test
    fun `desktop batch isolates invalid item and continues with valid item`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-desktop-batch-invalid"))
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
                evidenceArchivePortOverride = CountingArchivePort(),
            ),
        )
        val coordinator = DesktopBatchCoordinator(controller)
        val invalid = store.directory.resolve("invalid.yeonsik").also { Files.writeString(it, "not a zip") }
        val valid = writeMerchantBundle(store.directory, "batch-valid", userText = "유효한 출처")

        coordinator.importBundles(listOf(invalid, valid))

        val items = coordinator.state.value.items
        assertEquals(DesktopBatchItemStatus.FAILED, items[0].status)
        assertTrue(items[0].error.orEmpty().isNotBlank())
        assertEquals(DesktopBatchItemStatus.REVIEW_REQUIRED, items[1].status)
        assertNotNull(items[1].ingestionId)
    }

    @Test
    fun `desktop batch duplicate does not rearchive the existing session`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-desktop-batch-duplicate"))
        val archive = CountingArchivePort()
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
                evidenceArchivePortOverride = archive,
            ),
        )
        val coordinator = DesktopBatchCoordinator(controller)
        val first = writeMerchantBundle(store.directory, "batch-original", userText = "동일한 출처")
        val duplicate = writeMerchantBundle(store.directory, "batch-duplicate", userText = "동일한 출처")

        coordinator.importBundles(listOf(first, duplicate))

        val items = coordinator.state.value.items
        assertEquals(DesktopBatchItemStatus.REVIEW_REQUIRED, items[0].status)
        assertEquals(DesktopBatchItemStatus.DUPLICATE, items[1].status)
        assertEquals(items[0].ingestionId, items[1].duplicateOfIngestionId)
        assertEquals(1, archive.archiveCalls)
        assertNotNull(store.loadRecord(requireNotNull(items[0].ingestionId)))
        val sessionFiles = Files.list(store.directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.count()
        }
        assertEquals(1, sessionFiles)
    }

    @Test
    fun `desktop batch continues after archive failure`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-desktop-batch-archive-failure"))
        val archive = CountingArchivePort(failuresBeforeSuccess = 1)
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
                evidenceArchivePortOverride = archive,
            ),
        )
        val coordinator = DesktopBatchCoordinator(controller)
        val first = writeMerchantBundle(store.directory, "batch-archive-fails", userText = "첫 보관 실패")
        val second = writeMerchantBundle(store.directory, "batch-after-failure", userText = "다음 항목")

        coordinator.importBundles(listOf(first, second))

        val items = coordinator.state.value.items
        assertEquals(DesktopBatchItemStatus.FAILED, items[0].status)
        assertTrue(items[0].error.orEmpty().contains("archive", ignoreCase = true))
        assertEquals(DesktopBatchItemStatus.REVIEW_REQUIRED, items[1].status)
        assertEquals(2, archive.archiveCalls)
    }

    @Test
    fun `desktop batch retries only the failed archive item`() = runBlocking {
        val store = DesktopSessionStore(Files.createTempDirectory("yeonsik-desktop-batch-retry"))
        val archive = CountingArchivePort(failuresBeforeSuccess = 1)
        val controller = DesktopIngestionController(
            store = store,
            bundle = DesktopProjectionBundle(
                config = DesktopRuntimeConfig.load(emptyMap(), store.directory.resolve("external.env")),
                evidenceArchivePortOverride = archive,
            ),
        )
        val coordinator = DesktopBatchCoordinator(controller)
        val source = writeMerchantBundle(store.directory, "batch-retry", userText = "보관 재시도")

        coordinator.importBundles(listOf(source))
        val failed = coordinator.state.value.items.single()
        assertEquals(DesktopBatchItemStatus.FAILED, failed.status)

        coordinator.retryItem(failed.itemId)

        assertEquals(DesktopBatchItemStatus.REVIEW_REQUIRED, coordinator.state.value.items.single().status)
        assertEquals(2, archive.archiveCalls)
    }

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }

    private fun writeMerchantBundle(
        directory: Path,
        name: String,
        userText: String?,
        sourceFiles: List<SourceAttachment> = emptyList(),
        sourceAttachmentIds: List<String> = emptyList(),
    ): Path {
        val canonical = YeonsikOcrV2Json.encode(
            YeonsikOcrEnvelope(
                mode = IngestionMode.MERCHANT,
                source = IngestionSource("chatgpt", sourceFiles, userText),
                merchantCandidate = MerchantCandidate(
                    name = "Desktop V2 Merchant",
                    sourceAttachmentIds = sourceAttachmentIds,
                ),
                schemaVersion = YEONSIK_OCR_V2_SCHEMA,
            ),
        )
        val canonicalBytes = canonical.toByteArray()
        val evidenceBytes = "merchant receipt evidence".toByteArray()
        val evidence = sourceFiles.singleOrNull()?.let { source ->
            YeonsikBundleEvidence(
                sourceFileId = source.id,
                type = source.type,
                path = "evidence/${source.id}.jpg",
                sha256 = sha256(evidenceBytes),
                mimeType = "image/jpeg",
                byteSize = evidenceBytes.size.toLong(),
                originalFilename = "${source.id}.jpg",
            )
        }
        val manifest = YeonsikBundleManifest(
            bundleVersion = YEONSIK_BUNDLE_VERSION,
            canonicalPath = "canonical.json",
            canonicalSha256 = sha256(canonicalBytes),
            evidence = listOfNotNull(evidence),
        )
        val path = directory.resolve("$name.yeonsik")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("canonical.json"))
            zip.write(canonicalBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(YeonsikBundleManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
            evidence?.let { item ->
                zip.putNextEntry(ZipEntry(item.path))
                zip.write(evidenceBytes)
                zip.closeEntry()
            }
        }
        return path
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class SuccessfulArchivePort : EvidenceArchivePort {
        override suspend fun archive(
            request: EvidenceArchiveRequest,
            checkpoint: EvidenceArchiveCheckpoint,
        ): EvidenceArchiveResult = EvidenceArchiveResult.Success(
            checkpoint.copy(
                canonicalArtifactId = checkpoint.canonicalArtifactId ?: "desktop-archive-artifact",
                bundleFingerprint = request.bundle.bundleFingerprint,
            ),
        )

       override suspend fun recordVerification(
           canonicalArtifactId: String,
           basis: VerificationBasis,
           result: String,
           issues: List<String>,
       ): EvidenceVerificationEventResult = EvidenceVerificationEventResult.Success

        override suspend fun archiveCanonicalRevision(
            request: CanonicalRevisionArchiveRequest,
        ): CanonicalRevisionArchiveResult = CanonicalRevisionArchiveResult.Success(
            revisionId = "revision-${request.revisionSeq}",
            revisionSeq = request.revisionSeq,
        )
   }

    private class CountingArchivePort(
        private val failuresBeforeSuccess: Int = 0,
        private val revisionFailuresBeforeSuccess: Int = 0,
    ) : EvidenceArchivePort {
        var archiveCalls: Int = 0
            private set
        var revisionArchiveCalls: Int = 0
            private set

        override suspend fun archive(
            request: EvidenceArchiveRequest,
            checkpoint: EvidenceArchiveCheckpoint,
        ): EvidenceArchiveResult {
            archiveCalls += 1
            val nextCheckpoint = checkpoint.copy(bundleFingerprint = request.bundle.bundleFingerprint)
            return if (archiveCalls <= failuresBeforeSuccess) {
                EvidenceArchiveResult.Failure("archive test failure", nextCheckpoint)
            } else {
                EvidenceArchiveResult.Success(
                    nextCheckpoint.copy(canonicalArtifactId = nextCheckpoint.canonicalArtifactId ?: "batch-artifact-$archiveCalls"),
                )
            }
        }

       override suspend fun recordVerification(
           canonicalArtifactId: String,
           basis: VerificationBasis,
           result: String,
           issues: List<String>,
       ): EvidenceVerificationEventResult = EvidenceVerificationEventResult.Success

        override suspend fun archiveCanonicalRevision(
            request: CanonicalRevisionArchiveRequest,
        ): CanonicalRevisionArchiveResult {
            revisionArchiveCalls += 1
            return if (revisionArchiveCalls <= revisionFailuresBeforeSuccess) {
                CanonicalRevisionArchiveResult.Failure(
                    issue = "revision archive test failure",
                    revisionSeq = request.revisionSeq,
                )
            } else {
                CanonicalRevisionArchiveResult.Success(
                    revisionId = "revision-${request.revisionSeq}",
                    revisionSeq = request.revisionSeq,
                )
            }
        }
   }
}
