package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.importer.ExternalJsonImporter
import com.pricetrace.receiptscanner.ingestion.InMemoryIngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.IngestionOrchestrator
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionStartResult
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
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

    private fun readExample(name: String): String {
        val file = sequenceOf(File("examples", name), File("../examples", name))
            .firstOrNull(File::isFile) ?: error("example not found: $name")
        return file.readText()
    }
}
