package com.pricetrace.receiptocr.publishing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pricetrace.receiptocr.publishing.share.*
import com.pricetrace.receiptocr.publishing.ui.PublicationSamples
import com.pricetrace.receiptscanner.publishing.generator.BlogDraftGenerator
import com.pricetrace.receiptscanner.publishing.model.PublicationEvidenceImage
import com.pricetrace.receiptscanner.publishing.model.PublicationGeneratedImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicationShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val draft = BlogDraftGenerator().generate(PublicationSamples.source())
    private val exporter = PublicationShareExporter(context) {}

    @Test fun plainTextShareContainsTitleAndBodyWithoutUriGrants() {
        val intent = exporter.buildIntent(draft, PublicationShareContent.TEXT)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(draft.fullText, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(intent.clipData)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun onePngUsesSendAndTemporaryReadGrant() {
        val image = PublicationGeneratedImage("table", "content://publication.test/table")
        val intent = exporter.buildIntent(draft.copy(generatedImages = listOf(image)), PublicationShareContent.IMAGES)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(Uri.parse(image.uri), intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(1, intent.clipData!!.itemCount)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun mixedOriginalAndGeneratedImagesUseMultipleAndGrantEveryUri() {
        val complete = draft.copy(
            evidenceImages = listOf(PublicationEvidenceImage("original", "content://publication.test/original", "image/jpeg")),
            generatedImages = listOf(PublicationGeneratedImage("table", "content://publication.test/table")),
        )
        val intent = exporter.buildIntent(complete, PublicationShareContent.ALL)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/*", intent.type)
        assertEquals(2, intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.size)
        assertEquals(2, intent.clipData!!.itemCount)
        assertEquals(draft.fullText, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(intent.getPackage())
    }

    @Test fun missingShareAppAndPermissionFailureReturnHelpfulResults() {
        assertTrue(PublicationShareExporter(context) { throw ActivityNotFoundException() }
            .share(draft, PublicationShareContent.TEXT) is PublicationExportResult.Failed)
        assertTrue(PublicationShareExporter(context) { throw SecurityException() }
            .share(draft, PublicationShareContent.TEXT) is PublicationExportResult.Failed)
    }

    @Test fun imageOnlyWithoutImagesIsBlockedAndAllWithoutImagesFallsBackToText() {
        assertTrue(exporter.share(draft, PublicationShareContent.IMAGES) is PublicationExportResult.Failed)
        assertEquals("text/plain", exporter.buildIntent(draft, PublicationShareContent.ALL).type)
    }

    @Test fun fileUrisAreNeverExposedAndChooserCarriesReadPermissions() {
        assertTrue(exporter.share(draft.copy(
            generatedImages = listOf(PublicationGeneratedImage("bad", "file:///private/table.png")),
        ), PublicationShareContent.IMAGES) is PublicationExportResult.Failed)
        var chooser: Intent? = null
        val share = PublicationShareExporter(context) { chooser = it }
        assertEquals(PublicationExportResult.Completed, share.share(draft.copy(
            generatedImages = listOf(PublicationGeneratedImage("ok", "content://publication.test/table")),
        ), PublicationShareContent.ALL))
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        assertEquals(1, chooser!!.clipData!!.itemCount)
        assertTrue(chooser!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
