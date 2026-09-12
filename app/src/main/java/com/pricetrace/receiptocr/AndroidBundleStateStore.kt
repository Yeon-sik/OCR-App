package com.pricetrace.receiptocr

import android.content.Context
import com.pricetrace.receiptscanner.ingestion.EvidenceArchiveCheckpoint
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/** Durable UI/recovery state for an imported bundle; credentials and runtime tokens are excluded. */
data class AndroidBundleRecoveryState(
    val rawJson: String,
    val canonicalJson: String,
    val localDocumentId: String,
    val ingestionId: String,
    val selectedProjections: Set<IngestionProjection>,
    val verificationBasis: VerificationBasis,
    val bundle: AndroidBundleState,
)

interface AndroidBundleStateStore {
    fun save(state: AndroidBundleRecoveryState): Boolean
    fun loadActive(): AndroidBundleRecoveryState?
    /** Hides the current bundle from automatic restore without deleting its files/session. */
    fun clearActive(): Boolean
}

class InMemoryAndroidBundleStateStore : AndroidBundleStateStore {
    private var active: AndroidBundleRecoveryState? = null

    override fun save(state: AndroidBundleRecoveryState): Boolean {
        active = state
        return true
    }

    override fun loadActive(): AndroidBundleRecoveryState? = active

    override fun clearActive(): Boolean {
        active = null
        return true
    }
}

