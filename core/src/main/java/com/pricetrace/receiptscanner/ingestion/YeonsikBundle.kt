package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

const val YEONSIK_BUNDLE_VERSION = "yeonsik-bundle.v1"

data class YeonsikBundleEvidence(
    val sourceFileId: String,
    val type: SourceAttachmentType,
    val path: String,
    val sha256: String,
    val mimeType: String,
    val byteSize: Long,
    val originalFilename: String,
)

data class YeonsikBundleManifest(
    val bundleVersion: String,
    val canonicalPath: String,
    val canonicalSha256: String,
    val evidence: List<YeonsikBundleEvidence>,
)

data class YeonsikBundle(
    val manifest: YeonsikBundleManifest,
    val manifestJson: String,
    val canonicalJson: String,
    val envelope: YeonsikOcrEnvelope,
)

/** Platform adapters provide app-private staging. abort() must remove partial materialization. */
interface YeonsikBundleMaterializer {
    fun open(relativePath: String): OutputStream
    fun complete() = Unit
    fun abort() = Unit
}

object YeonsikBundleManifestCodec {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
    private val sha256Pattern = Regex("^[a-f0-9]{64}$")

    fun decode(value: String): YeonsikBundleManifest {
        val root = json.parseToJsonElement(value).jsonObject
        requireKeys(root, ROOT_KEYS, "manifest")
        val evidence = root.arrayValue("evidence").map { element ->
            val item = element.jsonObject
            requireKeys(item, EVIDENCE_KEYS, "manifest evidence")
            YeonsikBundleEvidence(
                sourceFileId = item.requiredString("source_file_id"),
                type = SourceAttachmentType.fromWireValue(item.requiredString("type")),
                path = validateEvidencePath(item.requiredString("path")),
                sha256 = validateHash(item.requiredString("sha256"), "evidence sha256"),
                mimeType = item.requiredString("mime_type").also {
                    require(MIME_PATTERN.matches(it)) { "invalid evidence mime_type" }
                },
                byteSize = item.requiredLong("byte_size").also {
                    require(it in 0..YeonsikBundleReader.MAX_SINGLE_EVIDENCE_BYTES) {
                        "evidence byte_size exceeds limit"
                    }
                },
                originalFilename = validateOriginalFilename(item.requiredString("original_filename")),
            )
        }
        require(evidence.map { it.sourceFileId }.distinct().size == evidence.size) {
            "manifest source_file_id values must be unique"
        }
        evidence.groupBy { it.path }.values.forEach { shared ->
            require(shared.map { listOf(it.sha256, it.mimeType, it.byteSize.toString()) }.distinct().size == 1) {
                "shared evidence path metadata must match"
            }
        }
        return YeonsikBundleManifest(
            bundleVersion = root.requiredString("bundle_version").also {
                require(it == YEONSIK_BUNDLE_VERSION) { "unsupported bundle_version" }
            },
            canonicalPath = root.requiredString("canonical_path").also {
                require(it == CANONICAL_PATH) { "canonical_path must be canonical.json" }
            },
            canonicalSha256 = validateHash(root.requiredString("canonical_sha256"), "canonical_sha256"),
            evidence = evidence,
        )
    }

    fun encode(value: YeonsikBundleManifest): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("bundle_version", value.bundleVersion)
            put("canonical_path", value.canonicalPath)
            put("canonical_sha256", value.canonicalSha256)
            put("evidence", buildJsonArray {
                value.evidence.forEach { item ->
                    add(buildJsonObject {
                        put("source_file_id", item.sourceFileId)
                        put("type", item.type.wireValue)
                        put("path", item.path)
                        put("sha256", item.sha256)
                        put("mime_type", item.mimeType)
                        put("byte_size", item.byteSize)
                        put("original_filename", item.originalFilename)
                    })
                }
            })
        },
    )

    private fun validateHash(value: String, label: String): String = value.also {
        require(sha256Pattern.matches(it)) { "$label must be lowercase 64 hex without a prefix" }
    }

    private fun validateEvidencePath(value: String): String = value.also {
        require(it.startsWith("evidence/") && it.length > "evidence/".length) {
            "evidence path must be below evidence/"
        }
        require(isSafeZipPath(it)) { "unsafe evidence path" }
    }

    private fun validateOriginalFilename(value: String): String = value.also {
        require(it == it.substringAfterLast('/') && it == it.substringAfterLast('\\')) {
            "original_filename must not contain a path"
        }
        require(it !in setOf(".", "..") && it.none(Char::isISOControl)) {
            "invalid original_filename"
        }
    }

    private fun requireKeys(root: JsonObject, expected: Set<String>, label: String) {
        require(root.keys == expected) {
            "$label keys mismatch: unexpected=${root.keys - expected}, missing=${expected - root.keys}"
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("$key must be a non-empty string")

    private fun JsonObject.requiredLong(key: String): Long {
        val value = this[key] as? JsonPrimitive ?: error("$key must be an integer")
        require(!value.isString) { "$key must be an integer" }
        return value.content.toLongOrNull() ?: error("$key must be an integer")
    }

    private fun JsonObject.arrayValue(key: String): JsonArray = this[key]?.jsonArray
        ?: error("$key must be an array")

    private val ROOT_KEYS = setOf("bundle_version", "canonical_path", "canonical_sha256", "evidence")
    private val EVIDENCE_KEYS = setOf(
        "source_file_id", "type", "path", "sha256", "mime_type", "byte_size", "original_filename",
    )
    private val MIME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}$")
    private const val CANONICAL_PATH = "canonical.json"
}

