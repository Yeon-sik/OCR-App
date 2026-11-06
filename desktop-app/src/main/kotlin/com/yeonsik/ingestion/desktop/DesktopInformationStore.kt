package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.information.InformationRecord
import com.pricetrace.receiptscanner.information.InformationRecordCodec
import kotlinx.serialization.json.*
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class ArchivedInformationSource(val id: String, val name: String, val hash: String, val size: Long, val capturedAt: String, val mediaType: String) {
    fun wire(): JsonObject = buildJsonObject {
        put("source_id", id); put("kind", "file"); put("name", name)
        put("sha256", hash); put("media_type", mediaType)
    }
}
data class InformationInboxEntry(val record: InformationRecord, val reviewed: Boolean)
data class InformationArchiveSnapshot(val sources: List<ArchivedInformationSource>, val records: List<InformationInboxEntry>, val errors: List<String>)
enum class InformationSaveResult { CREATED, DUPLICATE }

/** Local source archive. No source is executed, parsed by media libraries, or sent to a remote service. */
class DesktopInformationStore(val directory: Path = DesktopSessionStore.defaultDirectory().parent.resolve("information")) {
    private val sourceDirectory = directory.resolve("sources")
    private val blobDirectory = directory.resolve("blobs")
    private val recordDirectory = directory.resolve("records")
    private val stateDirectory = directory.resolve("state")

    init { listOf(directory, sourceDirectory, blobDirectory, recordDirectory, stateDirectory).forEach(Files::createDirectories) }

    fun captureFile(path: Path): ArchivedInformationSource {
        require(Files.isRegularFile(path) && Files.size(path) <= MAX_SOURCE_BYTES) { "일반 파일을 선택하세요. 파일당 최대 512 MiB입니다." }
        return Files.newInputStream(path).use { capture(it, path.fileName.toString(), Files.probeContentType(path) ?: "application/octet-stream") }
    }

    fun captureText(text: String, name: String): ArchivedInformationSource {
        require(text.isNotBlank()) { "보관할 원문을 입력하세요." }
        return text.byteInputStream(Charsets.UTF_8).use { capture(it, name.ifBlank { "원문.txt" }, "text/plain; charset=utf-8") }
    }