class SharedPreferencesAndroidBundleStateStore(context: Context) : AndroidBundleStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { explicitNulls = true; ignoreUnknownKeys = false }

    override fun save(state: AndroidBundleRecoveryState): Boolean {
        val key = RECORD_PREFIX + state.ingestionId
        return preferences.edit()
            .putString(key, encode(state))
            .putString(ACTIVE_KEY, state.ingestionId)
            .commit()
    }

    override fun loadActive(): AndroidBundleRecoveryState? {
        val ingestionId = preferences.getString(ACTIVE_KEY, null) ?: return null
        val value = preferences.getString(RECORD_PREFIX + ingestionId, null) ?: return null
        return runCatching { decode(json.parseToJsonElement(value).jsonObject) }.getOrNull()
    }

    override fun clearActive(): Boolean = preferences.edit().remove(ACTIVE_KEY).commit()

    private fun encode(state: AndroidBundleRecoveryState): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schema_version", JsonPrimitive(SCHEMA_VERSION))
            put("raw_json", JsonPrimitive(state.rawJson))
            put("canonical_json", JsonPrimitive(state.canonicalJson))
            put("local_document_id", JsonPrimitive(state.localDocumentId))
            put("ingestion_id", JsonPrimitive(state.ingestionId))
            put(
                "selected_projections",
                JsonArray(state.selectedProjections.sortedBy(IngestionProjection::wireValue).map { JsonPrimitive(it.wireValue) }),
            )
            put("verification_basis", JsonPrimitive(state.verificationBasis.wireValue))
            put("bundle", encodeBundle(state.bundle))
        },
    )

    private fun encodeBundle(bundle: AndroidBundleState): JsonObject = buildJsonObject {
        put("source_name", JsonPrimitive(bundle.sourceName))
        put("canonical_sha256", JsonPrimitive(bundle.canonicalSha256))
        put("manifest_json", JsonPrimitive(bundle.manifestJson))
        put("manifest_sha256", JsonPrimitive(bundle.manifestSha256))
        put("bundle_fingerprint", JsonPrimitive(bundle.bundleFingerprint))
        put(
            "evidence_paths",
            JsonObject(bundle.evidencePaths.mapValues { (_, path) -> JsonPrimitive(path) }),
        )
        put("validation_status", JsonPrimitive(bundle.validationStatus.name))
        put("archive_status", JsonPrimitive(bundle.archiveStatus.name))
        put("archive_checkpoint", encodeCheckpoint(bundle.archiveCheckpoint))
        put("verification_event_recorded", JsonPrimitive(bundle.verificationEventRecorded))
        putNullable("archive_error", bundle.archiveError)
    }

    private fun encodeCheckpoint(checkpoint: EvidenceArchiveCheckpoint): JsonObject = buildJsonObject {
        putNullable("canonical_artifact_id", checkpoint.canonicalArtifactId)
        put(
            "evidence_object_ids_by_sha256",
            JsonObject(checkpoint.evidenceObjectIdsBySha256.mapValues { (_, id) -> JsonPrimitive(id) }),
        )
        put("bound_source_file_ids", JsonArray(checkpoint.boundSourceFileIds.sorted().map(::JsonPrimitive)))
        putNullable("bundle_fingerprint", checkpoint.bundleFingerprint)
    }

    private fun decode(root: JsonObject): AndroidBundleRecoveryState {
        require(root.string("schema_version") == SCHEMA_VERSION) { "Unsupported Android bundle recovery state" }
        val bundle = root.objectValue("bundle").let(::decodeBundle)
        return AndroidBundleRecoveryState(
            rawJson = root.string("raw_json"),
            canonicalJson = root.string("canonical_json"),
            localDocumentId = root.string("local_document_id"),
            ingestionId = root.string("ingestion_id"),
            selectedProjections = root.arrayValue("selected_projections")
                .map { IngestionProjection.fromWireValue(it.jsonPrimitive.content) }.toSet(),
            verificationBasis = VerificationBasis.fromWireValue(root.string("verification_basis")),
            bundle = bundle,
        )
    }

    private fun decodeBundle(root: JsonObject): AndroidBundleState {
        val bundle = AndroidBundleState(
            sourceName = root.string("source_name"),
            canonicalSha256 = root.string("canonical_sha256"),
            manifestJson = root.string("manifest_json"),
            evidencePaths = root.objectValue("evidence_paths")
                .mapValues { (_, path) -> path.jsonPrimitive.content },
            validationStatus = AndroidBundleValidationStatus.valueOf(root.string("validation_status")),
            archiveStatus = AndroidEvidenceArchiveStatus.valueOf(root.string("archive_status")),
            archiveCheckpoint = decodeCheckpoint(root.objectValue("archive_checkpoint")),
            verificationEventRecorded = root.boolean("verification_event_recorded"),
            archiveError = root.nullableString("archive_error"),
        )
        root.nullableString("manifest_sha256")?.let { require(it == bundle.manifestSha256) { "Manifest hash mismatch" } }
        root.nullableString("bundle_fingerprint")?.let { require(it == bundle.bundleFingerprint) { "Bundle fingerprint mismatch" } }
        return bundle
    }

    private fun decodeCheckpoint(root: JsonObject) = EvidenceArchiveCheckpoint(
        canonicalArtifactId = root.nullableString("canonical_artifact_id"),
        evidenceObjectIdsBySha256 = root.objectValue("evidence_object_ids_by_sha256")
            .mapValues { (_, id) -> id.jsonPrimitive.content },
        boundSourceFileIds = root.arrayValue("bound_source_file_ids")
            .map { it.jsonPrimitive.content }.toSet(),
        bundleFingerprint = root.nullableString("bundle_fingerprint"),
    )

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Missing bundle recovery value: $key")

    private fun JsonObject.nullableString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.contentOrNull ?: error("Invalid nullable bundle recovery value: $key")
    }

    private fun JsonObject.boolean(key: String): Boolean = string(key).toBooleanStrict()
    private fun JsonObject.objectValue(key: String): JsonObject = this[key]?.jsonObject
        ?: error("Missing bundle recovery object: $key")
    private fun JsonObject.arrayValue(key: String): JsonArray = this[key]?.jsonArray
        ?: error("Missing bundle recovery array: $key")

    private companion object {
        const val PREFERENCES = "yeonsik_bundle_recovery"
        const val ACTIVE_KEY = "active_ingestion_id"
        const val RECORD_PREFIX = "record."
        const val SCHEMA_VERSION = "android-bundle-recovery.v1"
    }
}
