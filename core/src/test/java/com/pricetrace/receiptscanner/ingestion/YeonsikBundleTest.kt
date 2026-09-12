package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.input.InputOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YeonsikBundleTest {
    @Test
    fun validV2RestaurantAutoBindsEvidenceAndPassesSourceGate() {
        val canonical = example("yeonsik-ocr.v2.restaurant.example.json")
        val evidence = mapOf(
            "receipt-1" to Evidence(SourceAttachmentType.RECEIPT, "receipt.jpg", "receipt".toByteArray()),
            "food-1" to Evidence(SourceAttachmentType.FOOD_PHOTO, "food-1.jpg", "food one".toByteArray()),
            "food-3" to Evidence(SourceAttachmentType.FOOD_PHOTO, "food-3.jpg", "food three".toByteArray()),
        )
        val result = readBundle(bundle(canonical, evidence))
        assertEquals(evidence.keys, result.bundle.manifest.evidence.map { it.sourceFileId }.toSet())
        val local = result.bundle.manifest.evidence.map {
            LocalEvidence(it.sourceFileId, it.type, Files.isReadable(result.directory.resolve(it.path)))
        }
        assertTrue(
            IngestionEvidenceGate.evaluate(
                result.bundle.envelope,
                local,
                InputOrigin.EXTERNAL_JSON,
                verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
            ).isAllowed,
        )
    }

    @Test
    fun validV4PurchaseKeepsBindingAndRouting() {
        val canonical = example("yeonsik-ocr.v4.purchase.example.json")
        val evidence = mapOf(
            "order-history-1" to Evidence(SourceAttachmentType.ORDER_HISTORY, "order.png", "order".toByteArray()),
            "payment-history-1" to Evidence(SourceAttachmentType.PAYMENT_HISTORY, "payment.png", "payment".toByteArray()),
        )
        val result = readBundle(bundle(canonical, evidence)).bundle
        assertEquals(
            mapOf(
                "order-history-1" to SourceAttachmentType.ORDER_HISTORY,
                "payment-history-1" to SourceAttachmentType.PAYMENT_HISTORY,
            ),
            result.manifest.evidence.associate { it.sourceFileId to it.type },
        )
        assertTrue(IngestionProjection.CASHOS_TRANSACTION in CanonicalProjectionPlanner.plan(result.envelope).eligible)
    }

    @Test
    fun textOnlyV4AllowsEmptyEvidence() {
        val result = readBundle(bundle(example("yeonsik-ocr.v4.purchase.text-only.example.json"), emptyMap())).bundle
        assertTrue(result.manifest.evidence.isEmpty())
        assertTrue(result.envelope.source.sourceFiles.isEmpty())
    }

    @Test
    fun sameCanonicalWithDifferentEvidenceGetsDifferentBundleIdentityAndStaysIsolatedFromLegacy() = runBlocking {
        val canonical = example("yeonsik-ocr.v3.restaurant.example.json")
        val first = readBundle(
            bundle(
                canonical,
                mapOf("menu-photo-1" to Evidence(SourceAttachmentType.MENU_PHOTO, "menu-a.jpg", "menu-a".toByteArray())),
            ),
        ).bundle
        val second = readBundle(
            bundle(
                canonical,
                mapOf("menu-photo-1" to Evidence(SourceAttachmentType.MENU_PHOTO, "menu-b.jpg", "menu-b".toByteArray())),
            ),
        ).bundle
        assertEquals(first.manifest.canonicalSha256, second.manifest.canonicalSha256)
        assertTrue(first.manifestSha256 != second.manifestSha256)
        assertTrue(first.bundleFingerprint != second.bundleFingerprint)

        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(store, submitters = emptyMap())
        val firstEvidence = first.manifest.evidence.map { LocalEvidence(it.sourceFileId, it.type, true) }
        val secondEvidence = second.manifest.evidence.map { LocalEvidence(it.sourceFileId, it.type, true) }
        assertTrue(
            orchestrator.start(
                "bundle-a",
                "document-a",
                first.envelope,
                firstEvidence,
                InputOrigin.EXTERNAL_JSON,
                first.bundleFingerprint,
            ) is IngestionStartResult.Success,
        )
        assertTrue(
            orchestrator.start(
                "bundle-b",
                "document-b",
                second.envelope,
                secondEvidence,
                InputOrigin.EXTERNAL_JSON,
                second.bundleFingerprint,
            ) is IngestionStartResult.Success,
        )
        assertTrue(
            orchestrator.start(
                "legacy-json",
                "document-legacy",
                first.envelope,
                firstEvidence,
                InputOrigin.EXTERNAL_JSON,
            ) is IngestionStartResult.Success,
        )

        val useCaseStore = InMemoryIngestionSessionStore()
        val useCase = CanonicalIngestionUseCase(useCaseStore)
        val imported = useCase.importJson(
            value = first.canonicalJson,
            localDocumentId = "document-use-case",
            ingestionId = "bundle-use-case",
            evidence = firstEvidence,
            bundleFingerprint = first.bundleFingerprint,
        )
        assertTrue(imported is CanonicalImportResult.Success)
        val legacyTransition = useCase.importJson(
            value = first.canonicalJson,
            localDocumentId = "document-use-case",
            ingestionId = "bundle-use-case",
        )
        assertTrue(legacyTransition is CanonicalImportResult.Failure)
        assertEquals(listOf("bundle_legacy_transition_forbidden"), (legacyTransition as CanonicalImportResult.Failure).issues)
    }

    @Test
    fun hashAndSizeMismatchesAreRejected() {
        val canonical = example("yeonsik-ocr.v3.restaurant.example.json")
        val evidence = mapOf(
            "menu-photo-1" to Evidence(SourceAttachmentType.MENU_PHOTO, "menu.jpg", "menu".toByteArray()),
        )
        assertRejected(bundle(canonical, evidence, canonicalHash = "0".repeat(64)), "canonical hash mismatch")
        assertRejected(bundle(canonical, evidence, evidenceHash = "0".repeat(64)), "evidence hash mismatch")
        assertRejected(bundle(canonical, evidence, evidenceSizeDelta = 1), "evidence size mismatch")
    }

    @Test
    fun bindingAndPathViolationsAreRejected() {
        val canonical = example("yeonsik-ocr.v3.restaurant.example.json")
        assertRejected(bundle(canonical, emptyMap()), "match 1:1")
        val correct = mapOf(
            "menu-photo-1" to Evidence(SourceAttachmentType.MENU_PHOTO, "menu.jpg", "menu".toByteArray()),
        )
        assertRejected(
            bundle(canonical, correct.mapValues { (_, item) -> item.copy(type = SourceAttachmentType.FOOD_PHOTO) }),
            "match 1:1",
        )
        assertRejected(bundle(canonical, correct, evidencePath = "evidence/../menu.jpg"), "unsafe")
        assertRejected(
            bundle(canonical, correct, manifestByteSize = YeonsikBundleReader.MAX_SINGLE_EVIDENCE_BYTES + 1),
            "exceeds limit",
        )
    }

    @Test
    fun duplicateEntriesAndCompressedOversizedEvidenceAreRejectedBeforeMaterialization() {
        val canonical = example("yeonsik-ocr.v2.restaurant.example.json")
        val evidence = mapOf(
            "receipt-1" to Evidence(SourceAttachmentType.RECEIPT, "receipt.jpg", "receipt".toByteArray()),
            "food-1" to Evidence(SourceAttachmentType.FOOD_PHOTO, "food-1.jpg", "food one".toByteArray()),
            "food-3" to Evidence(SourceAttachmentType.FOOD_PHOTO, "food-3.jpg", "food three".toByteArray()),
        )
        val duplicate = replaceAscii(
            bundle(canonical, evidence),
            "evidence/food-3.jpg".toByteArray(),
            "evidence/food-1.jpg".toByteArray(),
        )
        assertRejected(duplicate, "duplicate ZIP entry")
        assertRejected(oversizedEvidenceZip(), "single evidence exceeds limit")
    }

    @Test
    fun sharedContentPathIsMaterializedOnce() {
        val canonical = example("yeonsik-ocr.v2.restaurant.example.json")
        val shared = "same food".toByteArray()
        val evidence = mapOf(
            "receipt-1" to Evidence(SourceAttachmentType.RECEIPT, "receipt.jpg", "receipt".toByteArray()),
            "food-1" to Evidence(SourceAttachmentType.FOOD_PHOTO, "shared.jpg", shared),
            "food-3" to Evidence(SourceAttachmentType.FOOD_PHOTO, "shared.jpg", shared),
        )
        val result = readBundle(bundle(canonical, evidence, shareByFilename = true))
        assertEquals(2, result.openedPaths.size)
    }

    @Test
    fun legacyJsonWithoutLocalEvidenceStillFailsAtRuntimeGate() {
        val envelope = YeonsikOcrEnvelopeCodec.decode(
            example("yeonsik-ocr.v2.restaurant.example.json"),
            "legacy-json-test",
        )
        val gate = IngestionEvidenceGate.evaluate(
            envelope,
            emptyList(),
            InputOrigin.EXTERNAL_JSON,
            verificationBasis = VerificationBasis.SOURCE_EVIDENCE,
        )
        assertTrue(!gate.isAllowed)
        assertTrue(gate.blockingIssues.isNotEmpty())
    }

    private data class Evidence(val type: SourceAttachmentType, val filename: String, val bytes: ByteArray)
    private data class ReadResult(val bundle: YeonsikBundle, val directory: Path, val openedPaths: List<String>)

    private fun readBundle(bytes: ByteArray): ReadResult {
        val directory = Files.createTempDirectory("yeonsik-bundle-test")
        val opened = mutableListOf<String>()
        val materializer = object : YeonsikBundleMaterializer {
            override fun open(relativePath: String): OutputStream {
                opened += relativePath
                val target = directory.resolve(relativePath)
                Files.createDirectories(target.parent)
                return Files.newOutputStream(target)
            }

            override fun abort() = directory.toFile().deleteRecursively().let { Unit }
        }
        return ReadResult(
            YeonsikBundleReader.read(ByteArrayInputStream(bytes), "bundle-test", materializer),
            directory,
            opened,
        )
    }

    private fun assertRejected(bytes: ByteArray, message: String) {
        val error = runCatching { readBundle(bytes) }.exceptionOrNull()
        assertTrue("expected rejection containing '$message', got $error", error?.message.orEmpty().contains(message))
    }

    private fun bundle(
        canonical: String,
        evidence: Map<String, Evidence>,
        canonicalHash: String? = null,
        evidenceHash: String? = null,
        evidenceSizeDelta: Long = 0,
        manifestByteSize: Long? = null,
        evidencePath: String? = null,
        shareByFilename: Boolean = false,
    ): ByteArray {
        val canonicalBytes = canonical.toByteArray()
        val records = evidence.map { (id, item) ->
            val path = evidencePath ?: "evidence/${item.filename}"
            YeonsikBundleEvidence(
                id,
                item.type,
                path,
                evidenceHash ?: digest(item.bytes),
                "image/jpeg",
                manifestByteSize ?: item.bytes.size.toLong() + evidenceSizeDelta,
                item.filename,
            )
        }
        val manifest = YeonsikBundleManifest(
            YEONSIK_BUNDLE_VERSION,
            "canonical.json",
            canonicalHash ?: digest(canonicalBytes),
            records,
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            add("canonical.json", canonicalBytes)
            add("manifest.json", YeonsikBundleManifestCodec.encode(manifest).toByteArray())
            val written = mutableSetOf<String>()
            evidence.forEach { (_, item) ->
                val path = evidencePath ?: "evidence/${item.filename}"
                if (!shareByFilename || written.add(path)) add(path, item.bytes)
            }
        }
        return output.toByteArray()
    }

    private fun oversizedEvidenceZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            add("canonical.json", "{}".toByteArray())
            add("manifest.json", "{}".toByteArray())
            zip.putNextEntry(ZipEntry("evidence/zip-bomb.jpg"))
            repeat(6_401) { zip.write(ByteArray(8 * 1024)) }
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun replaceAscii(input: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        require(from.size == to.size)
        val copy = input.copyOf()
        var replacements = 0
        for (index in 0..copy.size - from.size) {
            if (from.indices.all { offset -> copy[index + offset] == from[offset] }) {
                to.copyInto(copy, index)
                replacements++
            }
        }
        require(replacements >= 2) { "expected local and central ZIP names" }
        return copy
    }

    private fun example(name: String): String = Files.readString(Path.of("..", "examples", name))
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
