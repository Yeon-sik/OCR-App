package com.pricetrace.receiptocr.information

import com.pricetrace.receiptscanner.information.InformationRecordCodec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class AndroidInformationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = AndroidInformationStore(File(temporary.root, "information"))
    private fun fixture(): String {
        val vectors = Json.parseToJsonElement(File("../contracts/information-record.v1.cases.json").readText()).jsonObject
        return InformationRecordCodec.pretty(vectors.getValue("accepted").jsonArray.first().jsonObject.getValue("record"))
    }

    @Test fun arbitraryBytesSurviveRestartAndHashCheckedExport() {
        val bytes = byteArrayOf(0, -1, -2, 1, 92, 13, 10)
        val source = store().capture(bytes.inputStream(), "../unrecognized.bin")
        assertEquals("unrecognized.bin", source.name)
        assertEquals(bytes.size.toLong(), source.size)
        val restarted = store()
        assertEquals(source, restarted.snapshot().sources.single())
        val output = ByteArrayOutputStream(); restarted.exportSource(source.id, output)
        assertArrayEquals(bytes, output.toByteArray())
        File(temporary.root, "information/blobs/${source.sha256}").writeText("tampered")
        assertThrows(IllegalArgumentException::class.java) { restarted.exportSource(source.id, ByteArrayOutputStream()) }
    }

    @Test fun unsupportedAndMalformedJsonIsArchivedBeforeRejection() {
        val bytes = "not valid JSON".toByteArray()
        assertThrows(Exception::class.java) { store().importRecord(bytes.inputStream(), "bad.json") }
        val snapshot = store().snapshot()
        assertEquals(1, snapshot.sources.size); assertTrue(snapshot.records.isEmpty())
        val output = ByteArrayOutputStream(); store().exportSource(snapshot.sources.single().id, output)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun oversizedJsonIsStillAnOpaqueArchivedSource() {
        val bytes = ByteArray(InformationRecordCodec.MAX_BYTES + 1) { 32 }
        assertThrows(IllegalArgumentException::class.java) { store().importRecord(bytes.inputStream(), "large.json") }
        assertEquals(bytes.size.toLong(), store().snapshot().sources.single().size)
    }

    @Test fun invalidUtf8IsNotSilentlyReplacedDuringImport() {
        val bytes = byteArrayOf(-61, 40)
        assertThrows(Exception::class.java) { store().importRecord(bytes.inputStream(), "broken.json") }
        assertEquals(1, store().snapshot().sources.size); assertTrue(store().snapshot().records.isEmpty())
    }

    @Test fun identicalReplayCreatesOneRecordButChangedPayloadConflicts() {
        val record = InformationRecordCodec.decode(fixture())
        assertEquals(AndroidInformationSave.CREATED, store().save(record))
        assertEquals(AndroidInformationSave.DUPLICATE, store().save(record))
        val changed = InformationRecordCodec.decode(fixture().replace(record.title, "changed"))
        assertThrows(IllegalArgumentException::class.java) { store().save(changed) }
        assertEquals(record.canonical, store().snapshot().records.single().record.canonical)
    }

    @Test fun exportRequiresReviewAndPreservesExactDecimalJsonAndOwner() {
        val text = fixture().replace("\"nested\": [", "\"huge\": 90071992547409931234567890, \"nested\": [")
        val record = InformationRecordCodec.decode(text)
        store().save(record)
        assertThrows(IllegalArgumentException::class.java) { store().exportRecord(record.id, ByteArrayOutputStream()) }
        store().markReviewed(record.id)
        val restarted = store(); assertTrue(restarted.snapshot().records.single().reviewed)
        val output = ByteArrayOutputStream(); restarted.exportRecord(record.id, output)
        val exported = output.toString("UTF-8")
        assertTrue(exported.contains("12345678901234567890.0010")); assertTrue(exported.contains("90071992547409931234567890"))
        assertEquals(record.canonical, InformationRecordCodec.decode(exported).canonical)
    }

    @Test fun interruptedStreamLeavesNoPublishedSourceOrPartialBlob() {
        val broken = object : InputStream() { override fun read(): Int = throw IOException("fixture read failure") }
        assertThrows(IOException::class.java) { store().capture(broken, "interrupted.bin") }
        assertTrue(store().snapshot().sources.isEmpty())
        assertTrue(File(temporary.root, "information/blobs").listFiles().orEmpty().isEmpty())
    }

    @Test fun nearLimitRecordSurvivesPersistenceAndReimportWithoutPrettyExpansion() {
        val base = Json.parseToJsonElement(fixture()).jsonObject
        val payload = JsonObject(base + ("fields" to buildJsonArray {
            add(buildJsonObject {
                put("key", "large_payload"); put("value_type", "json")
                putJsonArray("value") { repeat(200_000) { add(0) } }
                putJsonArray("source_ids") { add(base.getValue("sources").jsonArray.first().jsonObject.getValue("source_id")) }
            })
        }))
        val record = InformationRecordCodec.decode(InformationRecordCodec.canonical(payload))
        assertTrue(InformationRecordCodec.pretty(payload).toByteArray().size > InformationRecordCodec.MAX_BYTES)
        store().save(record); store().markReviewed(record.id)
        val restarted = store(); val output = ByteArrayOutputStream()
        restarted.exportRecord(record.id, output)
        assertTrue(output.size() <= InformationRecordCodec.MAX_BYTES)
        assertEquals(record.canonical, InformationRecordCodec.decode(output.toString("UTF-8")).canonical)
        assertEquals(AndroidInformationSave.DUPLICATE, restarted.importRecord(output.toByteArray().inputStream(), "large-valid.json"))
    }

    @Test fun existingReceiptFilesAreUnchanged() {
        val receipt = File(temporary.root, "session.json").apply { writeText("receipt fixture") }
        store().captureText("unassigned original", "source.txt")
        store().save(InformationRecordCodec.decode(fixture()))
        assertEquals("receipt fixture", receipt.readText())
    }
}
