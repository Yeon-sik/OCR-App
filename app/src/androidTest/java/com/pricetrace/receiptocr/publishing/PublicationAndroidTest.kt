package com.pricetrace.receiptocr.publishing

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pricetrace.receiptocr.publishing.evidence.SessionPublicationEvidenceResolver
import com.pricetrace.receiptocr.publishing.generator.*
import com.pricetrace.receiptocr.publishing.naver.NaverBlogPackageGenerator
import com.pricetrace.receiptocr.publishing.ui.PublicationSamples
import com.pricetrace.receiptscanner.capture.CapturedPageContent
import com.pricetrace.receiptscanner.publishing.evidence.PublicationEvidenceResolution
import com.pricetrace.receiptscanner.publishing.evidence.PublicationEvidenceResolver
import com.pricetrace.receiptscanner.storage.ReceiptFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PublicationAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ReceiptFileStore(context)
    private val documentId = "blog-test-" + UUID.randomUUID()
    private val assetDirectories = mutableListOf<File>()

    @After fun cleanUpOnlyTestArtifacts() {
        runBlocking { store.deleteDocumentFiles(documentId) }
        assetDirectories.forEach { it.deleteRecursively() }
    }

    @Test fun canvasSupportsRequiredOptionalDecimalAndMixedUnitRows() {
        val source = PublicationSamples.source()
        val required = (NutritionTableRenderer().render(source.copy(nutrients = source.nutrients.take(7)))
            as NutritionTableRenderResult.Ready).bitmap
        val optional = (NutritionTableRenderer().render(source.copy(
            productName = "모바일 블로그에서 읽기 쉬운 아주 긴 제품명 ".repeat(20),
            nutrients = source.nutrients.map { it.copy(value = 123.45) },
        )) as NutritionTableRenderResult.Ready).bitmap
        assertEquals(1080, required.width)
        assertTrue(required.height > 600)
        assertTrue(optional.height > required.height)
        assertEquals(Color.WHITE, required.getPixel(0, 0))
        required.recycle()
        optional.recycle()
    }

    @Test fun unavailableOptionalRowsDoNotIncreaseImageHeight() {
        val source = PublicationSamples.source()
        val full = (NutritionTableRenderer().render(source) as NutritionTableRenderResult.Ready).bitmap
        val partial = (NutritionTableRenderer().render(source.copy(
            nutrients = source.nutrients.filter { it.key != "fiber_grams" && it.key != "cholesterol_mg" },
        )) as NutritionTableRenderResult.Ready).bitmap
        assertTrue(partial.height < full.height)
        full.recycle()
        partial.recycle()
    }

    @Test fun bitmapAllocationFailuresReturnAnActionableFailure() {
        assertTrue(NutritionTableRenderer { _, _ -> throw IllegalStateException() }
            .render(PublicationSamples.source()) is NutritionTableRenderResult.Failed)
        assertTrue(NutritionTableRenderer { _, _ -> throw OutOfMemoryError() }
            .render(PublicationSamples.source()) is NutritionTableRenderResult.Failed)
    }

    @Test fun noEvidenceOrUnmappedAttachmentReturnsNoFabricatedUri() = runBlocking {
        val resolver = SessionPublicationEvidenceResolver(context, emptyList(), store::resolveStorageKey)
        val noEvidence = resolver.resolve(documentId, emptyList(), emptyList())
        assertTrue(noEvidence.images.isEmpty())
        assertTrue(noEvidence.notices.isNotEmpty())
        val result = resolver.resolve(documentId, listOf("not-a-file-path"), emptyList())
        assertTrue(result.images.isEmpty())
        assertTrue(result.notices.any { it.contains("첨부 식별자") })
        assertTrue(result.notices.any { it.contains("원본 페이지를 특정할 수 없어") })
    }

    @Test fun missingPageProvenanceNeverSelectsStoredDocumentPages() = runBlocking {
        val first = store.saveCapturedPage(documentId, 0, pageContent())
        val second = store.saveCapturedPage(documentId, 1, pageContent())
        var resolutions = 0
        val resolver = SessionPublicationEvidenceResolver(context, listOf(first, second)) {
            resolutions++
            store.resolveStorageKey(it)
        }
        for (ids in listOf(emptyList(), listOf("", " "))) {
            val result = resolver.resolve(documentId, emptyList(), ids)
            assertTrue(result.images.isEmpty())
            assertTrue(result.notices.any { it.contains("원본 페이지를 특정할 수 없어") })
        }
        assertEquals(0, resolutions)
    }

    @Test fun actualPageResolvesToReadableFileProviderUriWithoutChangingBytes() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        val before = store.readBytes(page.storageKey)
        val result = SessionPublicationEvidenceResolver(context, listOf(page), store::resolveStorageKey)
            .resolve(documentId, emptyList(), listOf(page.id))
        assertEquals(1, result.images.size)
        val image = result.images.single()
        assertEquals(page.id, image.id)
        assertEquals("content", Uri.parse(image.uri).scheme)
        val shared = context.contentResolver.openInputStream(Uri.parse(image.uri))!!.use { it.readBytes() }
        assertArrayEquals(before, shared)
        assertArrayEquals(before, store.readBytes(page.storageKey))
    }

    @Test fun multipleExplicitPagesResolveOnlyRequestedIdsAndRemoveDuplicates() = runBlocking {
        val first = store.saveCapturedPage(documentId, 0, pageContent())
        val second = store.saveCapturedPage(documentId, 1, pageContent())
        val unrequested = store.saveCapturedPage(documentId, 2, pageContent())
        val resolver = SessionPublicationEvidenceResolver(context, listOf(first, second, unrequested),
            store::resolveStorageKey)
        val result = resolver.resolve(documentId, emptyList(), listOf(second.id, first.id, second.id))
        assertEquals(listOf(second.id, first.id), result.images.map { it.id })
        assertTrue(result.notices.isEmpty())
        assertEquals(listOf(second.id), resolver.resolve(documentId, emptyList(),
            listOf(second.id, second.id)).images.map { it.id })
    }

    @Test fun missingAndForeignDocumentPagesAreExcludedWithNotices() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        val foreign = page.copy(id = "foreign-page", documentId = "other-document")
        val resolver = SessionPublicationEvidenceResolver(context, listOf(page, foreign), store::resolveStorageKey)
        val mixed = resolver.resolve(documentId, emptyList(), listOf(page.id, "missing-page", foreign.id))
        assertEquals(listOf(page.id), mixed.images.map { it.id })
        assertTrue(mixed.notices.isNotEmpty())
        val missing = resolver.resolve(documentId, emptyList(), listOf("missing-page"))
        assertTrue(missing.images.isEmpty())
        assertTrue(missing.notices.isNotEmpty())
        val foreignOnly = resolver.resolve(documentId, emptyList(), listOf(foreign.id))
        assertTrue(foreignOnly.images.isEmpty())
        assertTrue(foreignOnly.notices.isNotEmpty())
        assertTrue(resolver.resolve("other-document", emptyList(), listOf(page.id)).images.isEmpty())
    }

    @Test fun changedMissingAndUnreadableEvidenceIsExcluded() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        val tampered = SessionPublicationEvidenceResolver(context, listOf(page.copy(sha256 = "changed")),
            store::resolveStorageKey).resolve(documentId, emptyList(), listOf(page.id))
        assertTrue(tampered.images.isEmpty())
        assertTrue(tampered.notices.isNotEmpty())
        val missingFile = SessionPublicationEvidenceResolver(context, listOf(page)) {
            File(context.filesDir, "receipt-scanner/" + documentId + "/missing.png")
        }.resolve(documentId, emptyList(), listOf(page.id))
        assertTrue(missingFile.images.isEmpty())
        assertTrue(missingFile.notices.isNotEmpty())
        val unreadable = SessionPublicationEvidenceResolver(context, listOf(page)) { throw SecurityException() }
            .resolve(documentId, emptyList(), listOf(page.id))
        assertTrue(unreadable.images.isEmpty())
        assertTrue(unreadable.notices.isNotEmpty())
    }

    @Test fun readableImageOutsideEvidenceStorageRootIsExcluded() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        val outside = File(context.cacheDir, documentId + "-outside.png")
        try {
            outside.writeBytes(store.readBytes(page.storageKey))
            assertTrue(outside.isFile && outside.canRead())
            val result = SessionPublicationEvidenceResolver(context, listOf(page)) { outside }
                .resolve(documentId, emptyList(), listOf(page.id))
            assertTrue(result.images.isEmpty())
            assertTrue(result.notices.isNotEmpty())
        } finally {
            outside.delete()
        }
    }

    @Test fun generatedPngIsCachedSeparatelyAndReadableThroughProvider() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        val before = store.readBytes(page.storageKey)
        val original = store.resolveStorageKey(page.storageKey).canonicalFile
        val bitmap = (NutritionTableRenderer().render(PublicationSamples.source())
            as NutritionTableRenderResult.Ready).bitmap
        val generated = try { PublicationAssetStore(context).saveTable(bitmap) } finally { bitmap.recycle() }
        val directory = File(context.cacheDir, "receipt-scanner/publication-assets/" + generated.id)
        assetDirectories += directory
        val cached = File(directory, "nutrition_table.png").canonicalFile
        assertTrue(cached.isFile)
        assertTrue(cached.path.startsWith(
            File(context.cacheDir, "receipt-scanner/publication-assets").canonicalPath + File.separator))
        assertTrue(original.path.startsWith(context.filesDir.canonicalPath + File.separator))
        assertNotEquals(original, cached)
        assertFalse(File(context.filesDir, "receipt-scanner/publication-assets/" + generated.id).exists())
        val uri = Uri.parse(generated.uri)
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".files", uri.authority)
        assertTrue(uri.path!!.startsWith("/publication_assets/"))
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10), bytes.copyOfRange(0, 8))
        assertArrayEquals(cached.readBytes(), bytes)
        assertArrayEquals(before, store.readBytes(page.storageKey))
    }

    @Test fun failingResolverDoesNotBlockTextAndTablePackage() = runBlocking {
        val publication = NaverBlogPackageGenerator(
            PublicationEvidenceResolver { _, _, _ -> throw IllegalStateException() },
            PublicationAssetStore(context),
        ).generate(PublicationSamples.source())
        publication.draft.generatedImages.forEach {
            assetDirectories += File(context.cacheDir, "receipt-scanner/publication-assets/" + it.id)
        }
        assertTrue(publication.draft.title.isNotBlank())
        assertEquals(1, publication.draft.generatedImages.size)
        assertTrue(publication.draft.evidenceImages.isEmpty())
        assertTrue(publication.notices.isNotEmpty())
    }

    @Test fun failedTableRenderKeepsTextPackageWithNotice() = runBlocking {
        val publication = NaverBlogPackageGenerator(
            PublicationEvidenceResolver { _, _, _ -> PublicationEvidenceResolution() },
            PublicationAssetStore(context),
            NutritionTableRenderer { _, _ -> throw IllegalStateException() },
        ).generate(PublicationSamples.source())
        assertTrue(publication.draft.title.isNotBlank())
        assertTrue(publication.draft.body.isNotBlank())
        assertTrue(publication.draft.generatedImages.isEmpty())
        assertTrue(publication.notices.isNotEmpty())
    }

    @Test fun failedAssetStorageKeepsTextPackageWithNotice() = runBlocking {
        val blockedCache = File(context.cacheDir, documentId + "-blocked-cache")
        blockedCache.writeText("Not a directory")
        try {
            val brokenContext = object : ContextWrapper(context) {
                override fun getCacheDir(): File = blockedCache
            }
            val publication = NaverBlogPackageGenerator(
                PublicationEvidenceResolver { _, _, _ -> PublicationEvidenceResolution() },
                PublicationAssetStore(brokenContext),
            ).generate(PublicationSamples.source())
            assertTrue(publication.draft.title.isNotBlank())
            assertTrue(publication.draft.body.isNotBlank())
            assertTrue(publication.draft.generatedImages.isEmpty())
            assertTrue(publication.notices.any { it.contains("저장하지 못했습니다") })
        } finally {
            blockedCache.delete()
        }
    }

    @Test fun cancelledResolverDoesNotPublishAStalePackage() {
        try {
            runBlocking {
                NaverBlogPackageGenerator(
                    PublicationEvidenceResolver { _, _, _ -> throw CancellationException() },
                    PublicationAssetStore(context),
                ).generate(PublicationSamples.source())
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    private fun pageContent(): CapturedPageContent {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return CapturedPageContent(output.toByteArray(), "image/png")
    }
}
