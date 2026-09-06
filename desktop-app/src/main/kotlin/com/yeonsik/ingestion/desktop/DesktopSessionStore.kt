package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.IngestionSessionStore
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

data class DesktopEvidenceAttachment(
    val attachmentId: String,
    val type: SourceAttachmentType,
    val path: Path,
    val pageId: String? = null,
)

data class DesktopSessionRecord(
    val session: IngestionSession,
    val rawJson: String,
    val canonicalJson: String,
    val evidence: List<DesktopEvidenceAttachment>,
)

/** JSON files only; no database and no remote state is treated as local session truth. */
class DesktopSessionStore(
    val directory: Path = defaultDirectory(),
) : IngestionSessionStore {
    private val json = Json {
        prettyPrint = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    init {
        Files.createDirectories(directory)
    }

    override suspend fun get(ingestionId: String): IngestionSession? = loadRecord(ingestionId)?.session

    override suspend fun findByCanonicalFingerprint(fingerprint: String): IngestionSession? =
        allRecords().firstOrNull { it.session.canonicalFingerprint == fingerprint }?.session

    override suspend fun findByImportFingerprint(fingerprint: String): IngestionSession? =
        allRecords().firstOrNull { it.session.importFingerprint == fingerprint }?.session

    override suspend fun delete(ingestionId: String) {
        val file = sessionFile(ingestionId)
        if (Files.isRegularFile(file)) Files.deleteIfExists(file)
    }

    override suspend fun save(session: IngestionSession) {
        val existing = loadRecord(session.ingestionId)
        saveRecord(
            (existing ?: DesktopSessionRecord(session, rawJson = "", canonicalJson = "", evidence = emptyList()))
                .copy(session = session),
        )
    }

    fun loadRecord(ingestionId: String): DesktopSessionRecord? = runCatching {
        readJson(sessionFile(ingestionId))
    }.getOrNull()

    fun latestRecord(): DesktopSessionRecord? = allRecords()
        .maxByOrNull { record -> record.session.updatedAt }

    fun saveRecord(record: DesktopSessionRecord) {
        require(record.session.ingestionId.isNotBlank()) { "ingestionId is required" }
        require(record.evidence.all { it.attachmentId.isNotBlank() && it.path.isAbsolute }) {
            "desktop evidence paths must be absolute"
        }
        val target = sessionFile(record.session.ingestionId)
        val temp = target.resolveSibling(".${target.fileName}.tmp-${UUID.randomUUID()}")
        Files.writeString(temp, encode(record))
        try {
            runCatching {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            }.getOrElse {
                if (it is AtomicMoveNotSupportedException) {
                    Files.move(temp, target, REPLACE_EXISTING)
                } else {
                    throw it
                }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun copyEvidence(ingestionId: String, source: Path, type: SourceAttachmentType, pageId: String? = null): DesktopEvidenceAttachment {
        require(source.isRegularFile() && Files.isReadable(source)) { "Evidence file is not readable: $source" }
        val attachmentId = UUID.randomUUID().toString()
        val sourceExtension = source.extension.lowercase().filter { it.isLetterOrDigit() }.take(12)
        val destinationDirectory = directory.resolve("evidence").resolve(safeId(ingestionId))
        Files.createDirectories(destinationDirectory)
        val destination = destinationDirectory.resolve(
            if (sourceExtension.isBlank()) "$attachmentId.bin" else "$attachmentId.$sourceExtension",
        )
        Files.copy(source, destination, REPLACE_EXISTING)
        return DesktopEvidenceAttachment(attachmentId, type, destination, pageId)
    }

    private fun allRecords(): List<DesktopSessionRecord> = Files.list(directory).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".json") }
            .toList()
            .mapNotNull { path -> runCatching { readJson(path) }.getOrNull() }
    }

    private fun sessionFile(ingestionId: String): Path = directory.resolve("${safeId(ingestionId)}.json")

    private fun safeId(value: String): String {
        require(value.matches(SAFE_ID_PATTERN)) { "unsafe desktop ingestion id" }
        return value
    }

    private fun encode(record: DesktopSessionRecord): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schema_version", JsonPrimitive("desktop-ingestion-session.v1"))
            put("raw_json", JsonPrimitive(record.rawJson))
            put("canonical_json", JsonPrimitive(record.canonicalJson))
            put("session", encodeSession(record.session))
            put("evidence", record.evidence.attachmentsToJson())
        },
    )

    private fun readJson(path: Path): DesktopSessionRecord {
        require(Files.isRegularFile(path)) { "session record not found" }
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        require(root.string("schema_version") == "desktop-ingestion-session.v1") {
            "unsupported desktop session record"
        }
        return DesktopSessionRecord(
            session = decodeSession(root.objectValue("session")),
            rawJson = root.string("raw_json"),
            canonicalJson = root.string("canonical_json"),
            evidence = root.arrayValue("evidence").map { decodeEvidence(it.jsonObject) },
        )
    }

    private fun encodeSession(session: IngestionSession): JsonObject = buildJsonObject {
        put("ingestion_id", JsonPrimitive(session.ingestionId))
        put("local_document_id", JsonPrimitive(session.localDocumentId))
        put("envelope_storage_key", JsonPrimitive(session.envelopeStorageKey))
        put("canonical_fingerprint", JsonPrimitive(session.canonicalFingerprint))
        put("review_status", JsonPrimitive(session.reviewStatus.wireValue))
        put("created_at", JsonPrimitive(session.createdAt))
        put("updated_at", JsonPrimitive(session.updatedAt))
        put("revision_seq", JsonPrimitive(session.revisionSeq))
        putNullable("verified_canonical_fingerprint", session.verifiedCanonicalFingerprint)
        putNullable("verified_at", session.verifiedAt)
        put("import_fingerprint", JsonPrimitive(session.importFingerprint))
        put(
            "verified_artifact_fingerprints",
            JsonObject(session.verifiedArtifactFingerprints.mapValues { JsonPrimitive(it.value) }),
        )
        put("attachments", session.attachments.localEvidenceToJson())
        put("projections", session.projections.projectionStatesToJson())
    }

    private fun decodeSession(root: JsonObject): IngestionSession = IngestionSession(
        ingestionId = root.string("ingestion_id"),
        localDocumentId = root.string("local_document_id"),
        envelopeStorageKey = root.string("envelope_storage_key"),
        canonicalFingerprint = root.string("canonical_fingerprint"),
        reviewStatus = IngestionReviewStatus.entries.first { it.wireValue == root.string("review_status") },
        createdAt = root.string("created_at"),
        updatedAt = root.string("updated_at"),
        projections = root.arrayValue("projections").map { decodeProjection(it.jsonObject) },
        attachments = root.arrayValue("attachments").map { decodeLocalEvidence(it.jsonObject) },
        revisionSeq = root.long("revision_seq"),
        verifiedCanonicalFingerprint = root.nullableString("verified_canonical_fingerprint"),
        verifiedAt = root.nullableString("verified_at"),
        verifiedArtifactFingerprints = root.objectValue("verified_artifact_fingerprints")
            .mapValues { (_, value) -> value.jsonPrimitive.content },
        importFingerprint = root.string("import_fingerprint"),
    )

    private fun decodeProjection(root: JsonObject): ProjectionState = ProjectionState(
        projection = IngestionProjection.fromWireValue(root.string("projection")),
        status = ProjectionStatus.fromPersisted(root.string("status")),
        idempotencyKey = root.nullableString("idempotency_key"),
        remoteId = root.nullableString("remote_id"),
        attemptCount = root.int("attempt_count"),
        lastError = root.nullableString("last_error"),
        updatedAt = root.string("updated_at"),
        metadataJson = root.nullableString("metadata_json"),
        projectionRevisionSeq = root.long("projection_revision_seq"),
        projectionPayloadFingerprint = root.nullableString("projection_payload_fingerprint"),
    )

    private fun decodeLocalEvidence(root: JsonObject): LocalEvidence = LocalEvidence(
        attachmentId = root.string("attachment_id"),
        type = SourceAttachmentType.fromWireValue(root.string("type")),
        fileReadable = root.boolean("file_readable"),
        pageId = root.nullableString("page_id"),
    )

    private fun decodeEvidence(root: JsonObject): DesktopEvidenceAttachment = DesktopEvidenceAttachment(
        attachmentId = root.string("attachment_id"),
        type = SourceAttachmentType.fromWireValue(root.string("type")),
        path = Path.of(root.string("path")),
        pageId = root.nullableString("page_id"),
    )

    private fun List<ProjectionState>.projectionStatesToJson(): JsonElement = kotlinx.serialization.json.JsonArray(map { state ->
        buildJsonObject {
            put("projection", JsonPrimitive(state.projection.wireValue))
            put("status", JsonPrimitive(state.status.wireValue))
            putNullable("idempotency_key", state.idempotencyKey)
            putNullable("remote_id", state.remoteId)
            put("attempt_count", JsonPrimitive(state.attemptCount))
            putNullable("last_error", state.lastError)
            put("updated_at", JsonPrimitive(state.updatedAt))
            putNullable("metadata_json", state.metadataJson)
            put("projection_revision_seq", JsonPrimitive(state.projectionRevisionSeq))
            putNullable("projection_payload_fingerprint", state.projectionPayloadFingerprint)
        }
    })

    private fun List<LocalEvidence>.localEvidenceToJson(): JsonElement = kotlinx.serialization.json.JsonArray(map { evidence ->
        buildJsonObject {
            put("attachment_id", JsonPrimitive(evidence.attachmentId))
            put("type", JsonPrimitive(evidence.type.wireValue))
            put("file_readable", JsonPrimitive(evidence.fileReadable))
            putNullable("page_id", evidence.pageId)
        }
    })

    private fun List<DesktopEvidenceAttachment>.attachmentsToJson(): JsonElement = kotlinx.serialization.json.JsonArray(map { evidence ->
        buildJsonObject {
            put("attachment_id", JsonPrimitive(evidence.attachmentId))
            put("type", JsonPrimitive(evidence.type.wireValue))
            put("path", JsonPrimitive(evidence.path.toAbsolutePath().normalize().toString()))
            putNullable("page_id", evidence.pageId)
        }
    })

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Missing session value: $key")

    private fun JsonObject.nullableString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.contentOrNull ?: error("Invalid nullable session value: $key")
    }

    private fun JsonObject.boolean(key: String): Boolean = string(key).toBooleanStrict()
    private fun JsonObject.int(key: String): Int = string(key).toInt()
    private fun JsonObject.long(key: String): Long = string(key).toLong()
    private fun JsonObject.objectValue(key: String): JsonObject = this[key]?.jsonObject ?: error("Missing object: $key")
    private fun JsonObject.arrayValue(key: String): kotlinx.serialization.json.JsonArray = this[key]?.jsonArray
        ?: error("Missing array: $key")

    companion object {
        fun defaultDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")?.trim().orEmpty()
            val base = localAppData.takeIf(String::isNotBlank)?.let(Path::of)
                ?.resolve("YeonsikIngestionConsole")
                ?: Path.of(System.getProperty("user.home"), ".yeonsik-ingestion-console")
            return base.resolve("sessions")
        }

        private val SAFE_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,160}")
    }
}
