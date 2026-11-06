package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.information.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopInformationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = DesktopInformationStore(temporary.root.toPath().resolve("archive"))

    @Test fun `arbitrary bytes survive source capture restart and verified export`() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val source = temporary.root.toPath().resolve("unknown.media")
        Files.write(source, bytes)
        val item = store().captureFile(source)
        assertEquals(bytes.size.toLong(), item.size)
        Files.delete(source)
        val restarted = store()
        assertEquals(item, restarted.snapshot().sources.single())
        val output = temporary.root.toPath().resolve("restored.media")
        restarted.exportSource(item.id, output)
        assertArrayEquals(bytes, Files.readAllBytes(output))
    }

    @Test fun `failed structured import retains raw original`() {
        val input = temporary.root.toPath().resolve("unsupported.json")
        Files.writeString(input, "{\"arbitrary\":true}")
        val store = store()
        assertTrue(runCatching { store.importFile(input) }.isFailure)
        assertEquals(1, store.snapshot().sources.size)
        assertTrue(store.snapshot().records.isEmpty())
    }

    @Test fun `retry is idempotent changed payload conflicts and export requires review`() {
        val store = store()
        val raw = addInformationField(newInformationDraft(InformationOwner.HEALTHTRACE, null), "pressure", "decimal", "120.00", "mmHg")
        val record = InformationRecordCodec.decode(raw)
        assertEquals(InformationSaveResult.CREATED, store.save(record))
        assertEquals(InformationSaveResult.DUPLICATE, store.save(InformationRecordCodec.decode(record.canonical)))
        assertTrue(runCatching { store.save(InformationRecordCodec.decode(raw.replace("120.00", "125.00"))) }.isFailure)
        val target = temporary.root.toPath().resolve("out.json")
        assertTrue(runCatching { store.exportRecord(record.id, target) }.isFailure)
        assertFalse(Files.exists(target))
        store.markReviewed(record.id)
        store().exportRecord(record.id, target)
        assertEquals(record.canonical, InformationRecordCodec.decode(Files.readString(target)).canonical)
        assertEquals(1, store.snapshot().records.size)
    }

    @Test fun `correction has a new identity preserving original`() {
        val original = InformationRecordCodec.decode(newInformationDraft(InformationOwner.CASHOS, null))
        val correction = InformationRecordCodec.decode(correctionInformationDraft(original))
        assertNotEquals(original.id, correction.id)
        assertTrue(correction.canonical.contains(original.id))
        val store = store(); store.save(original); store.save(correction)
        assertEquals(2, store.snapshot().records.size)
    }

    @Test fun `corrupt sources do not disappear without explanation and unsafe output refused`() {
        val store = store()
        Files.writeString(store.directory.resolve("sources/broken.json"), "not json")
        assertEquals(1, store.snapshot().errors.size)
        val item = store.captureText("어떤 내용도 원문으로 보관", "evidence.txt")
        assertTrue(runCatching { store.exportSource(item.id, store.directory.resolve("overwrite")) }.isFailure)
        val blob = store.directory.resolve("blobs").resolve(item.hash)
        Files.writeString(blob, "tampered")
        assertTrue(runCatching { store.exportSource(item.id, temporary.root.toPath().resolve("out.txt")) }.isFailure)
    }

    @Test fun `field editor validates type duplicate key and owner boundary`() {
        val draft = newInformationDraft(InformationOwner.FITNESS, null)
        assertTrue(runCatching { addInformationField(draft, "note", "text", "duplicate", "") }.isFailure)
        assertTrue(runCatching { addInformationField(draft, "healthtrace.sleep", "decimal", "8", "h") }.isFailure)
        assertTrue(runCatching { addInformationField(draft, "flag", "boolean", "\"true\"", "") }.isFailure)
    }
}
