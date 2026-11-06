package com.pricetrace.receiptscanner.information

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Opt-in hosted private-inbox integration. IDs are durably recorded before writes for scoped cleanup. */
class InformationDeliveryLiveTest {
    @Test fun deliverAndReplayEachOwnerThroughProductionGateway() = runTest(timeout = 180.seconds) {
        val envFile = System.getenv("INFORMATION_DELIVERY_LIVE_ENV").orEmpty()
        assumeTrue("Explicit local env path is required for hosted fixture writes", envFile.isNotBlank())
        val env = Files.readAllLines(Path.of(envFile)).mapNotNull { raw ->
            val line = raw.trim(); if (line.startsWith('#') || '=' !in line) null else {
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                key to value
            }
        }.toMap()
        val services = mapOf(InformationOwner.FITNESS to "NUTRITION", InformationOwner.CASHOS to "CASHOS",
            InformationOwner.PRICETRACE to "PRICETRACE", InformationOwner.HEALTHTRACE to "HEALTHTRACE")
        val configs = services.mapValues { (_, prefix) -> InformationConnection(
            env["${prefix}_SUPABASE_URL"].orEmpty(), env["${prefix}_SUPABASE_PUBLISHABLE_KEY"].orEmpty().ifBlank { env["${prefix}_SUPABASE_ANON_KEY"].orEmpty() }) }.toMutableMap()
        services.forEach { (owner, prefix) ->
            require(configs.getValue(owner).url.isNotBlank() && !env["${prefix}_EMAIL"].isNullOrBlank() && !env["${prefix}_PASSWORD"].isNullOrBlank()) { "Live fixture configuration is incomplete for ${owner.wire}" }
        }
        val connections = object : InformationConnectionStore {
            override fun read(owner: InformationOwner) = configs.getValue(owner)
            override fun saveConnection(owner: InformationOwner, url: String, publishableKey: String) { error("Not expected") }
            override fun saveSession(owner: InformationOwner, userId: String, email: String, accessToken: String, refreshToken: String) {
                val current = read(owner); configs[owner] = InformationConnection(current.url, current.publishableKey, userId, email, accessToken, refreshToken)
            }
            override fun clearSession(owner: InformationOwner) { error("Not expected") }
        }
        val gateway = InformationDeliveryGateway(connections)
        val fixtures = mutableListOf<JsonObject>()
        // Keep every run's cleanup manifest, including interrupted or failed runs.
        val evidenceFile = Path.of("..", "build", "information-delivery-qa", "hosted-fixtures-${UUID.randomUUID()}.json").toAbsolutePath()
        Files.createDirectories(evidenceFile.parent)
        for ((owner, prefix) in services) {
            gateway.signIn(owner, env.getValue("${prefix}_EMAIL"), env.getValue("${prefix}_PASSWORD"))
            val record = InformationRecordCodec.decode(buildJsonObject {
                put("schema_version", "information-record.v1"); put("record_id", UUID.randomUUID().toString())
                put("owner_app", owner.wire); put("record_type", "${owner.wire}.observation")
                put("title", "합성 통합 시험 — 자동 정리 대상"); put("captured_at", Instant.now().toString())
                putJsonArray("sources") { add(buildJsonObject { put("source_id", "integration-fixture"); put("kind", "manual") }) }
                putJsonArray("fields") { add(buildJsonObject { put("key", "fixture_value"); put("value_type", "decimal"); put("value", "12345678901234567890.0010") }) }
                putJsonArray("references") { }
            }.toString())
            val prepared = gateway.prepare(record)
            fixtures += buildJsonObject {
                put("owner", owner.wire); put("origin", prepared.target.origin); put("user_id", prepared.target.userId)
                put("record_id", record.id); put("fingerprint", record.fingerprint); put("payload_text", record.canonical)
            }
            Files.writeString(evidenceFile, JsonArray(fixtures).toString())
            val receipt = gateway.deliver(record, prepared)
            assertFalse(receipt.duplicate)
            assertTrue(gateway.deliver(record, gateway.prepare(record)).duplicate)
        }
        println("Hosted private inbox delivery and replay passed for all four owners; scoped fixture cleanup remains required: $evidenceFile")
    }
}