    private fun capture(input: InputStream, name: String, mediaType: String): ArchivedInformationSource = exclusive {
        require(name.isNotBlank() && name.length <= 255 && '/' !in name && '\\' !in name) { "원본 이름은 경로 없이 255자 이내로 입력하세요." }
        val temporary = Files.createTempFile(blobDirectory, ".capture-", ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_SOURCE_BYTES) { "파일당 최대 512 MiB까지 보관할 수 있습니다." }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val blob = blobDirectory.resolve(hash)
            if (Files.exists(blob)) {
                require(Files.size(blob) == size && sha256(blob) == hash) { "기존 원문 해시가 다릅니다. 보관함을 확인하세요." }
            } else moveAtomic(temporary, blob)
            val source = ArchivedInformationSource(UUID.randomUUID().toString(), name, hash, size, Instant.now().toString(), mediaType)
            writeAtomic(sourceDirectory.resolve(source.id + ".json"), buildJsonObject {
                put("id", source.id); put("name", source.name); put("hash", source.hash)
                put("size", source.size); put("captured_at", source.capturedAt); put("media_type", source.mediaType)
            }.toString())
            source
        } finally { Files.deleteIfExists(temporary) }
    }

    fun save(record: InformationRecord): InformationSaveResult = exclusive {
        val path = recordDirectory.resolve(record.id + ".json")
        if (Files.exists(path)) {
            require(InformationRecordCodec.decode(Files.readString(path)).canonical == record.canonical) { "같은 ID의 다른 내용이 이미 있습니다. 정정 기록을 새 ID로 만드세요." }
            InformationSaveResult.DUPLICATE
        } else {
            writeAtomic(path, record.canonical)
            InformationSaveResult.CREATED
        }
    }

    fun importFile(path: Path): InformationSaveResult {
        // Preserve even malformed/unsupported JSON before trying to decode it.
        val archived = captureFile(path)
        require(archived.size <= InformationRecordCodec.MAX_BYTES) { "구조화 기록 JSON은 1 MiB 이하여야 합니다. 원문은 보관했습니다." }
        return save(InformationRecordCodec.decode(Files.readString(blobDirectory.resolve(archived.hash))))
    }

    fun markReviewed(id: String) = exclusive {
        val record = storedRecord(id)
        writeAtomic(stateDirectory.resolve(record.id + ".json"), buildJsonObject {
            put("fingerprint", record.fingerprint); put("reviewed_at", Instant.now().toString())
        }.toString(), replace = true)
    }

    fun exportRecord(id: String, target: Path) = exclusive {
        val record = storedRecord(id)
        require(isReviewed(record)) { "소유 앱과 출처를 확인한 뒤 검토 완료를 눌러 주세요." }
        require(!target.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) { "보관함 밖의 내보내기 위치를 선택하세요." }
        writeAtomic(target, record.canonical, replace = false)
    }

    fun exportSource(id: String, target: Path) = exclusive {
        val source = readSource(sourceDirectory.resolve(safeId(id) + ".json"))
        require(!target.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) { "보관함 밖의 내보내기 위치를 선택하세요." }
        val blob = blobDirectory.resolve(source.hash)
        require(sha256(blob) == source.hash) { "원문 해시가 다릅니다. 내보내기를 중단했습니다." }
        Files.copy(blob, target)
    }

    fun snapshot(): InformationArchiveSnapshot = exclusive {
        val errors = mutableListOf<String>()
        val sources = jsonFiles(sourceDirectory).mapNotNull { path ->
            runCatching { readSource(path) }.getOrElse { errors += "원문 metadata를 읽지 못했습니다: ${path.fileName}"; null }
        }.sortedByDescending { it.capturedAt }
        val records = jsonFiles(recordDirectory).mapNotNull { path ->
            runCatching { val record = InformationRecordCodec.decode(Files.readString(path)); InformationInboxEntry(record, isReviewed(record)) }
                .getOrElse { errors += "기록을 읽지 못했습니다: ${path.fileName}"; null }
        }.sortedByDescending { it.record.payload.getValue("captured_at").jsonPrimitive.content }
        InformationArchiveSnapshot(sources, records, errors)
    }

    private fun storedRecord(id: String) = InformationRecordCodec.decode(Files.readString(recordDirectory.resolve(safeId(id) + ".json")))
    private fun safeId(id: String): String { require(Regex("[0-9a-f-]{36}").matches(id)); return id }
    private fun isReviewed(record: InformationRecord): Boolean {
        val path = stateDirectory.resolve(record.id + ".json")
        if (!Files.exists(path)) return false
        return Json.parseToJsonElement(Files.readString(path)).jsonObject["fingerprint"]?.jsonPrimitive?.content == record.fingerprint
    }
    private fun readSource(path: Path): ArchivedInformationSource {
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val source = ArchivedInformationSource(root.getValue("id").jsonPrimitive.content, root.getValue("name").jsonPrimitive.content,
            root.getValue("hash").jsonPrimitive.content, root.getValue("size").jsonPrimitive.long,
            root.getValue("captured_at").jsonPrimitive.content, root.getValue("media_type").jsonPrimitive.content)
        safeId(source.id); require(Regex("[0-9a-f]{64}").matches(source.hash))
        return source
    }
    private fun jsonFiles(path: Path): List<Path> = Files.list(path).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream -> val bytes = ByteArray(64 * 1024); while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun writeAtomic(path: Path, text: String, replace: Boolean = false) {
        val target = path.toAbsolutePath().normalize()
        require(replace || !Files.exists(target)) { "같은 이름의 파일이 있습니다. 새 파일 이름을 선택하세요." }
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".information-", ".tmp")
        try { Files.writeString(temp, text); moveAtomic(temp, target, replace) } finally { Files.deleteIfExists(temp) }
    }
    private fun moveAtomic(from: Path, to: Path, replace: Boolean = false) {
        val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) else arrayOf(StandardCopyOption.ATOMIC_MOVE)
        try { Files.move(from, to, *options) } catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING) else Files.move(from, to)
        }
    }
    @Synchronized private fun <T> exclusive(action: () -> T): T = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel -> channel.lock().use { action() } }

    companion object { const val MAX_SOURCE_BYTES = 512L * 1024 * 1024 }
}
