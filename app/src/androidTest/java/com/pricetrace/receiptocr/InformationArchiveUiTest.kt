package com.pricetrace.receiptocr

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptocr.information.*
import com.pricetrace.receiptscanner.information.InformationRecordCodec
import com.pricetrace.receiptscanner.workflow.OcrWorkflowType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class InformationArchiveUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun prescriptionHandoffRequiresReviewAndKeepsOriginalPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = InformationArchiveViewModel(context.applicationContext as Application, File(context.cacheDir, "prescription-ui-${UUID.randomUUID()}"))
        compose.waitUntil(10_000) { !model.busy }
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val source = model.store.capture(bytes.inputStream(), "synthetic-prescription.png", "image/png")
        val id = UUID.randomUUID().toString()
        val record = InformationRecordCodec.decode("""{"schema_version":"information-record.v1","record_id":"$id","owner_app":"healthtrace","record_type":"healthtrace.prescription","title":"Synthetic prescription QA","captured_at":"2026-11-14T10:00:00+09:00","sources":[{"source_id":"text","kind":"text","text":"synthetic"}],"fields":[{"key":"prescription","value_type":"json","source_ids":["text"],"value":{"schema_version":"healthtrace.prescription.v1","prescribed_on":null,"medicines":[{"name":"코데날정"}]}}],"references":[]}""")
        model.store.save(record)
        var photoId: String? = null
        var prescriptionId: String? = null
        compose.setContent { ReceiptOcrTheme {
            InformationArchiveScreen(model, {}, {}, {}, {}, {},
                onHealthSource = { photoId = it.id }, onHealthPrescription = { prescriptionId = it })
        } }
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(10_000) { !model.busy && model.snapshot.sources.size == 1 }
        scroll("health_prescription_photo"); compose.onNodeWithTag("health_prescription_photo").performClick()
        compose.runOnIdle { assertEquals(source.id, photoId); model.pane = 2; model.selectedRecordId = id }
        scroll("health_prescription_json"); compose.onNodeWithTag("health_prescription_json").assertIsNotEnabled()
        compose.runOnIdle { model.review(id) }
        compose.waitUntil(10_000) { !model.busy && model.snapshot.records.single().reviewed }
        scroll("health_prescription_json"); compose.onNodeWithTag("health_prescription_json").performClick()
        compose.runOnIdle {
            assertEquals(id, prescriptionId)
            val output = ByteArrayOutputStream(); model.store.exportSource(source.id, output)
            assertArrayEquals(bytes, output.toByteArray())
        }
    }

    @Test fun sourceToHealthRecordReviewAndExportWorksOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = InformationArchiveViewModel(context.applicationContext as Application, File(context.cacheDir, "information-ui-${UUID.randomUUID()}"))
        var exported: String? = null
        compose.setContent { ReceiptOcrTheme {
            InformationArchiveScreen(model, {}, {}, { id ->
                val output = ByteArrayOutputStream(); model.store.exportRecord(id, output); exported = output.toString("UTF-8")
            }, {}, {})
        } }
        compose.waitUntil(10_000) { !model.busy }
        scroll("information_source_text")
        compose.onNodeWithTag("information_source_text").performTextInput("원문 보존 검사: 7.5000 시간")
        scroll("information_capture_text"); compose.onNodeWithTag("information_capture_text").performClick()
        compose.waitUntil(10_000) { !model.busy && model.snapshot.sources.size == 1 }
        compose.onNodeWithTag("information_content").performScrollToNode(hasText("이 원문으로 구조화"))
        compose.onNodeWithText("이 원문으로 구조화").performClick()
        scroll("information_owner_healthtrace"); compose.onNodeWithTag("information_owner_healthtrace").performClick()
        scroll("information_title"); compose.onNodeWithTag("information_title").performTextInput("수면 시간 관측")
        scroll("information_new_draft"); compose.onNodeWithTag("information_new_draft").performClick()
        scroll("information_field_key"); compose.onNodeWithTag("information_field_key").performTextInput("sleep_duration")
        scroll("information_type_decimal"); compose.onNodeWithTag("information_type_decimal").performClick()
        scroll("information_field_value"); compose.onNodeWithTag("information_field_value").performTextInput("12345678901234567890.0010")
        scroll("information_field_unit"); compose.onNodeWithTag("information_field_unit").performTextInput("hour")
        scroll("information_add_field"); compose.onNodeWithTag("information_add_field").performClick()
        scroll("information_save_draft"); compose.onNodeWithTag("information_save_draft").assertIsNotEnabled()
        scroll("information_owner_confirm"); compose.onNodeWithTag("information_owner_confirm").performClick()
        scroll("information_save_draft"); compose.onNodeWithTag("information_save_draft").performClick()
        compose.waitUntil(10_000) { !model.busy && model.snapshot.records.size == 1 }
        val entry = model.snapshot.records.single()
        assertEquals("healthtrace", entry.record.owner.wire); assertTrue(entry.reviewed)
        assertEquals("12345678901234567890.0010", entry.record.payload.getValue("fields").jsonArray.last().jsonObject.getValue("value").jsonPrimitive.content)
        compose.onNodeWithTag("information_record_${entry.record.id}").performClick()
        scroll("information_export_record"); compose.onNodeWithTag("information_export_record").performClick()
        compose.runOnIdle {
            assertEquals(entry.record.canonical, InformationRecordCodec.decode(exported!!).canonical)
            val source = model.snapshot.sources.single(); val output = ByteArrayOutputStream(); model.store.exportSource(source.id, output)
            assertEquals("원문 보존 검사: 7.5000 시간", output.toString("UTF-8"))
        }
        compose.onNodeWithTag("information_content").performScrollToIndex(0)
        val capture = File(context.getExternalFilesDir(null), "information-archive-mobile.png")
        capture.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun homeEntryIsReachableAndArchiveIsPrivateActivity() {
        var opened = false
        compose.setContent { ReceiptOcrTheme {
            SessionListScreen(emptyList(), OcrWorkflowType.PRICE_TRACE_RECEIPT, false,
                {}, {}, {}, {}, {}, {}, {}, onOpenInformationArchive = { opened = true })
        } }
        compose.onNodeWithTag("session_list").performScrollToNode(hasTestTag("information_archive_button"))
        compose.onNodeWithTag("information_archive_button").performClick()
        compose.runOnIdle { assertTrue(opened) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") val info = context.packageManager.getActivityInfo(ComponentName(context, InformationArchiveActivity::class.java), 0)
        assertFalse(info.exported)
    }

    private fun scroll(tag: String) = compose.onNodeWithTag("information_content").performScrollToNode(hasTestTag(tag))
}
