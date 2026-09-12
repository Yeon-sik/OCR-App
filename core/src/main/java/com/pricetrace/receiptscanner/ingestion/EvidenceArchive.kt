package com.pricetrace.receiptscanner.ingestion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    /** Prevents a checkpoint from being replayed against a different evidence manifest. */
    val bundleFingerprint: String? = null,
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
    /** Re-openable body source used by streaming uploads and one-shot 401 retries. */
    val bodyStream: (() -> InputStream)? = null,
    val contentLength: Long? = body?.size?.toLong(),
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
            if (request.body != null || request.bodyStream != null) {
                doOutput = true
                request.contentLength?.let(::setFixedLengthStreamingMode)
                    ?: setChunkedStreamingMode(DEFAULT_BUFFER_SIZE)
            }
        }
        try {
            if (request.body != null || request.bodyStream != null) {
                connection.outputStream.use { output ->
                    request.body?.let(output::write)
                    request.bodyStream?.invoke()?.use { input -> input.copyTo(output) }
                }
            }
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

    private data class AuthContext(
        var config: EvidenceSupabaseConfig,
        var refreshAttempted: Boolean = false,
    )

    suspend fun signIn(email: String, password: String): Result<EvidenceSupabaseConfig> = runCatching {
        ensureAuthenticated(email.trim(), password, force = true)
    }

    override suspend fun archive(
        request: EvidenceArchiveRequest,
        checkpoint: EvidenceArchiveCheckpoint,
    ): EvidenceArchiveResult {
        var progress = checkpoint
        return try {
            require(checkpoint.bundleFingerprint == null || checkpoint.bundleFingerprint == request.bundle.bundleFingerprint) {
                "archive checkpoint belongs to a different bundle"
            }
            progress = checkpoint.copy(bundleFingerprint = request.bundle.bundleFingerprint)
            val auth = AuthContext(ensureAuthenticated())
            val artifactId = progress.canonicalArtifactId ?: insertOrReuseCanonical(auth, request.bundle)
            progress = progress.copy(canonicalArtifactId = artifactId)
            request.bundle.manifest.evidence.forEach { item ->
                val objectId = progress.evidenceObjectIdsBySha256[item.sha256] ?: run {
                    uploadBlob(auth, item) { request.openEvidence(item.sourceFileId) }
                    insertOrReuseEvidenceObject(auth, item).also { id ->
                        progress = progress.copy(
                            evidenceObjectIdsBySha256 = progress.evidenceObjectIdsBySha256 + (item.sha256 to id),
                        )
                    }
                }
                if (item.sourceFileId !in progress.boundSourceFileIds) {
                    insertOrReuseBinding(auth, artifactId, item, objectId)
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
        val auth = AuthContext(ensureAuthenticated())
        val body = buildJsonObject {
            put("owner_id", auth.config.userId)
            put("canonical_artifact_id", canonicalArtifactId)
            put("basis", basis.wireValue)
            put("result", result)
            put("issues", JsonArray(issues.map(::JsonPrimitive)))
        }.encoded()
        executeJson(auth, "POST", "/rest/v1/verification_events", body, prefer = "return=minimal")
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

    private suspend fun insertOrReuseCanonical(auth: AuthContext, bundle: YeonsikBundle): String {
        val config = auth.config
        val body = buildJsonObject {
            put("owner_id", config.userId)
            put("canonical_sha256", bundle.manifest.canonicalSha256)
            put("manifest_sha256", bundle.manifestSha256)
            put("bundle_fingerprint", bundle.bundleFingerprint)
            put("schema_version", bundle.envelope.schemaVersion)
            put("mode", bundle.envelope.mode.wireValue)
            put("canonical_json", json.parseToJsonElement(bundle.canonicalJson))
            put("manifest_json", json.parseToJsonElement(bundle.manifestJson))
        }.encoded()
        val response = executeJson(
            auth,
            "POST",
            "/rest/v1/canonical_artifacts?on_conflict=owner_id%2Cbundle_fingerprint&select=id",
            body,
            "resolution=ignore-duplicates,return=representation",
        ).requireSuccess("canonical artifact insert-or-reuse")
        return response.firstIdOrNull() ?: findCanonical(auth, bundle)
    }

    private suspend fun uploadBlob(
        auth: AuthContext,
        item: YeonsikBundleEvidence,
        openEvidence: () -> InputStream,
    ) {
        validateEvidence(openEvidence, item)
        val config = auth.config
        val response = executeAuthenticated(auth) { refreshed ->
            EvidenceHttpRequest(
                method = "POST",
                url = refreshed.url.trimEnd('/') + "/storage/v1/object/yeonsik-evidence/${storagePath(refreshed, item)}",
                headers = authHeaders(refreshed) + mapOf("Content-Type" to item.mimeType, "x-upsert" to "false"),
                bodyStream = openEvidence,
                contentLength = item.byteSize,
            )
        }
        require(response.statusCode in 200..299 || response.statusCode == 409) {
            "evidence blob archive failed (${response.statusCode})"
        }
    }

    private fun validateEvidence(openEvidence: () -> InputStream, item: YeonsikBundleEvidence) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        openEvidence().use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                require(total <= YeonsikBundleReader.MAX_SINGLE_EVIDENCE_BYTES) { "evidence upload exceeds limit" }
                digest.update(buffer, 0, count)
            }
        }
        require(total == item.byteSize) { "local evidence size changed before archive" }
        require(digest.digest().joinToString("") { "%02x".format(it) } == item.sha256) {
            "local evidence hash changed before archive"
        }
    }

    private suspend fun insertOrReuseEvidenceObject(auth: AuthContext, item: YeonsikBundleEvidence): String {
        val config = auth.config
        val body = buildJsonObject {
            put("owner_id", config.userId)
            put("sha256", item.sha256)
            put("mime_type", item.mimeType)
            put("byte_size", item.byteSize)
            put("bucket", BUCKET)
            put("storage_path", storagePath(config, item))
        }.encoded()
        val response = executeJson(
            auth,
            "POST",
            "/rest/v1/evidence_objects?on_conflict=owner_id%2Csha256&select=id",
            body,
            "resolution=ignore-duplicates,return=representation",
        ).requireSuccess("evidence object insert-or-reuse")
        return response.firstIdOrNull() ?: findEvidenceObject(auth, item)
    }

    private suspend fun insertOrReuseBinding(
        auth: AuthContext,
        artifactId: String,
        item: YeonsikBundleEvidence,
        objectId: String,
    ) {
        val config = auth.config
        val body = buildJsonObject {
            put("owner_id", config.userId)
            put("canonical_artifact_id", artifactId)
            put("source_file_id", item.sourceFileId)
            put("source_type", item.type.wireValue)
            put("evidence_object_id", objectId)
            put("metadata", buildJsonObject { put("original_filename", item.originalFilename) })
        }.encoded()
        val response = executeJson(
            auth,
            "POST",
            "/rest/v1/evidence_bindings?on_conflict=canonical_artifact_id%2Csource_file_id&select=id",
            body,
            "resolution=ignore-duplicates,return=representation",
        ).requireSuccess("evidence binding insert-or-reuse")
        if (response.firstIdOrNull() == null) findBinding(auth, artifactId, item, objectId)
    }

    private suspend fun executeJson(
        auth: AuthContext,
        method: String,
        path: String,
        body: String,
        prefer: String,
    ): EvidenceHttpResponse = executeAuthenticated(auth) { config ->
        EvidenceHttpRequest(
            method = method,
            url = config.url.trimEnd('/') + path,
            headers = authHeaders(config) + mapOf("Content-Type" to "application/json", "Prefer" to prefer),
            body = body.toByteArray(StandardCharsets.UTF_8).takeIf { body.isNotEmpty() },
        )
    }

    private suspend fun executeAuthenticated(
        auth: AuthContext,
        request: (EvidenceSupabaseConfig) -> EvidenceHttpRequest,
    ): EvidenceHttpResponse {
        val first = transport.execute(request(auth.config))
        if (first.statusCode != HttpURLConnection.HTTP_UNAUTHORIZED || auth.refreshAttempted) return first
        auth.refreshAttempted = true
        auth.config = refreshSession(auth.config)
        return transport.execute(request(auth.config))
    }

    private suspend fun refreshSession(current: EvidenceSupabaseConfig): EvidenceSupabaseConfig {
        require(current.refreshToken.isNotBlank()) { "Evidence refresh token is missing" }
        val body = buildJsonObject { put("refresh_token", current.refreshToken) }.encoded()
        val response = transport.execute(
            EvidenceHttpRequest(
                method = "POST",
                url = current.url.trimEnd('/') + "/auth/v1/token?grant_type=refresh_token",
                headers = mapOf("apikey" to current.publishableKey, "Content-Type" to "application/json"),
                body = body.toByteArray(StandardCharsets.UTF_8),
            ),
        ).requireSuccess("Evidence token refresh")
        val root = json.parseToJsonElement(response.body).jsonObject
        val accessToken = requireNotNull(root.string("access_token")) { "Evidence refresh response missing access token" }
        val refreshToken = root.string("refresh_token") ?: current.refreshToken
        val userId = root["user"]?.jsonObject?.string("id") ?: current.userId
        return store.saveSession(userId, current.email, accessToken, refreshToken).getOrThrow()
    }

    private suspend fun findCanonical(auth: AuthContext, bundle: YeonsikBundle): String {
        val response = executeJson(
            auth,
            "GET",
            "/rest/v1/canonical_artifacts?owner_id=eq.${urlEncode(auth.config.userId)}&bundle_fingerprint=eq.${bundle.bundleFingerprint}&select=id,canonical_sha256,manifest_sha256,bundle_fingerprint,schema_version,mode,canonical_json,manifest_json",
            "",
            "return=minimal",
        ).requireSuccess("canonical artifact lookup")
        val row = response.firstObjectOrNull() ?: error("canonical artifact response missing id")
        require(row.string("canonical_sha256") == bundle.manifest.canonicalSha256)
        require(row.string("manifest_sha256") == bundle.manifestSha256)
        require(row.string("bundle_fingerprint") == bundle.bundleFingerprint)
        require(row.string("schema_version") == bundle.envelope.schemaVersion)
        require(row.string("mode") == bundle.envelope.mode.wireValue)
        require(row["canonical_json"] == json.parseToJsonElement(bundle.canonicalJson))
        require(row["manifest_json"] == json.parseToJsonElement(bundle.manifestJson))
        return row.string("id") ?: error("canonical artifact response missing id")
    }

    private suspend fun findEvidenceObject(auth: AuthContext, item: YeonsikBundleEvidence): String {
        val response = executeJson(
            auth,
            "GET",
            "/rest/v1/evidence_objects?owner_id=eq.${urlEncode(auth.config.userId)}&sha256=eq.${item.sha256}&select=id,mime_type,byte_size,bucket,storage_path",
            "",
            "return=minimal",
        ).requireSuccess("evidence object lookup")
        val row = response.firstObjectOrNull() ?: error("evidence object response missing id")
        require(row.string("mime_type") == item.mimeType)
        require(row.string("byte_size") == item.byteSize.toString())
        require(row.string("bucket") == BUCKET)
        require(row.string("storage_path") == storagePath(auth.config, item))
        return row.string("id") ?: error("evidence object response missing id")
    }

    private suspend fun findBinding(
        auth: AuthContext,
        artifactId: String,
        item: YeonsikBundleEvidence,
        objectId: String,
    ) {
        val response = executeJson(
            auth,
            "GET",
            "/rest/v1/evidence_bindings?canonical_artifact_id=eq.${urlEncode(artifactId)}&source_file_id=eq.${urlEncode(item.sourceFileId)}&select=id,source_type,evidence_object_id,metadata",
            "",
            "return=minimal",
        ).requireSuccess("evidence binding lookup")
        val row = response.firstObjectOrNull() ?: error("evidence binding response missing id")
        require(row.string("source_type") == item.type.wireValue)
        require(row.string("evidence_object_id") == objectId)
        require(row["metadata"] == buildJsonObject { put("original_filename", item.originalFilename) })
    }

    private fun authHeaders(config: EvidenceSupabaseConfig): Map<String, String> = mapOf(
        "apikey" to config.publishableKey,
        "Authorization" to "Bearer ${config.accessToken}",
    )

    private fun storagePath(config: EvidenceSupabaseConfig, item: YeonsikBundleEvidence): String {
        return "${config.userId}/${item.sha256}.${mimeExtension(item.mimeType)}"
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

    private fun EvidenceHttpResponse.firstIdOrNull(): String? =
        parseArrayOrNull()?.firstOrNull()?.jsonObject?.string("id")

    private fun EvidenceHttpResponse.firstObjectOrNull(): JsonObject? =
        parseArrayOrNull()?.firstOrNull()?.jsonObject

    private fun EvidenceHttpResponse.parseArrayOrNull(): JsonArray? =
        body.takeIf(String::isNotBlank)?.let { json.parseToJsonElement(it).jsonArray }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun JsonObject.encoded(): String = json.encodeToString(JsonElement.serializer(), this)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val BUCKET = "yeonsik-evidence"
    }
}
