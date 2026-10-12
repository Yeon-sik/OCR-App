package com.pricetrace.receiptocr.publishing

import android.content.Context
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
        assertTrue(resolver.resolve(documentId, emptyList(), emptyList()).images.isEmpty())
        val result = resolver.resolve(documentId, listOf("not-a-file-path"), emptyList())
        assertTrue(result.images.isEmpty())
        assertTrue(result.notices.isNotEmpty())
    }

    @Test fun actualPageResolvesToReadableFileProviderUriWithoutChangingBytes() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        val before = store.readBytes(page.storageKey)
        val result = SessionPublicationEvidenceResolver(context, listOf(page), store::resolveStorageKey)
            .resolve(documentId, emptyList(), listOf(page.id))
        assertEquals(1, result.images.size)
        val image = result.images.single()
        assertEquals("content", Uri.parse(image.uri).scheme)
        val shared = context.contentResolver.openInputStream(Uri.parse(image.uri))!!.use { it.readBytes() }
        assertArrayEquals(before, shared)
        assertArrayEquals(before, store.readBytes(page.storageKey))
    }

    @Test fun multipleImagesResolveOnlyWithinRequestedDocumentAndPageSet() = runBlocking {
        val first = store.saveCapturedPage(documentId, 0, pageContent())
        val second = store.saveCapturedPage(documentId, 1, pageContent())
        val resolver = SessionPublicationEvidenceResolver(context, listOf(first, second), store::resolveStorageKey)
        assertEquals(2, resolver.resolve(documentId, emptyList(), emptyList()).images.size)
        assertEquals(listOf(second.id), resolver.resolve(documentId, emptyList(),
            listOf(second.id, second.id)).images.map { it.id })
        assertTrue(resolver.resolve("other-document", emptyList(), listOf(first.id)).images.isEmpty())
        assertTrue(resolver.resolve(documentId, emptyList(), listOf("missing-page")).images.isEmpty())
    }

    @Test fun changedUnreadableAndEscapingEvidenceIsExcluded() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        assertTrue(SessionPublicationEvidenceResolver(context, listOf(page.copy(sha256 = "changed")),
            store::resolveStorageKey).resolve(documentId, emptyList(), listOf(page.id)).images.isEmpty())
        assertTrue(SessionPublicationEvidenceResolver(context, listOf(page)) { File(context.cacheDir, "missing") }
            .resolve(documentId, emptyList(), listOf(page.id)).images.isEmpty())
        assertTrue(SessionPublicationEvidenceResolver(context, listOf(page)) { throw SecurityException() }
            .resolve(documentId, emptyList(), listOf(page.id)).images.isEmpty())
    }

    @Test fun generatedPngIsSeparateFromOriginalAndReadableThroughProvider() = runBlocking {
        val page = store.saveCapturedPage(documentId, 0, pageContent())
        val before = store.readBytes(page.storageKey)
        val bitmap = (NutritionTableRenderer().render(PublicationSamples.source())
            as NutritionTableRenderResult.Ready).bitmap
        val generated = PublicationAssetStore(context).saveTable(bitmap)
        assetDirectories += File(context.filesDir, "receipt-scanner/publication-assets/" + generated.id)
        bitmap.recycle()
        val bytes = context.contentResolver.openInputStream(Uri.parse(generated.uri))!!.use { it.readBytes() }
        assertEquals(137, bytes[0].toInt() and 255)
        assertArrayEquals(before, store.readBytes(page.storageKey))
        assertTrue(generated.uri.contains("publication-assets"))
    }

    @Test fun failingResolverDoesNotBlockTextAndTablePackage() = runBlocking {
        val publication = NaverBlogPackageGenerator(
            PublicationEvidenceResolver { _, _, _ -> throw IllegalStateException() },
            PublicationAssetStore(context),
        ).generate(PublicationSamples.source())
        publication.draft.generatedImages.forEach {
            assetDirectories += File(context.filesDir, "receipt-scanner/publication-assets/" + it.id)
        }
        assertTrue(publication.draft.title.isNotBlank())
        assertEquals(1, publication.draft.generatedImages.size)
        assertTrue(publication.draft.evidenceImages.isEmpty())
        assertTrue(publication.notices.isNotEmpty())
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