/**
 * Strict ZIP reader. The compressed stream is spooled to a temporary file so the central
 * directory can be checked for encryption, Unix symlinks, duplicate entries, and ZIP64 before
 * any entry is materialized. Public APIs remain InputStream/OutputStream based for Android.
 */
object YeonsikBundleReader {
    const val MAX_ENTRIES = 128
    const val MAX_SINGLE_EVIDENCE_BYTES = 50L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 250L * 1024 * 1024
    private const val MAX_CONTROL_BYTES = 8L * 1024 * 1024
    private const val MAX_COMPRESSED_BYTES = MAX_TOTAL_UNCOMPRESSED_BYTES + 16L * 1024 * 1024

    fun read(
        input: InputStream,
        localDocumentId: String,
        materializer: YeonsikBundleMaterializer,
    ): YeonsikBundle {
        val archive = File.createTempFile("yeonsik-bundle-", ".zip")
        try {
            input.buffered().use { source ->
                Files.newOutputStream(archive.toPath()).use { target ->
                    copyLimited(source, target, MAX_COMPRESSED_BYTES, "compressed bundle")
                }
            }
            val entries = inspectCentralDirectory(archive)
            validateEntryNames(entries)
            ZipFile(archive, StandardCharsets.UTF_8).use { zip ->
                val manifestBytes = readControlEntry(zip, "manifest.json")
                val canonicalBytes = readControlEntry(zip, "canonical.json")
                val manifestJson = manifestBytes.toString(StandardCharsets.UTF_8)
                val canonicalJson = canonicalBytes.toString(StandardCharsets.UTF_8)
                val manifest = YeonsikBundleManifestCodec.decode(manifestJson)
                require(sha256(canonicalBytes) == manifest.canonicalSha256) { "canonical hash mismatch" }
                val envelope = YeonsikOcrEnvelopeCodec.decode(canonicalJson, localDocumentId)
                validateBinding(manifest, envelope)
                val declaredPaths = manifest.evidence.map { it.path }.toSet()
                val actualEvidencePaths = entries.map { it.name }
                    .filter { it.startsWith("evidence/") && it != "evidence/" }
                    .toSet()
                require(actualEvidencePaths == declaredPaths) {
                    "undeclared or missing evidence entries"
                }
                var total = 0L
                manifest.evidence.distinctBy { it.path }.forEach { item ->
                    val entry = zip.getEntry(item.path) ?: error("missing evidence entry: ${item.path}")
                    require(!entry.isDirectory) { "evidence entry must be a file" }
                    require(entry.size < 0 || entry.size == item.byteSize) { "evidence size mismatch: ${item.path}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    materializer.open(item.path).use { output ->
                        zip.getInputStream(entry).buffered().use { source ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                count += read
                                total += read
                                require(count <= MAX_SINGLE_EVIDENCE_BYTES) { "single evidence exceeds limit" }
                                require(total <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "bundle evidence exceeds total limit" }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    require(count == item.byteSize) { "evidence size mismatch: ${item.path}" }
                    require(digest.hex() == item.sha256) { "evidence hash mismatch: ${item.path}" }
                }
                materializer.complete()
                return YeonsikBundle(manifest, manifestJson, canonicalJson, envelope)
            }
        } catch (error: Exception) {
            runCatching { materializer.abort() }
            throw error
        } finally {
            Files.deleteIfExists(archive.toPath())
        }
    }

    private data class CentralEntry(
        val name: String,
        val encrypted: Boolean,
        val symlink: Boolean,
        val uncompressedSize: Long,
    )

    private fun inspectCentralDirectory(file: File): List<CentralEntry> = RandomAccessFile(file, "r").use { raf ->
        val eocd = findEocd(raf)
        raf.seek(eocd + 4)
        val disk = raf.readLeUShort()
        val centralDisk = raf.readLeUShort()
        val entriesOnDisk = raf.readLeUShort()
        val entryCount = raf.readLeUShort()
        val centralSize = raf.readLeUInt()
        val centralOffset = raf.readLeUInt()
        require(disk == 0 && centralDisk == 0 && entriesOnDisk == entryCount) { "multi-disk ZIP is not supported" }
        require(entryCount != 0xffff && centralSize != 0xffffffffL && centralOffset != 0xffffffffL) {
            "ZIP64 bundles are not supported"
        }
        require(entryCount <= MAX_ENTRIES) { "bundle has too many entries" }
        require(centralOffset + centralSize <= eocd) { "invalid ZIP central directory" }
        raf.seek(centralOffset)
        buildList {
            repeat(entryCount) {
                require(raf.readLeUInt() == 0x02014b50L) { "invalid ZIP central entry" }
                val versionMadeBy = raf.readLeUShort()
                raf.skipBytes(2)
                val flags = raf.readLeUShort()
                raf.skipBytes(14)
                val uncompressedSize = raf.readLeUInt()
                val nameLength = raf.readLeUShort()
                val extraLength = raf.readLeUShort()
                val commentLength = raf.readLeUShort()
                raf.skipBytes(4)
                val externalAttributes = raf.readLeUInt()
                raf.skipBytes(4)
                val nameBytes = ByteArray(nameLength).also(raf::readFully)
                val charset = if (flags and 0x0800 != 0) StandardCharsets.UTF_8 else Charset.forName("CP437")
                val name = nameBytes.toString(charset)
                raf.skipBytes(extraLength + commentLength)
                val unixMode = (externalAttributes ushr 16).toInt()
                val host = versionMadeBy ushr 8
                add(
                    CentralEntry(
                        name = name,
                        encrypted = flags and 0x0001 != 0,
                        symlink = host == 3 && unixMode and 0xf000 == 0xa000,
                        uncompressedSize = uncompressedSize,
                    ),
                )
            }
        }
    }

    private fun validateEntryNames(entries: List<CentralEntry>) {
        require(entries.isNotEmpty()) { "empty bundle" }
        require(entries.map { it.name }.distinct().size == entries.size) { "duplicate ZIP entry" }
        entries.forEach { entry ->
            require(isSafeZipPath(entry.name)) { "unsafe ZIP entry path" }
            require(!entry.encrypted) { "encrypted ZIP entries are not allowed" }
            require(!entry.symlink) { "ZIP symlinks are not allowed" }
            require(entry.name in setOf("canonical.json", "manifest.json", "evidence/") ||
                entry.name.startsWith("evidence/") && !entry.name.endsWith('/')) {
                "undeclared ZIP root entry: ${entry.name}"
            }
            if (entry.name.startsWith("evidence/") && entry.name != "evidence/") {
                require(entry.uncompressedSize <= MAX_SINGLE_EVIDENCE_BYTES) { "single evidence exceeds limit" }
            } else if (!entry.name.endsWith('/')) {
                require(entry.uncompressedSize <= MAX_CONTROL_BYTES) { "bundle control entry exceeds limit" }
            }
        }
        require(entries.count { it.name == "canonical.json" } == 1) { "canonical.json is required" }
        require(entries.count { it.name == "manifest.json" } == 1) { "manifest.json is required" }
    }

    private fun validateBinding(manifest: YeonsikBundleManifest, envelope: YeonsikOcrEnvelope) {
        val canonical = envelope.source.sourceFiles.associate { it.id to it.type }
        val bundled = manifest.evidence.associate { it.sourceFileId to it.type }
        require(canonical == bundled) { "canonical source files and manifest evidence must match 1:1 by id/type" }
    }

    private fun readControlEntry(zip: ZipFile, name: String): ByteArray {
        val entry = zip.getEntry(name) ?: error("$name is required")
        require(!entry.isDirectory) { "$name must be a file" }
        return zip.getInputStream(entry).use { source ->
            val output = ByteArrayOutputStream()
            copyLimited(source, output, MAX_CONTROL_BYTES, name)
            output.toByteArray()
        }
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val minimum = (raf.length() - 65_557L).coerceAtLeast(0L)
        var offset = raf.length() - 22L
        while (offset >= minimum) {
            raf.seek(offset)
            if (raf.readLeUInt() == 0x06054b50L) return offset
            offset--
        }
        error("ZIP end-of-central-directory not found")
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long, label: String): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "$label exceeds limit" }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun RandomAccessFile.readLeUShort(): Int {
        val a = read()
        val b = read()
        require(a >= 0 && b >= 0) { "truncated ZIP" }
        return a or (b shl 8)
    }

    private fun RandomAccessFile.readLeUInt(): Long {
        val a = readLeUShort()
        val b = readLeUShort()
        return (a.toLong() or (b.toLong() shl 16)) and 0xffffffffL
    }
}

internal fun isSafeZipPath(value: String): Boolean {
    if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') || '\\' in value) return false
    if (Regex("^[A-Za-z]:").containsMatchIn(value)) return false
    val parts = value.split('/')
    return parts.none { it.isEmpty() && value != "evidence/" || it == "." || it == ".." || it.any(Char::isISOControl) }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
