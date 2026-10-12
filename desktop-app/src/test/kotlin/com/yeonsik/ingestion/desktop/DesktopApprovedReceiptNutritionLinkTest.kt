package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in repair of the existing owner-approved GS record; never repeats source imports. */
class DesktopApprovedReceiptNutritionLinkTest {
    @Test fun approvedSourceLinkAndAuthenticatedReads() = runBlocking {
        val ingestionId = System.getenv("YEONSIK_APPROVED_RECEIPT_LINK_ID").orEmpty()
        assumeTrue(ingestionId.isNotBlank())
        val store = DesktopSessionStore()
        val original = requireNotNull(store.loadRecord(ingestionId))
        assertEquals(original.session.canonicalFingerprint, original.session.verifiedCanonicalFingerprint)
        val before = original.session.projections.associateBy { it.projection }
        val controller = DesktopIngestionController(store)
        controller.load(ingestionId)
        assertNull(controller.state.value.error)
        controller.submit(setOf(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK))
        assertNull(controller.state.value.error)
        val after = controller.state.value.session!!.projections.associateBy { it.projection }
        val link = after.getValue(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK)
        assertEquals(link.lastError, ProjectionStatus.UPLOADED, link.status)
        assertEquals(IngestionReviewStatus.READY, controller.state.value.session!!.reviewStatus)
        for (projection in listOf(IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            IngestionProjection.CASHOS_RECEIPT, IngestionProjection.FITNESS_NUTRITION)) {
            assertEquals(projection.name, before[projection]?.remoteId, after[projection]?.remoteId)
            assertEquals(projection.name, before[projection]?.attemptCount, after[projection]?.attemptCount)
        }
        controller.submit(setOf(IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK))
        assertNull(controller.state.value.error)
        assertEquals(link, controller.state.value.session!!.projections.single { it.projection == link.projection })

        val bundle = DesktopProjectionBundle()
        assertTrue(bundle.ensureAuthenticated(setOf(IngestionProjection.PRICETRACE_RECEIPT, IngestionProjection.FITNESS_NUTRITION)).isEmpty())
        val pt = bundle.priceTraceStore.read()
        val fitness = bundle.nutritionStore.read()
        val http = HttpClient.newHttpClient()
        fun read(url: String, key: String, token: String, body: String? = null): String {
            val builder = HttpRequest.newBuilder(URI(url)).header("apikey", key).header("Authorization", "Bearer $token")
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            assertEquals("Authenticated REST request failed: ${response.body()}", 200, response.statusCode())
            return response.body()
        }
        val select = "id,user_id,receipt_item_id,observed_at,unit_price_krw,quantity,catalog_product_id,verification_status,store_products!inner(id,user_id,store_id,store_product_code,products!inner(id,user_id,name,purchase_type),stores!inner(id,user_id,name)),receipt_items(receipt_id,total_price_krw)"
        val feed = read(pt.url + "/rest/v1/price_observations?select=$select&user_id=eq.${pt.userId}&verification_status=eq.verified&order=observed_at.desc&limit=1000", pt.publishableKey, pt.accessToken)
        val storeProductId = Json.parseToJsonElement(link.metadataJson!!).jsonArray.single().jsonObject.getValue("store_product_id").jsonPrimitive.content
        val gs = Json.parseToJsonElement(feed).jsonArray.single { it.jsonObject["store_products"]!!.jsonObject["id"]!!.jsonPrimitive.content == storeProductId }.jsonObject
        assertEquals(pt.userId, gs["user_id"]!!.jsonPrimitive.content)
        val nutrition = read(fitness.url + "/rest/v1/rpc/get_verified_receipt_product_nutrition_v1", fitness.publishableKey, fitness.accessToken,
            buildJsonObject { put("p_store_product_id", JsonPrimitive(storeProductId)) }.toString())
        val food = Json.parseToJsonElement(nutrition).jsonArray.single().jsonObject
        assertEquals(before[IngestionProjection.FITNESS_NUTRITION]?.remoteId, food["nutrition_food_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, food["catalog_product_id"])
        assertEquals(155.0, food["nutrition_values"]!!.jsonObject["calories_kcal"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val reports = Path.of("build/reports/approved-receipt-link")
        Files.createDirectories(reports)
        Files.writeString(reports.resolve("authenticated-pt-feed.json"), feed)
        Files.writeString(reports.resolve("authenticated-nutrition-read.json"), nutrition)
        println("APPROVED_RECEIPT_LINK_PASS: existing PT/CashOS/Fitness identities preserved; authenticated owner feed and raw-product nutrition RPC accepted; retry stable")
    }
}
