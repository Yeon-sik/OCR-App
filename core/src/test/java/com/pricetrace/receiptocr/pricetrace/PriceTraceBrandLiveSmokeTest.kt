package com.pricetrace.receiptocr.pricetrace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in production read/denial smoke; never submits a real candidate. */
class PriceTraceBrandLiveSmokeTest {
    @Test fun publicSearchStrictParseAndAnonymousMutationDenial() = runBlocking {
        val url = System.getenv("PT_BRAND_SMOKE_URL").orEmpty()
        val key = System.getenv("PT_BRAND_SMOKE_KEY").orEmpty()
        assumeTrue("Set PT_BRAND_SMOKE_URL/KEY to run the live read-only smoke", url.isNotBlank() && key.isNotBlank())
        val config = PriceTraceSupabaseConfig(url, key, "")
        val client = PriceTraceBrandClient(HttpsPriceObservationHttpTransport())
        val rows = client.search(config, "", limit = 50)
        assertTrue("Expected deployed public brands", rows.isNotEmpty())
        val denied = client.call(config, "set_product_candidate_brand_resolution_v2", buildJsonObject {
            put("p_client_key", "anonymous-denial-probe")
            put("p_source_field", "brand")
            put("p_observed_value", "probe")
            put("p_brand_id", kotlinx.serialization.json.JsonNull)
            put("p_user_verified", true)
        }, authenticated = false)
        assertTrue("Anonymous mutation must be denied", denied.statusCode in setOf(401, 403))
        println("PT_BRAND_LIVE_STRICT_PARSE_PASS rows=${rows.size}; ANONYMOUS_MUTATION_DENIED=${denied.statusCode}")
    }
}
