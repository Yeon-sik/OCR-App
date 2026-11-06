package com.pricetrace.receiptscanner.information

import com.pricetrace.receiptocr.fitness.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class InformationDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val userId = "88888888-8888-4888-8888-888888888888"
    private fun record(owner: InformationOwner = InformationOwner.FITNESS): InformationRecord {
        val vectors = InformationRecordCodec.parseObject(Files.readString(Path.of("..", "contracts", "information-record.v1.cases.json")))
        return vectors.getValue("accepted").jsonArray.map { InformationRecordCodec.decode(it.jsonObject.getValue("record").toString()) }.single { it.owner == owner }
    }
    private inner class Connections : InformationConnectionStore {
        var config = InformationConnection("https://example.supabase.co", "sb_publishable_fixture_key_123456", userId, "fixture@example.test", "secret-access", "secret-refresh")
        override fun read(owner: InformationOwner) = config
        override fun saveConnection(owner: InformationOwner, url: String, publishableKey: String) { config = InformationConnection(url, publishableKey) }
        override fun saveSession(owner: InformationOwner, userId: String, email: String, accessToken: String, refreshToken: String) { config = InformationConnection(config.url, config.publishableKey, userId, email, accessToken, refreshToken) }
        override fun clearSession(owner: InformationOwner) { config = InformationConnection(config.url, config.publishableKey) }
    }
    private inner class Server : NutritionHttpTransport {
        val requests = mutableListOf<NutritionHttpRequest>()
        var stored: String? = null
        var loseFirstReply = false
        var responseId: String? = null
        var afterPost: (() -> Unit)? = null
        var returnWrongPayload = false
        override suspend fun execute(request: NutritionHttpRequest): NutritionHttpResponse {
            requests += request
            return when {
                request.url.endsWith("/auth/v1/user") -> NutritionHttpResponse(200, "{\"id\":\"$userId\",\"email\":\"fixture@example.test\"}")
                request.url.contains("/rest/v1/rpc/") -> {
                    val body = InformationRecordCodec.parseObject(request.body!!, 4 * InformationRecordCodec.MAX_BYTES)
                    assertEquals(userId, body.getValue("p_expected_user_id").jsonPrimitive.content)
                    val payload = body.getValue("p_payload_text").jsonPrimitive.content
                    val duplicate = stored != null
                    if (stored != null && stored != payload) return NutritionHttpResponse(409, "{}")
                    stored = payload; afterPost?.invoke()
                    if (loseFirstReply && !duplicate) throw IOException("untrusted secret error")
                    val decoded = InformationRecordCodec.decode(payload)
                    NutritionHttpResponse(200, buildJsonObject {
                        put("record_id", responseId ?: decoded.id); put("duplicate", duplicate); put("content_fingerprint", decoded.fingerprint)
                    }.toString())
                }
                else -> NutritionHttpResponse(200, buildJsonArray { add(buildJsonObject { put("payload_text", if (returnWrongPayload) record().canonical else requireNotNull(stored)) }) }.toString())
            }
        }
    }
    @Test fun `each owner routes only to its inbox after authenticated account confirmation`() = runTest {
        val routes = mapOf(InformationOwner.FITNESS to "fitness_ingest_information_record_v1", InformationOwner.CASHOS to "finance_ingest_information_record_v1",
            InformationOwner.PRICETRACE to "ingest_pricetrace_information_record_v1", InformationOwner.HEALTHTRACE to "health_ingest_information_record_v1")
        routes.forEach { (owner, rpc) ->
            val server = Server(); val gateway = InformationDeliveryGateway(Connections(), server); val record = record(owner)
            val prepared = gateway.prepare(record)
            assertEquals(0, server.requests.count { it.method == "POST" })
            assertFalse(gateway.deliver(record, prepared).duplicate)
            assertEquals(record.canonical, server.stored)
            assertTrue(server.requests.single { it.method == "POST" }.url.endsWith("/rpc/$rpc"))
            assertFalse(server.requests.any { it.url.contains("apply_") || it.url.contains("projection") })
        }
    }
    @Test fun `lost reply survives restart and retry does not add another remote record`() = runTest {
        val directory = temporary.newFolder("journal"); val server = Server().apply { loseFirstReply = true }
        val gateway = InformationDeliveryGateway(Connections(), server); val record = record(); val ready = gateway.prepare(record)
        assertTrue(runCatching { InformationDeliveryJournal(directory).deliver(record, ready, gateway) }.isFailure)
        val restarted = InformationDeliveryJournal(directory)
        assertEquals(InformationDeliveryState.RETRY_REQUIRED, restarted.attempts(record.id).single().state)
        assertTrue(restarted.deliver(record, gateway.prepare(record), gateway).duplicate)
        assertEquals(InformationDeliveryState.DELIVERED, restarted.attempts(record.id).single().state)
        directory.listFiles()!!.filter { it.extension == "json" }.forEach {
            assertFalse(it.readText().contains("secret-")); assertFalse(it.readText().contains("payload_text"))
        }
    }
    @Test fun `account and project changes invalidate confirmation before any write`() = runTest {
        val connections = Connections(); val server = Server(); val gateway = InformationDeliveryGateway(connections, server); val record = record()
        val prepared = gateway.prepare(record)
        connections.config = InformationConnection("https://other.supabase.co", connections.config.publishableKey, userId, "fixture@example.test", "new-access", "new-refresh")
        assertTrue(runCatching { gateway.deliver(record, prepared) }.isFailure)
        assertTrue(server.requests.none { it.method == "POST" })
        connections.config = InformationConnection("https://example.supabase.co", connections.config.publishableKey, "99999999-9999-4999-8999-999999999999", "other@example.test", "other", "refresh")
        assertTrue(runCatching { gateway.deliver(record, prepared) }.isFailure)
        assertNull(server.stored)
    }
    @Test fun `changed source or mismatched server receipt never reports delivered`() = runTest {
        val server = Server(); val gateway = InformationDeliveryGateway(Connections(), server); val record = record()
        val prepared = gateway.prepare(record)
        val changed = InformationRecordCodec.decode(record.canonical.replace("12345678901234567890.0010", "123"))
        assertTrue(runCatching { gateway.deliver(changed, prepared) }.isFailure)
        assertNull(server.stored)
        server.responseId = "99999999-9999-4999-8999-999999999999"
        assertTrue(runCatching { gateway.deliver(record, prepared) }.isFailure)
    }
    @Test fun `session changes during delivery keep status unresolved`() = runTest {
        val connections = Connections(); val server = Server().apply { afterPost = { connections.clearSession(InformationOwner.FITNESS) } }
        val gateway = InformationDeliveryGateway(connections, server); val record = record(); val journal = InformationDeliveryJournal(temporary.newFolder())
        assertTrue(runCatching { journal.deliver(record, gateway.prepare(record), gateway) }.isFailure)
        assertEquals(InformationDeliveryState.RETRY_REQUIRED, journal.attempts(record.id).single().state)
    }
    @Test fun `stored payload is compared and escaped response envelope may exceed one MiB`() = runTest {
        val base = record()
        val large = InformationRecordCodec.decode(base.canonical.replace("\"보존\"", JsonPrimitive("\"".repeat(300000)).toString()))
        assertTrue(large.canonical.toByteArray().size < InformationRecordCodec.MAX_BYTES)
        val server = Server(); val gateway = InformationDeliveryGateway(Connections(), server)
        gateway.deliver(large, gateway.prepare(large))
        server.returnWrongPayload = true
        assertTrue(runCatching { gateway.deliver(large, gateway.prepare(large)) }.isFailure)
    }
    @Test fun `refresh is bound to original account and saves rotated tokens without logging them`() = runTest {
        val connections = Connections(); var refreshCalls = 0
        val gateway = InformationDeliveryGateway(connections, NutritionHttpTransport { request ->
            when {
                request.url.contains("grant_type=refresh_token") -> { refreshCalls++; NutritionHttpResponse(200,
                    "{\"user\":{\"id\":\"$userId\",\"email\":\"fixture@example.test\"},\"access_token\":\"rotated\",\"refresh_token\":\"rotated-refresh\"}") }
                request.headers["Authorization"] == "Bearer secret-access" -> NutritionHttpResponse(401, "{}")
                else -> NutritionHttpResponse(200, "{\"id\":\"$userId\",\"email\":\"fixture@example.test\"}")
            }
        })
        gateway.prepare(record())
        assertEquals(1, refreshCalls); assertEquals("rotated", connections.config.accessToken)
        assertFalse(connections.config.toString().contains("rotated"))
    }
}
