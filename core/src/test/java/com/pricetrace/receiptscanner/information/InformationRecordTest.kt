package com.pricetrace.receiptscanner.information

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class InformationRecordTest {
    private fun cases(): JsonObject = Json.parseToJsonElement(Files.readString(Path.of("..", "contracts", "information-record.v1.cases.json"))).jsonObject

    @Test fun `shared conformance vectors accepted and rejected`() {
        cases().getValue("accepted").jsonArray.forEach { item ->
            val record = item.jsonObject.getValue("record").jsonObject
            val decoded = InformationRecordCodec.decode(record.toString())
            assertEquals(record, decoded.payload)
            InformationOwner.entries.forEach { owner ->
                val result = runCatching { InformationRecordCodec.decode(record.toString(), owner) }
                assertEquals(owner == decoded.owner, result.isSuccess)
            }
        }
        cases().getValue("rejected").jsonArray.forEach { item ->
            assertTrue(item.jsonObject.getValue("name").toString(), runCatching {
                InformationRecordCodec.decode(item.jsonObject.getValue("record").toString())
            }.isFailure)
        }
    }

    @Test fun `exact decimal and arbitrary JSON numeric lexemes survive roundtrip`() {
        val original = cases().getValue("accepted").jsonArray.first().jsonObject.getValue("record").toString()
            .replace("\"보존\"", "9007199254740993123456789.0010")
        val record = InformationRecordCodec.decode(original)
        val encoded = InformationRecordCodec.pretty(record)
        assertTrue(encoded.contains("9007199254740993123456789.0010"))
        assertTrue(encoded.contains("12345678901234567890.0010"))
        assertEquals(record.fingerprint, InformationRecordCodec.decode(encoded).fingerprint)
    }

    @Test fun `object ordering does not change identity but array order does`() {
        val original = cases().getValue("accepted").jsonArray.first().jsonObject.getValue("record").jsonObject
        val reversed = JsonObject(original.entries.reversed().associate { it.key to it.value })
        val first = InformationRecordCodec.decode(original.toString())
        assertEquals(first.fingerprint, InformationRecordCodec.decode(reversed.toString()).fingerprint)
        val reorderedFields = JsonObject(original + ("fields" to JsonArray(original.getValue("fields").jsonArray.reversed())))
        assertNotEquals(first.fingerprint, InformationRecordCodec.decode(reorderedFields.toString()).fingerprint)
    }

    @Test fun `size limit counts encoded bytes`() {
        assertTrue(runCatching { InformationRecordCodec.decode("가".repeat(400000)) }.isFailure)
    }

    @Test fun `nonstandard JSON numeric tokens cannot enter arbitrary fields`() {
        val original = cases().getValue("accepted").jsonArray.first().jsonObject.getValue("record").toString()
        listOf("NaN", "Infinity", "undefined", "01", "+1", "1.").forEach { token ->
            assertTrue(token, runCatching { InformationRecordCodec.decode(original.replace("\"보존\"", token)) }.isFailure)
        }
    }

    @Test fun `display formatting cannot make a valid record exceed the export byte limit`() {
        val original = cases().getValue("accepted").jsonArray.first().jsonObject.getValue("record").toString()
        val largeArray = List(100000) { "\"x\"" }.joinToString(",", "[", "]")
        val record = InformationRecordCodec.decode(original.replace("\"보존\"", largeArray))
        val displayed = InformationRecordCodec.pretty(record)
        assertTrue(displayed.toByteArray(Charsets.UTF_8).size <= InformationRecordCodec.MAX_BYTES)
        assertEquals(record.canonical, InformationRecordCodec.decode(displayed).canonical)
    }

    @Test fun `deep hostile structure is rejected before recursive parsing`() {
        val deep = "[".repeat(50000) + "0" + "]".repeat(50000)
        val error = runCatching { InformationRecordCodec.decode(deep) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        val original = cases().getValue("accepted").jsonArray.first().jsonObject.getValue("record").toString()
        InformationRecordCodec.decode(original.replace("\"보존\"", "\"[\\\"{]\\\\\""))
    }
}
