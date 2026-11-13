package com.pricetrace.receiptocr

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptocr.information.CarePlaceViewModel
import com.pricetrace.receiptocr.information.informationConnections
import com.pricetrace.receiptscanner.information.InformationDeliveryGateway
import com.pricetrace.receiptscanner.information.InformationOwner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in device QA. The runner must create an owned synthetic payment and clean the returned place ID. */
class CarePlaceLiveUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reviewedInstitutionRegistersOnceAndLinksExistingPayment() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("careLive") == "true")
        val paymentId = requireNotNull(args.getString("carePaymentId"))
        val name = requireNotNull(args.getString("careName"))
        require(name.startsWith("QA-CARE-"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Optional private file supplied by the operator; never an instrumentation argument.
        val authFile = File(context.filesDir, "care-live-auth.json")
        if (authFile.exists()) {
            try {
                val auth = Json.parseToJsonElement(authFile.readText()).jsonObject
                val gateway = InformationDeliveryGateway(informationConnections(context))
                runBlocking {
                    for (owner in listOf(InformationOwner.PRICETRACE, InformationOwner.CASHOS)) {
                        val login = auth.getValue(owner.wire).jsonObject
                        gateway.signIn(owner, login.getValue("email").jsonPrimitive.content, login.getValue("password").jsonPrimitive.content)
                    }
                }
            } finally { authFile.delete() }
        }
        val draftName = "care_place_live_qa"
        val prefs = context.getSharedPreferences(draftName, 0)
        prefs.edit().clear().commit()
        val model = CarePlaceViewModel(context.applicationContext as Application, draftName)
        model.prefill(name, "자동 검증 전용 주소 (실제 기관 아님)", "")
        compose.setContent { ReceiptOcrTheme { CarePlaceScreen(model, {}) } }
        try {
            scroll("care_register"); compose.onNodeWithTag("care_register").assertIsNotEnabled()
            scroll("care_public_confirm"); compose.onNodeWithTag("care_public_confirm").performClick()
            scroll("care_register"); compose.onNodeWithTag("care_register").performClick()
            compose.waitUntil(30_000) { !model.busy }
            assertNull(model.error)
            val place = requireNotNull(model.selected)
            File(context.getExternalFilesDir(null), "care-place-live-result.json").writeText(place.json().toString())
            // Retry the same draft; a second UI submit must retain the canonical ID.
            scroll("care_public_confirm"); compose.onNodeWithTag("care_public_confirm").performClick()
            scroll("care_register"); compose.onNodeWithTag("care_register").performClick()
            compose.waitUntil(30_000) { !model.busy }
            assertNull(model.error); assertEquals(place.id, model.selected?.id)
            assertTrue(model.notice?.contains("중복") == true)
            scroll("care_load_payments"); compose.onNodeWithTag("care_load_payments").performClick()
            compose.waitUntil(30_000) { !model.busy }
            assertNull(model.error); assertTrue(model.expenses.any { it.id == paymentId })
            scroll("care_expense_$paymentId"); compose.onNodeWithTag("care_expense_$paymentId").performClick()
            scroll("care_link"); compose.onNodeWithTag("care_link").assertIsNotEnabled()
            scroll("care_payment_confirm"); compose.onNodeWithTag("care_payment_confirm").performClick()
            scroll("care_link"); compose.onNodeWithTag("care_link").performClick()
            compose.waitUntil(30_000) { !model.busy }
            assertNull(model.error); assertTrue(model.notice?.contains("연결했습니다") == true)
            File(context.getExternalFilesDir(null), "care-place-linked.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { prefs.edit().clear().commit() }
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("care_content").performScrollToNode(hasTestTag(tag))
}
