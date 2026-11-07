package com.pricetrace.receiptocr

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptocr.fitness.*
import com.pricetrace.receiptocr.information.*
import com.pricetrace.receiptscanner.information.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class InformationDeliveryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun explicitAccountApprovalDeliversExactlyOnceAndRetainsSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "delivery-ui-${UUID.randomUUID()}")
        val record = InformationRecordCodec.decode("""{"schema_version":"information-record.v1","record_id":"77777777-7777-4777-8777-777777777777","owner_app":"fitness","record_type":"fitness.observation","title":"합성 체중 관측","captured_at":"2026-11-06T12:00:00Z","sources":[{"source_id":"manual-1","kind":"manual"}],"fields":[{"key":"weight_kg","value_type":"decimal","value":"70.500","unit":"kg"}],"references":[]}""")
        val store = AndroidInformationStore(directory); store.save(record); store.markReviewed(record.id)
        val userId = "88888888-8888-4888-8888-888888888888"
        val connections = object : InformationConnectionStore {
            override fun read(owner: InformationOwner) = InformationConnection("https://fixture.supabase.co", "sb_publishable_fixture_123456789", userId, "fixture@example.test", "fixture-access", "fixture-refresh")
            override fun saveConnection(owner: InformationOwner, url: String, publishableKey: String) = error("Not expected")
            override fun saveSession(owner: InformationOwner, userId: String, email: String, accessToken: String, refreshToken: String) = error("Not expected")
            override fun clearSession(owner: InformationOwner) = error("Not expected")
        }
        val requests = CopyOnWriteArrayList<NutritionHttpRequest>()
        val gateway = InformationDeliveryGateway(connections, NutritionHttpTransport { request ->
            requests += request
            NutritionHttpResponse(200, when {
                request.url.endsWith("/auth/v1/user") -> "{\"id\":\"$userId\",\"email\":\"fixture@example.test\"}"
                request.method == "POST" -> "{\"record_id\":\"${record.id}\",\"duplicate\":false,\"content_fingerprint\":\"${record.fingerprint}\"}"
                else -> buildJsonArray { add(buildJsonObject { put("payload_text", record.canonical) }) }.toString()
            })
        })
        val model = InformationArchiveViewModel(context.applicationContext as Application, directory, gateway)
        compose.setContent { ReceiptOcrTheme { InformationArchiveScreen(model, {}, {}, {}, {}, {}) } }
        compose.waitUntil(10_000) { !model.busy && model.snapshot.records.size == 1 }
        compose.onNodeWithTag("information_tab_2").performClick()
        compose.onNodeWithTag("information_record_${record.id}").performClick()
        scroll("information_open_delivery"); compose.onNodeWithTag("information_open_delivery").performClick()
        compose.waitUntil(10_000) { !model.busy }
        scroll("information_prepare_delivery"); compose.onNodeWithTag("information_prepare_delivery").performClick()
        compose.waitUntil(10_000) { !model.busy && model.deliveryPrepared != null }
        scroll("information_send_delivery"); compose.onNodeWithTag("information_send_delivery").assertIsNotEnabled()
        assertFalse(requests.any { it.method == "POST" })
        val screenshot = File(context.getExternalFilesDir(null), "information-delivery-review.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        scroll("information_delivery_confirm"); compose.onNodeWithTag("information_delivery_confirm").performClick()
        scroll("information_send_delivery"); compose.onNodeWithTag("information_send_delivery").performClick()
        compose.waitUntil(10_000) { !model.busy && model.deliveryHistory.firstOrNull()?.state == InformationDeliveryState.DELIVERED }
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(record.canonical, model.store.snapshot().records.single().record.canonical)
        assertTrue(requests.single { it.method == "POST" }.url.endsWith("/rpc/fitness_ingest_information_record_v1"))
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("information_content").performScrollToNode(hasTestTag(tag))
}
