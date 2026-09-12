package com.pricetrace.receiptscanner.ingestion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class EvidenceSupabaseConfig(
    val url: String = "",
    val publishableKey: String = "",
    val userId: String = "",
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
) {
    val isConnectionConfigured: Boolean get() = validateConnection(url, publishableKey) == null
    val isSignedIn: Boolean get() = isConnectionConfigured && userId.isNotBlank() && accessToken.isNotBlank()

    companion object {
        fun validateConnection(rawUrl: String, rawKey: String): String? {
            val url = rawUrl.trim().trimEnd('/')
            val key = rawKey.trim()
            val uri = runCatching { URI(url) }.getOrNull()
            return when {
                uri == null || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() ->
                    "Evidence Supabase URL must be an HTTPS URL."
                uri.userInfo != null || uri.fragment != null || uri.rawQuery != null ->
                    "Evidence Supabase URL must not contain user info, query, or fragment."
                looksLikePrivilegedKey(key) ->
                    "service_role/secret keys are not allowed. Use an Evidence publishable key."
                key.length !in 20..4096 || key.any(Char::isWhitespace) ->
                    "Evidence publishable key is invalid."
                else -> null
            }
        }

        private fun looksLikePrivilegedKey(key: String): Boolean {
            if (key.startsWith("sb_secret_", ignoreCase = true)) return true
            val payload = key.split('.').takeIf { it.size == 3 }?.get(1) ?: return false
            return runCatching {
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
                    .contains(Regex("\"role\"\\s*:\\s*\"service_role\"", RegexOption.IGNORE_CASE))
            }.getOrDefault(false)
        }
    }
}

interface EvidenceSupabaseStore {
    fun read(): EvidenceSupabaseConfig
    fun saveConnection(url: String, publishableKey: String): Result<EvidenceSupabaseConfig>
    fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String): Result<EvidenceSupabaseConfig>
    fun clearSession(): Boolean
}

data class EvidenceArchiveCheckpoint(
    val canonicalArtifactId: String? = null,
    val evidenceObjectIdsBySha256: Map<String, String> = emptyMap(),
    val boundSourceFileIds: Set<String> = emptySet(),
)

data class EvidenceArchiveRequest(
    val bundle: YeonsikBundle,
    val openEvidence: (sourceFileId: String) -> InputStream,
)

sealed interface EvidenceArchiveResult {
    data class Success(val checkpoint: EvidenceArchiveCheckpoint) : EvidenceArchiveResult
    data class Failure(val issue: String, val checkpoint: EvidenceArchiveCheckpoint) : EvidenceArchiveResult
}

sealed interface EvidenceVerificationEventResult {
    data object Success : EvidenceVerificationEventResult
    data class Failure(val issue: String) : EvidenceVerificationEventResult
}

interface EvidenceArchivePort {
    suspend fun archive(
        request: EvidenceArchiveRequest,
        checkpoint: EvidenceArchiveCheckpoint = EvidenceArchiveCheckpoint(),
    ): EvidenceArchiveResult

    suspend fun recordVerification(
        canonicalArtifactId: String,
        basis: VerificationBasis,
        result: String,
        issues: List<String>,
    ): EvidenceVerificationEventResult
}

data class EvidenceHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

data class EvidenceHttpResponse(val statusCode: Int, val body: String)

fun interface EvidenceHttpTransport {
    suspend fun execute(request: EvidenceHttpRequest): EvidenceHttpResponse
}

