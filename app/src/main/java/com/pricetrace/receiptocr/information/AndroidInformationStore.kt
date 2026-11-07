package com.pricetrace.receiptocr.information

import com.pricetrace.receiptscanner.information.InformationRecord
import com.pricetrace.receiptscanner.information.InformationRecordCodec
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class AndroidInformationSource(val id: String, val name: String, val sha256: String, val size: Long, val capturedAt: String, val mediaType: String) {
    fun reference(): JsonObject = buildJsonObject {
        put("source_id", id); put("kind", "file"); put("name", name); put("sha256", sha256); put("media_type", mediaType)
    }
}
data class AndroidInformationEntry(val record: InformationRecord, val reviewed: Boolean)
data class AndroidInformationSnapshot(val sources: List<AndroidInformationSource> = emptyList(), val records: List<AndroidInformationEntry> = emptyList(), val errors: List<String> = emptyList())
enum class AndroidInformationSave { CREATED, DUPLICATE }

/** App-private, append-only archive. Streams are copied as opaque bytes before decoding. */
class AndroidInformationStore(private val directory: File) {
    private val sources = File(directory, "sources")
    private val blobs = File(directory, "blobs")
    private val records = File(directory, "records")
    private val reviews = File(directory, "reviews")
    init { listOf(directory, sources, blobs, records, reviews).forEach { require(it.isDirectory || it.mkdirs()) { "보관함 폴더를 만들지 못했습니다." } } }

    fun capture(input: InputStream, displayName: String, mediaType: String = "application/octet-stream"): AndroidInformationSource = exclusive {
        val name = displayName.substringAfterLast('/').substringAfterLast('\\').take(255).ifBlank { "source.bin" }
        val mime = mediaType.take(127).ifBlank { "application/octet-stream" }
        val temp = File.createTempFile(".capture-", ".tmp", blobs)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    require(size <= MAX_SOURCE_BYTES) { "원문 파일은 512 MiB 이하여야 합니다. 원래 파일은 변경하지 않았습니다." }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            val hash = digest.digest().hex()
            val blob = File(blobs, hash)
            if (blob.exists()) require(blob.length() == size && sha256(blob) == hash) { "기존 원문 해시가 일치하지 않습니다." }
            else require(temp.renameTo(blob)) { "원문을 보관하지 못했습니다." }
            val source = AndroidInformationSource(UUID.randomUUID().toString(), name, hash, size, Instant.now().toString(), mime)
            writeNew(File(sources, "${source.id}.json"), buildJsonObject {
                put("id", source.id); put("name", name); put("sha256", hash); put("size", size); put("captured_at", source.capturedAt); put("media_type", mime)
            }.toString())
            source
        } finally { temp.delete() }
    }

    fun captureText(text: String, name: String): AndroidInformationSource {
        require(text.isNotBlank()) { "보관할 원문을 입력하세요." }
        return text.byteInputStream(Charsets.UTF_8).use { capture(it, name, "text/plain; charset=utf-8") }
    }

    fun importRecord(input: InputStream, name: String): AndroidInformationSave {
        val source = capture(input, name, "application/json")
        require(source.size <= InformationRecordCodec.MAX_BYTES) { "구조화 JSON은 1 MiB 이하여야 합니다. 원문 파일은 보관했습니다." }
        val bytes = File(blobs, source.sha256).readBytes()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        return save(InformationRecordCodec.decode(text))
    }

    fun save(record: InformationRecord): AndroidInformationSave = exclusive {
        val path = File(records, "${safeId(record.id)}.json")
        if (path.exists()) {
            require(InformationRecordCodec.decode(path.readText()).canonical == record.canonical) { "같은 ID에 다른 내용이 있습니다. 새 ID의 정정 기록으로 저장하세요." }
            AndroidInformationSave.DUPLICATE
        } else { writeNew(path, InformationRecordCodec.pretty(record)); AndroidInformationSave.CREATED }
    }

    fun markReviewed(id: String) = exclusive {
        val record = readRecord(id)
        val path = File(reviews, "${record.id}.json")
        if (!path.exists()) writeNew(path, buildJsonObject { put("fingerprint", record.fingerprint); put("reviewed_at", Instant.now().toString()) }.toString())
        require(isReviewed(record)) { "검토 상태와 기록이 일치하지 않습니다." }
    }

    fun exportRecord(id: String, output: OutputStream) = exclusive {
        val record = readRecord(id)
        require(isReviewed(record)) { "소유 앱과 출처를 확인한 뒤 검토 완료를 선택하세요." }
        output.write(InformationRecordCodec.pretty(record).toByteArray(Charsets.UTF_8)); output.flush()
    }

    fun reviewedRecord(id: String): InformationRecord = exclusive {
        readRecord(id).also { require(isReviewed(it)) { "소유 앱과 출처를 먼저 검토하세요." } }
    }

    fun exportSource(id: String, output: OutputStream) = exclusive {
        val source = readSource(File(sources, "${safeId(id)}.json"))
        val blob = File(blobs, source.sha256)
        require(blob.exists() && blob.length() == source.size && sha256(blob) == source.sha256) { "원문 무결성 확인에 실패했습니다." }
        blob.inputStream().use { it.copyTo(output) }; output.flush()
    }

    fun snapshot(): AndroidInformationSnapshot = exclusive {
        val errors = mutableListOf<String>()
        val sourceList = sources.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching { readSource(file) }.getOrElse { errors += "읽지 못한 원문 정보: ${file.name}"; null }
        }.sortedByDescending { it.capturedAt }
        val recordList = records.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching { val record = InformationRecordCodec.decode(file.readText()); AndroidInformationEntry(record, isReviewed(record)) }
                .getOrElse { errors += "읽지 못한 기록: ${file.name}"; null }
        }.sortedByDescending { it.record.payload.getValue("captured_at").jsonPrimitive.content }
        AndroidInformationSnapshot(sourceList, recordList, errors)
    }

    private fun readRecord(id: String) = InformationRecordCodec.decode(File(records, "${safeId(id)}.json").readText())
    private fun safeId(id: String): String { require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)); return id }
    private fun isReviewed(record: InformationRecord): Boolean {
        val file = File(reviews, "${record.id}.json")
        return file.exists() && Json.parseToJsonElement(file.readText()).jsonObject["fingerprint"]?.jsonPrimitive?.content == record.fingerprint
    }
    private fun readSource(file: File): AndroidInformationSource {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val source = AndroidInformationSource(root.getValue("id").jsonPrimitive.content, root.getValue("name").jsonPrimitive.content,
            root.getValue("sha256").jsonPrimitive.content, root.getValue("size").jsonPrimitive.long,
            root.getValue("captured_at").jsonPrimitive.content, root.getValue("media_type").jsonPrimitive.content)
        safeId(source.id); require(Regex("[0-9a-f]{64}").matches(source.sha256)); return source
    }
    private fun writeNew(target: File, text: String) {
        require(!target.exists()) { "이미 저장된 파일은 덮어쓸 수 없습니다." }
        val temp = File.createTempFile(".information-", ".tmp", target.parentFile)
        try { FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }; require(temp.renameTo(target)) { "기록 파일을 저장하지 못했습니다." } }
        finally { temp.delete() }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val bytes = ByteArray(64 * 1024); while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun <T> exclusive(action: () -> T): T = synchronized(processLock) {
        RandomAccessFile(File(directory, ".lock"), "rw").use { file -> file.channel.lock().use { action() } }
    }
    companion object { const val MAX_SOURCE_BYTES = 512L * 1024 * 1024; private val processLock = Any() }
}
