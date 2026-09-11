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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopIngestionRegressionTest {
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

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }
}