class HttpsEvidenceHttpTransport : EvidenceHttpTransport {
    override suspend fun execute(request: EvidenceHttpRequest): EvidenceHttpResponse = withContext(Dispatchers.IO) {
        require(request.url.startsWith("https://", ignoreCase = true)) { "Only HTTPS Evidence endpoints are allowed" }
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            request.headers.forEach(::setRequestProperty)
            request.body?.let { bytes ->
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
            }
        }
        try {
            request.body?.let { bytes -> connection.outputStream.use { it.write(bytes) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            EvidenceHttpResponse(status, stream?.use(::readLimited).orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= 1024 * 1024) { "Evidence response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}

/** REST/Storage adapter. Remote IDs stay in the evidence checkpoint and never enter canonical data. */
class EvidenceSupabaseArchivePort(
    private val store: EvidenceSupabaseStore,
    private val transport: EvidenceHttpTransport = HttpsEvidenceHttpTransport(),
    private val email: String = "",
    private val password: String = "",
) : EvidenceArchivePort {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun signIn(email: String, password: String): Result<EvidenceSupabaseConfig> = runCatching {
        ensureAuthenticated(email.trim(), password, force = true)
    }

    override suspend fun archive(
        request: EvidenceArchiveRequest,
        checkpoint: EvidenceArchiveCheckpoint,
    ): EvidenceArchiveResult {
        var progress = checkpoint
        return try {
            val config = ensureAuthenticated()
            val artifactId = progress.canonicalArtifactId ?: upsertCanonical(config, request.bundle)
            progress = progress.copy(canonicalArtifactId = artifactId)
            request.bundle.manifest.evidence.forEach { item ->
                val objectId = progress.evidenceObjectIdsBySha256[item.sha256] ?: run {
                    uploadBlob(config, item, request.openEvidence(item.sourceFileId))
                    upsertEvidenceObject(config, item).also { id ->
                        progress = progress.copy(
                            evidenceObjectIdsBySha256 = progress.evidenceObjectIdsBySha256 + (item.sha256 to id),
                        )
                    }
                }
                if (item.sourceFileId !in progress.boundSourceFileIds) {
                    upsertBinding(config, artifactId, item, objectId)
                    progress = progress.copy(boundSourceFileIds = progress.boundSourceFileIds + item.sourceFileId)
                }
            }
            EvidenceArchiveResult.Success(progress)
        } catch (error: Exception) {
            EvidenceArchiveResult.Failure(error.message ?: error.javaClass.simpleName, progress)
        }
    }

    override suspend fun recordVerification(
        canonicalArtifactId: String,
        basis: VerificationBasis,
        result: String,
        issues: List<String>,
    ): EvidenceVerificationEventResult = try {
        val config = ensureAuthenticated()
        val body = buildJsonObject {
            put("owner_id", config.userId)
            put("canonical_artifact_id", canonicalArtifactId)
            put("basis", basis.wireValue)
            put("result", result)
            put("issues", JsonArray(issues.map(::JsonPrimitive)))
        }.encoded()
        executeJson(config, "POST", "/rest/v1/verification_events", body, prefer = "return=minimal")
            .requireSuccess("verification event")
        EvidenceVerificationEventResult.Success
    } catch (error: Exception) {
        EvidenceVerificationEventResult.Failure(error.message ?: error.javaClass.simpleName)
    }

    private suspend fun ensureAuthenticated(
        providedEmail: String = email,
        providedPassword: String = password,
        force: Boolean = false,
    ): EvidenceSupabaseConfig {
        val current = store.read()
        EvidenceSupabaseConfig.validateConnection(current.url, current.publishableKey)?.let(::error)
        if (current.isSignedIn && !force) return current
        require(providedEmail.isNotBlank() && providedPassword.isNotBlank()) { "Evidence credentials are missing" }
        val body = buildJsonObject { put("email", providedEmail); put("password", providedPassword) }.encoded()
        val response = transport.execute(
            EvidenceHttpRequest(
                "POST",
                current.url.trimEnd('/') + "/auth/v1/token?grant_type=password",
                mapOf("apikey" to current.publishableKey, "Content-Type" to "application/json"),
                body.toByteArray(StandardCharsets.UTF_8),
            ),
        ).requireSuccess("Evidence sign-in")
        val root = json.parseToJsonElement(response.body).jsonObject
        val userId = root["user"]?.jsonObject?.string("id") ?: error("Evidence auth response missing user id")
        val accessToken = requireNotNull(root.string("access_token")) { "Evidence auth response missing access token" }
        val refreshToken = requireNotNull(root.string("refresh_token")) { "Evidence auth response missing refresh token" }
        return store.saveSession(userId, providedEmail, accessToken, refreshToken).getOrThrow()
    }

    private suspend fun upsertCanonical(config: EvidenceSupabaseConfig, bundle: YeonsikBundle): String {
        val body = buildJsonObject {
            put("owner_id", config.userId)
            put("canonical_sha256", bundle.manifest.canonicalSha256)
            put("schema_version", bundle.envelope.schemaVersion)
            put("mode", bundle.envelope.mode.wireValue)
            put("canonical_json", json.parseToJsonElement(bundle.canonicalJson))
            put("manifest_json", json.parseToJsonElement(bundle.manifestJson))
        }.encoded()
        val response = executeJson(
            config,
            "POST",
            "/rest/v1/canonical_artifacts?on_conflict=owner_id%2Ccanonical_sha256&select=id",
            body,
            "resolution=merge-duplicates,return=representation",
        ).requireSuccess("canonical artifact upsert")
        return response.firstId()
    }

    private suspend fun uploadBlob(
        config: EvidenceSupabaseConfig,
        item: YeonsikBundleEvidence,
        input: InputStream,
    ) {
        val bytes = input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                require(total <= YeonsikBundleReader.MAX_SINGLE_EVIDENCE_BYTES) { "evidence upload exceeds limit" }
                output.write(buffer, 0, count)
            }
            require(total == item.byteSize) { "local evidence size changed before archive" }
            output.toByteArray()
        }
        require(sha256(bytes) == item.sha256) { "local evidence hash changed before archive" }
        val response = transport.execute(
            EvidenceHttpRequest(
                "POST",
                config.url.trimEnd('/') + "/storage/v1/object/yeonsik-evidence/${storagePath(config, item)}",
                authHeaders(config) + mapOf("Content-Type" to item.mimeType, "x-upsert" to "false"),
                bytes,
            ),
        )
        require(response.statusCode in 200..299 || response.statusCode == 409) {
            "evidence blob archive failed (${response.statusCode})"
        }
    }

    private suspend fun upsertEvidenceObject(config: EvidenceSupabaseConfig, item: YeonsikBundleEvidence): String {
        val body = buildJsonObject {
            put("owner_id", config.userId)
            put("sha256", item.sha256)
            put("mime_type", item.mimeType)
            put("byte_size", item.byteSize)
            put("original_filename", item.originalFilename)
            put("bucket", BUCKET)
            put("storage_path", storagePath(config, item))
        }.encoded()
        val response = executeJson(
            config,
            "POST",
            "/rest/v1/evidence_objects?on_conflict=owner_id%2Csha256&select=id",
            body,
            "resolution=merge-duplicates,return=representation",
        ).requireSuccess("evidence object upsert")
        return response.firstId()
    }

    private suspend fun upsertBinding(
        config: EvidenceSupabaseConfig,
        artifactId: String,
        item: YeonsikBundleEvidence,
        objectId: String,
    ) {
        val body = buildJsonObject {
            put("owner_id", config.userId)
            put("canonical_artifact_id", artifactId)
            put("source_file_id", item.sourceFileId)
            put("source_type", item.type.wireValue)
            put("evidence_object_id", objectId)
        }.encoded()
        executeJson(
            config,
            "POST",
            "/rest/v1/evidence_bindings?on_conflict=canonical_artifact_id%2Csource_file_id",
            body,
            "resolution=merge-duplicates,return=minimal",
        ).requireSuccess("evidence binding upsert")
    }

    private suspend fun executeJson(
        config: EvidenceSupabaseConfig,
        method: String,
        path: String,
        body: String,
        prefer: String,
    ): EvidenceHttpResponse = transport.execute(
        EvidenceHttpRequest(
            method,
            config.url.trimEnd('/') + path,
            authHeaders(config) + mapOf("Content-Type" to "application/json", "Prefer" to prefer),
            body.toByteArray(StandardCharsets.UTF_8),
        ),
    )

    private fun authHeaders(config: EvidenceSupabaseConfig): Map<String, String> = mapOf(
        "apikey" to config.publishableKey,
        "Authorization" to "Bearer ${config.accessToken}",
    )

    private fun storagePath(config: EvidenceSupabaseConfig, item: YeonsikBundleEvidence): String {
        val extension = item.originalFilename.substringAfterLast('.', "")
            .lowercase().filter(Char::isLetterOrDigit).take(12).ifBlank { mimeExtension(item.mimeType) }
        return "${config.userId}/${item.sha256}.$extension"
    }

    private fun mimeExtension(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "application/pdf" -> "pdf"
        else -> "bin"
    }

    private fun EvidenceHttpResponse.requireSuccess(label: String): EvidenceHttpResponse = also {
        require(statusCode in 200..299) { "$label failed ($statusCode): ${body.take(240)}" }
    }

    private fun EvidenceHttpResponse.firstId(): String {
        val values = json.parseToJsonElement(body).jsonArray
        return values.firstOrNull()?.jsonObject?.string("id") ?: error("Evidence response missing id")
    }

    private fun JsonObject.encoded(): String = json.encodeToString(JsonElement.serializer(), this)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val BUCKET = "yeonsik-evidence"
    }
}
