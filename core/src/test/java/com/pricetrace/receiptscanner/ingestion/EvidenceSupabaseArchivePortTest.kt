package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.review.CanonicalFieldType
import com.pricetrace.receiptscanner.review.CanonicalReviewEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

private const val TEST_USER_ID = "11111111-1111-1111-1111-111111111111"
private const val OTHER_USER_ID = "22222222-2222-2222-2222-222222222222"

private fun jwtToken(userId: String, tag: String = "signed"): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".toByteArray())
    val payload = encoder.encodeToString("{\"sub\":\"$userId\"}".toByteArray())
    return "$header.$payload.$tag"
}

class EvidenceSupabaseArchivePortTest {
    @Test
    fun firstBlobUploadSucceedsWithoutOverwrite() = runTest {
        val (result, transport) = archiveWithUploadResponse(EvidenceHttpResponse(201, "{}"))

        assertTrue(result is EvidenceArchiveResult.Success)
        val upload = transport.requests.single { "/storage/v1/object/" in it.url }
        assertEquals("POST", upload.method)
        assertEquals("false", upload.headers["x-upsert"])
    }

    @Test
    fun duplicate400ReusesBlobAndContinuesObjectAndBinding() = runTest {
        val (result, transport) = archiveWithUploadResponse(
            EvidenceHttpResponse(400, """{"code":"AssetAlreadyExists","error":"Asset Already Exists","message":"The asset already exists"}"""),
        )

        assertTrue(result is EvidenceArchiveResult.Success)
        assertEquals(5, transport.requests.size)
        assertTrue(transport.requests.any { "/rest/v1/evidence_objects" in it.url })
        assertTrue(transport.requests.any { "/rest/v1/evidence_bindings" in it.url })
    }

    @Test
    fun duplicate409ReusesBlob() = runTest {
        val (result, _) = archiveWithUploadResponse(
            EvidenceHttpResponse(409, """{"error":"ResourceAlreadyExists","message":"resource already exists"}"""),
        )

        assertTrue(result is EvidenceArchiveResult.Success)
    }

    @Test
    fun unrelated400FailsAndIncludesResponseCodeAndBody() = runTest {
        val (result, _) = archiveWithUploadResponse(
            EvidenceHttpResponse(400, """{"code":"InvalidMimeType","message":"mime type is not allowed"}"""),
        )

        assertTrue(result is EvidenceArchiveResult.Failure)
        val issue = (result as EvidenceArchiveResult.Failure).issue
        assertTrue(issue.contains("(400)"))
        assertTrue(issue.contains("InvalidMimeType"))
        assertTrue(issue.contains("mime type is not allowed"))
    }

    @Test
    fun unrelated409Fails() = runTest {
        val (result, _) = archiveWithUploadResponse(
            EvidenceHttpResponse(409, """{"code":"BucketPolicyConflict","message":"policy conflict"}"""),
        )

        assertTrue(result is EvidenceArchiveResult.Failure)
    }

    @Test
    fun duplicateMarkerMustComeFromStorageErrorFields() = runTest {
        val (result, _) = archiveWithUploadResponse(
            EvidenceHttpResponse(400, """{"code":"BadRequest","message":"invalid request","details":"Asset Already Exists"}"""),
        )

        assertTrue(result is EvidenceArchiveResult.Failure)
    }

    @Test
    fun partialFailureCheckpointResumesWithoutReupload() = runTest {
        val canonicalJson = Files.readString(Path.of("..", "examples", "yeonsik-ocr.v3.restaurant.example.json"))
        val bytes = "menu evidence".toByteArray()
        val item = YeonsikBundleEvidence(
            sourceFileId = "menu-photo-1",
            type = SourceAttachmentType.MENU_PHOTO,
            path = "evidence/menu.jpg",
            sha256 = digest(bytes),
            mimeType = "image/jpeg",
            byteSize = bytes.size.toLong(),
            originalFilename = "menu.jpg",
        )
        val manifest = YeonsikBundleManifest(
            YEONSIK_BUNDLE_VERSION,
            "canonical.json",
            digest(canonicalJson.toByteArray()),
            listOf(item),
        )
        val bundle = YeonsikBundle(
            manifest,
            YeonsikBundleManifestCodec.encode(manifest),
            canonicalJson,
            YeonsikOcrEnvelopeCodec.decode(canonicalJson, "archive-test"),
        )
        val transport = ScriptedTransport(
            mutableListOf(
                EvidenceHttpResponse(201, "[{\"id\":\"artifact-1\"}]"),
                EvidenceHttpResponse(200, "{}"),
                EvidenceHttpResponse(201, "[{\"id\":\"object-1\"}]"),
                EvidenceHttpResponse(500, "binding failed"),
                EvidenceHttpResponse(201, ""),
                EvidenceHttpResponse(200, "[{\"id\":\"binding-1\",\"source_type\":\"menu_photo\",\"evidence_object_id\":\"object-1\",\"metadata\":{\"original_filename\":\"menu.jpg\"}}]"),
            ),
        )
        val port = EvidenceSupabaseArchivePort(SignedInStore(), transport)
        var opens = 0
        val request = EvidenceArchiveRequest(bundle) {
            opens++
            ByteArrayInputStream(bytes)
        }

        val first = port.archive(request)
        assertTrue(first is EvidenceArchiveResult.Failure)
        val checkpoint = (first as EvidenceArchiveResult.Failure).checkpoint
        assertEquals("artifact-1", checkpoint.canonicalArtifactId)
        assertEquals("object-1", checkpoint.evidenceObjectIdsBySha256[item.sha256])

        val second = port.archive(request, checkpoint)
        assertTrue(second is EvidenceArchiveResult.Success)
        assertEquals(setOf("menu-photo-1"), (second as EvidenceArchiveResult.Success).checkpoint.boundSourceFileIds)
        assertEquals(2, opens)
        assertEquals(1, transport.requests.count { "/storage/v1/object/" in it.url })
        assertTrue(transport.requests.filter { "/rest/v1/" in it.url }.all {
            "merge-duplicates" !in it.headers["Prefer"].orEmpty()
        })
        assertTrue(transport.requests.single { "/storage/v1/object/" in it.url }.body == null)
    }

    @Test
    fun immutableBindingRejectsRebindToAnotherEvidenceObject() = runTest {
        val canonicalJson = Files.readString(Path.of("..", "examples", "yeonsik-ocr.v3.restaurant.example.json"))
        val bytes = "menu evidence".toByteArray()
        val item = YeonsikBundleEvidence(
            sourceFileId = "menu-photo-1",
            type = SourceAttachmentType.MENU_PHOTO,
            path = "evidence/menu.jpg",
            sha256 = digest(bytes),
            mimeType = "image/jpeg",
            byteSize = bytes.size.toLong(),
            originalFilename = "menu.jpg",
        )
        val manifest = YeonsikBundleManifest(
            YEONSIK_BUNDLE_VERSION,
            "canonical.json",
            digest(canonicalJson.toByteArray()),
            listOf(item),
        )
        val bundle = YeonsikBundle(
            manifest,
            YeonsikBundleManifestCodec.encode(manifest),
            canonicalJson,
            YeonsikOcrEnvelopeCodec.decode(canonicalJson, "immutable-binding-test"),
        )
        val transport = ScriptedTransport(
            mutableListOf(
                EvidenceHttpResponse(201, "[{\"id\":\"artifact-1\"}]"),
                EvidenceHttpResponse(200, "{}"),
                EvidenceHttpResponse(201, "[{\"id\":\"object-a\"}]"),
                EvidenceHttpResponse(201, "[{\"id\":\"binding-a\"}]"),
                EvidenceHttpResponse(201, ""),
                EvidenceHttpResponse(200, "[{\"id\":\"binding-a\",\"source_type\":\"menu_photo\",\"evidence_object_id\":\"object-a\",\"metadata\":{\"original_filename\":\"menu.jpg\"}}]"),
            ),
        )
        val port = EvidenceSupabaseArchivePort(SignedInStore(), transport)
        val request = EvidenceArchiveRequest(bundle) { ByteArrayInputStream(bytes) }
        val first = port.archive(request)
        val firstCheckpoint = (first as EvidenceArchiveResult.Success).checkpoint

        val rebindCheckpoint = firstCheckpoint.copy(
            evidenceObjectIdsBySha256 = mapOf(item.sha256 to "object-b"),
            boundSourceFileIds = emptySet(),
        )
        val second = port.archive(request, rebindCheckpoint)
        assertTrue(second is EvidenceArchiveResult.Failure)
        assertTrue((second as EvidenceArchiveResult.Failure).issue.isNotBlank())
    }

    @Test
    fun unauthorizedArchiveRefreshesOnceAndRetriesTheStreamingRequest() = runTest {
        val canonicalJson = Files.readString(Path.of("..", "examples", "yeonsik-ocr.v3.restaurant.example.json"))
        val bytes = "menu evidence".toByteArray()
        val item = YeonsikBundleEvidence(
            "menu-photo-1", SourceAttachmentType.MENU_PHOTO, "evidence/menu.jpg", digest(bytes),
            "image/jpeg", bytes.size.toLong(), "menu.jpeg",
        )
        val manifest = YeonsikBundleManifest(
            YEONSIK_BUNDLE_VERSION, "canonical.json", digest(canonicalJson.toByteArray()), listOf(item),
        )
        val bundle = YeonsikBundle(
            manifest, YeonsikBundleManifestCodec.encode(manifest), canonicalJson,
            YeonsikOcrEnvelopeCodec.decode(canonicalJson, "refresh-test"),
        )
        val transport = ScriptedTransport(
            mutableListOf(
                EvidenceHttpResponse(401, "expired"),
                EvidenceHttpResponse(200, authResponse(TEST_USER_ID, jwtToken(TEST_USER_ID, "new"))),
                EvidenceHttpResponse(201, "[{\"id\":\"artifact-1\"}]"),
                EvidenceHttpResponse(200, "{}"),
                EvidenceHttpResponse(201, "[{\"id\":\"object-1\"}]"),
                EvidenceHttpResponse(201, "[{\"id\":\"binding-1\"}]"),
            ),
        )
        val port = EvidenceSupabaseArchivePort(SignedInStore(), transport)
        val result = port.archive(EvidenceArchiveRequest(bundle) { ByteArrayInputStream(bytes) })
        assertTrue(result is EvidenceArchiveResult.Success)
        assertEquals(1, transport.requests.count { "grant_type=refresh_token" in it.url })
        assertEquals(2, transport.requests.count { "/rest/v1/canonical_artifacts" in it.url })
        assertTrue(transport.requests.single { "/storage/v1/object/" in it.url }.url.endsWith("/${item.sha256}.jpg"))
        assertEquals(jwtToken(TEST_USER_ID, "new"), transport.requests.first { "/rest/v1/canonical_artifacts" in it.url && it.headers["Authorization"]?.contains(jwtToken(TEST_USER_ID, "new")) == true }
            .headers["Authorization"]?.substringAfter("Bearer "))
    }

    @Test
    fun canonicalRevisionArchivesSnapshotAndTypedEditValues() = runTest {
        val canonicalJson = "{\"schema_version\":\"yeonsik-ocr.v4\"}"
        val edit = CanonicalReviewEdit(
            id = "edit-1",
            fieldPath = "purchase_records[purchase-1].totals.grand_total_amount_krw",
            previousValue = "100.00",
            newValue = "120.00",
            provenanceJson = "{\"user_modified\":true}",
            editedAt = "2026-09-16T12:00:00+09:00",
            valueType = CanonicalFieldType.DECIMAL,
        )
        val transport = ScriptedTransport(
            mutableListOf(
                EvidenceHttpResponse(201, "[{\"id\":\"revision-1\",\"revision_seq\":1}]"),
                EvidenceHttpResponse(201, "[{\"id\":\"event-1\"}]"),
            ),
        )
        val port = EvidenceSupabaseArchivePort(SignedInStore(), transport)

        val result = port.archiveCanonicalRevision(
            CanonicalRevisionArchiveRequest(
                canonicalArtifactId = "artifact-1",
                revisionSeq = 1,
                canonicalSha256 = digest(canonicalJson.toByteArray()),
                canonicalJson = canonicalJson,
                schemaVersion = "yeonsik-ocr.v4",
                mode = "purchase",
                edits = listOf(edit),
            ),
        )

        assertEquals(CanonicalRevisionArchiveResult.Success("revision-1", 1), result)
        assertTrue(transport.requests.any { it.method == "GET" && it.url.endsWith("/auth/v1/user") })
        val revisionPost = transport.requests.single {
            it.method == "POST" && "/rest/v1/canonical_revisions" in it.url
        }
        val revisionBody = Json.parseToJsonElement(revisionPost.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("artifact-1", revisionBody["canonical_artifact_id"]?.toString()?.trim('"'))
        assertEquals(TEST_USER_ID, revisionBody["owner_id"]?.toString()?.trim('"'))
        val eventPost = transport.requests.single {
            it.method == "POST" && "/rest/v1/canonical_edit_events" in it.url
        }
        val eventBody = Json.parseToJsonElement(eventPost.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("100.0", eventBody["previous_value"]?.toString())
        assertEquals("120.0", eventBody["new_value"]?.toString())
        assertEquals("{\"user_modified\":true}", eventBody["provenance"]?.toString())
    }


    @Test
    fun mismatchedConfiguredUserAndJwtSubjectFailsBeforeRevisionPost() = runTest {
        val store = SignedInStore(
            userId = TEST_USER_ID,
            accessToken = jwtToken(OTHER_USER_ID),
            refreshToken = "",
        )
        val transport = ScriptedTransport(mutableListOf())
        val result = EvidenceSupabaseArchivePort(store, transport)
            .archiveCanonicalRevision(canonicalRevisionRequest())

        assertTrue(result is CanonicalRevisionArchiveResult.Failure)
        val issue = (result as CanonicalRevisionArchiveResult.Failure).issue
        assertTrue(issue.contains("Evidence session identity mismatch"))
        assertTrue(transport.requests.none {
            it.method == "POST" && "/rest/v1/canonical_revisions" in it.url
        })
    }

    @Test
    fun refreshRepairsMixedSessionBeforeRevisionPublication() = runTest {
        val store = SignedInStore(
            userId = TEST_USER_ID,
            accessToken = jwtToken(OTHER_USER_ID),
            refreshToken = "refresh-for-other-user",
        )
        val transport = ScriptedTransport(
            responses = mutableListOf(
                EvidenceHttpResponse(200, authResponse(OTHER_USER_ID, jwtToken(OTHER_USER_ID, "refreshed"))),
                EvidenceHttpResponse(201, "[{\"id\":\"revision-1\",\"revision_seq\":1}]"),
            ),
            artifactOwnerId = OTHER_USER_ID,
        )
        val result = EvidenceSupabaseArchivePort(store, transport)
            .archiveCanonicalRevision(canonicalRevisionRequest())

        assertEquals(CanonicalRevisionArchiveResult.Success("revision-1", 1), result)
        assertEquals(OTHER_USER_ID, store.read().userId)
        assertTrue(store.read().isSignedIn)
        assertEquals(
            1,
            transport.requests.count { "grant_type=refresh_token" in it.url },
        )
        val revisionPost = transport.requests.single {
            it.method == "POST" && "/rest/v1/canonical_revisions" in it.url
        }
        assertEquals(
            OTHER_USER_ID,
            Json.parseToJsonElement(revisionPost.body!!.toString(Charsets.UTF_8))
                .jsonObject["owner_id"]?.toString()?.trim('"'),
        )
    }

    @Test
    fun remoteAuthUserMismatchIsRejectedWithoutRevisionPost() = runTest {
        val transport = ScriptedTransport(
            responses = mutableListOf(),
            remoteAuthUserId = OTHER_USER_ID,
        )
        val result = EvidenceSupabaseArchivePort(
            SignedInStore(refreshToken = ""),
            transport,
        ).archiveCanonicalRevision(canonicalRevisionRequest())

        assertTrue(result is CanonicalRevisionArchiveResult.Failure)
        assertTrue((result as CanonicalRevisionArchiveResult.Failure).issue.contains("Auth user"))
        assertTrue(transport.requests.none {
            it.method == "POST" && "/rest/v1/canonical_revisions" in it.url
        })
    }

    @Test
    fun staleCanonicalArtifactOwnerFailsBeforeRevisionPost() = runTest {
        val transport = ScriptedTransport(
            responses = mutableListOf(),
            artifactOwnerId = OTHER_USER_ID,
        )
        val result = EvidenceSupabaseArchivePort(SignedInStore(), transport)
            .archiveCanonicalRevision(canonicalRevisionRequest())

        assertTrue(result is CanonicalRevisionArchiveResult.Failure)
        assertTrue((result as CanonicalRevisionArchiveResult.Failure).issue.contains(
            "Evidence session does not own the archived canonical artifact. Re-authentication/re-archive required.",
        ))
        assertTrue(transport.requests.any {
            it.method == "GET" && "/rest/v1/canonical_artifacts" in it.url && "select=id,owner_id" in it.url
        })
        assertTrue(transport.requests.none {
            it.method == "POST" && "/rest/v1/canonical_revisions" in it.url
        })
    }

    @Test
    fun staleParentRevisionOwnerFailsBeforeRevisionPost() = runTest {
        val transport = ScriptedTransport(
            responses = mutableListOf(),
            parentRevisionOwnerId = OTHER_USER_ID,
        )
        val result = EvidenceSupabaseArchivePort(SignedInStore(), transport)
            .archiveCanonicalRevision(canonicalRevisionRequest(parentRevisionId = "parent-1"))

        assertTrue(result is CanonicalRevisionArchiveResult.Failure)
        assertTrue((result as CanonicalRevisionArchiveResult.Failure).issue.contains(
            "Evidence session does not own the parent canonical revision",
        ))
        assertTrue(transport.requests.none {
            it.method == "POST" && "/rest/v1/canonical_revisions" in it.url
        })
    }

    @Test
    fun reauthenticatedSessionRetriesTheSamePendingRevisionRequest() = runTest {
        val store = SignedInStore(
            userId = TEST_USER_ID,
            accessToken = jwtToken(OTHER_USER_ID),
            refreshToken = "",
        )
        val transport = ScriptedTransport(
            mutableListOf(EvidenceHttpResponse(201, "[{\"id\":\"revision-1\",\"revision_seq\":1}]")),
        )
        val port = EvidenceSupabaseArchivePort(store, transport)
        val request = canonicalRevisionRequest()

        val first = port.archiveCanonicalRevision(request)
        assertTrue(first is CanonicalRevisionArchiveResult.Failure)
        assertTrue((first as CanonicalRevisionArchiveResult.Failure).issue.contains("identity mismatch"))
        assertTrue(transport.requests.none {
            it.method == "POST" && "/rest/v1/canonical_revisions" in it.url
        })

        store.replaceSession(TEST_USER_ID, jwtToken(TEST_USER_ID, "reauth"), "reauth-refresh")
        val retried = port.archiveCanonicalRevision(request)

        assertEquals(CanonicalRevisionArchiveResult.Success("revision-1", 1), retried)
        assertEquals(
            1,
            transport.requests.count { it.method == "POST" && "/rest/v1/canonical_revisions" in it.url },
        )
        val post = transport.requests.single { it.method == "POST" && "/rest/v1/canonical_revisions" in it.url }
        val body = Json.parseToJsonElement(post.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(request.revisionSeq, body["revision_seq"]?.toString()?.toLong())
        assertEquals(request.canonicalArtifactId, body["canonical_artifact_id"]?.toString()?.trim('"'))
    }

    @Test
    fun existingCheckpointWithForeignArtifactDoesNotResumeArchive() = runTest {
        val canonicalJson = Files.readString(Path.of("..", "examples", "yeonsik-ocr.v3.restaurant.example.json"))
        val manifest = YeonsikBundleManifest(
            YEONSIK_BUNDLE_VERSION,
            "canonical.json",
            digest(canonicalJson.toByteArray()),
            emptyList(),
        )
        val bundle = YeonsikBundle(
            manifest,
            YeonsikBundleManifestCodec.encode(manifest),
            canonicalJson,
            YeonsikOcrEnvelopeCodec.decode(canonicalJson, "foreign-checkpoint"),
        )
        val transport = ScriptedTransport(
            responses = mutableListOf(),
            artifactOwnerId = OTHER_USER_ID,
        )
        val checkpoint = EvidenceArchiveCheckpoint(canonicalArtifactId = "artifact-1")
        val result = EvidenceSupabaseArchivePort(SignedInStore(), transport)
            .archive(EvidenceArchiveRequest(bundle) { error("no evidence expected") }, checkpoint)

        assertTrue(result is EvidenceArchiveResult.Failure)
        assertTrue((result as EvidenceArchiveResult.Failure).issue.contains(
            "Evidence session does not own the archived canonical artifact",
        ))
        assertTrue(transport.requests.none { "/storage/v1/object/" in it.url })
    }

    @Test
    fun actualRlsRejectionHasActionableDatabaseError() = runTest {
        val transport = ScriptedTransport(
            responses = mutableListOf(
                EvidenceHttpResponse(403, "{\"code\":\"42501\",\"message\":\"new row violates row-level security policy\"}"),
            ),
        )
        val result = EvidenceSupabaseArchivePort(SignedInStore(), transport)
            .archiveCanonicalRevision(canonicalRevisionRequest())

        assertTrue(result is CanonicalRevisionArchiveResult.Failure)
        val issue = (result as CanonicalRevisionArchiveResult.Failure).issue
        assertTrue(issue.contains("Supabase RLS rejected canonical revision insert-or-reuse (403/42501)"))
        assertTrue(issue.contains("Re-authenticate"))
    }

    private suspend fun archiveWithUploadResponse(response: EvidenceHttpResponse): Pair<EvidenceArchiveResult, ScriptedTransport> {
        val canonicalJson = Files.readString(Path.of("..", "examples", "yeonsik-ocr.v3.restaurant.example.json"))
        val bytes = "menu evidence".toByteArray()
        val item = YeonsikBundleEvidence(
            sourceFileId = "menu-photo-1",
            type = SourceAttachmentType.MENU_PHOTO,
            path = "evidence/menu.jpg",
            sha256 = digest(bytes),
            mimeType = "image/jpeg",
            byteSize = bytes.size.toLong(),
            originalFilename = "menu.jpg",
        )
        val manifest = YeonsikBundleManifest(
            YEONSIK_BUNDLE_VERSION,
            "canonical.json",
            digest(canonicalJson.toByteArray()),
            listOf(item),
        )
        val bundle = YeonsikBundle(
            manifest,
            YeonsikBundleManifestCodec.encode(manifest),
            canonicalJson,
            YeonsikOcrEnvelopeCodec.decode(canonicalJson, "archive-upload-test"),
        )
        val transport = ScriptedTransport(
            mutableListOf(
                EvidenceHttpResponse(201, "[{\"id\":\"artifact-1\"}]"),
                response,
                EvidenceHttpResponse(201, "[{\"id\":\"object-1\"}]"),
                EvidenceHttpResponse(201, "[{\"id\":\"binding-1\"}]"),
            ),
        )
        val result = EvidenceSupabaseArchivePort(SignedInStore(), transport)
            .archive(EvidenceArchiveRequest(bundle) { ByteArrayInputStream(bytes) })
        return result to transport
    }

    private class ScriptedTransport(
        private val responses: MutableList<EvidenceHttpResponse>,
        private val remoteAuthUserId: String? = null,
        private val artifactOwnerId: String? = TEST_USER_ID,
        private val parentRevisionOwnerId: String? = TEST_USER_ID,
    ) : EvidenceHttpTransport {
        val requests = mutableListOf<EvidenceHttpRequest>()

        override suspend fun execute(request: EvidenceHttpRequest): EvidenceHttpResponse {
            requests += request
            request.bodyStream?.invoke()?.use { input -> input.copyTo(ByteArrayOutputStream()) }

            if (request.method == "GET" && request.url.endsWith("/auth/v1/user")) {
                val token = request.headers["Authorization"]?.substringAfter("Bearer ").orEmpty()
                val userId = remoteAuthUserId ?: evidenceAccessTokenSubject(token) ?: TEST_USER_ID
                return EvidenceHttpResponse(200, """{"id":"$userId"}""")
            }
            if (request.method == "GET" &&
                "/rest/v1/canonical_artifacts?" in request.url &&
                "select=id,owner_id" in request.url
            ) {
                val body = artifactOwnerId?.let { """[{"id":"artifact-1","owner_id":"$it"}]""" } ?: "[]"
                return EvidenceHttpResponse(200, body)
            }
            if (request.method == "GET" &&
                "/rest/v1/canonical_revisions?" in request.url &&
                "select=id,canonical_artifact_id,owner_id" in request.url
            ) {
                val body = parentRevisionOwnerId?.let {
                    """[{"id":"parent-1","canonical_artifact_id":"artifact-1","owner_id":"$it"}]"""
                } ?: "[]"
                return EvidenceHttpResponse(200, body)
            }
            return responses.removeFirst()
        }
    }

    private class SignedInStore(
        userId: String = TEST_USER_ID,
        accessToken: String = jwtToken(userId),
        refreshToken: String = "refresh",
    ) : EvidenceSupabaseStore {
        private var config = EvidenceSupabaseConfig(
            url = "https://evidence.example.test",
            publishableKey = "sb_publishable_12345678901234567890",
            userId = userId,
            email = "test@example.com",
            accessToken = accessToken,
            refreshToken = refreshToken,
        )

        override fun read(): EvidenceSupabaseConfig = config
        override fun saveConnection(url: String, publishableKey: String) = Result.success(config)

        override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String): Result<EvidenceSupabaseConfig> {
            config = config.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
            return Result.success(config)
        }

        fun replaceSession(userId: String, accessToken: String, refreshToken: String) {
            config = config.copy(userId = userId, accessToken = accessToken, refreshToken = refreshToken)
        }

        override fun clearSession(): Boolean {
            config = config.copy(userId = "", email = "", accessToken = "", refreshToken = "")
            return true
        }
    }

    private fun canonicalRevisionRequest(parentRevisionId: String? = null): CanonicalRevisionArchiveRequest {
        val canonicalJson = "{\"schema_version\":\"yeonsik-ocr.v4\"}"
        return CanonicalRevisionArchiveRequest(
            canonicalArtifactId = "artifact-1",
            revisionSeq = 1,
            parentRevisionId = parentRevisionId,
            canonicalSha256 = digest(canonicalJson.toByteArray()),
            canonicalJson = canonicalJson,
            schemaVersion = "yeonsik-ocr.v4",
            mode = "purchase",
        )
    }

    private fun authResponse(userId: String, accessToken: String = jwtToken(userId)): String =
        """{"access_token":"$accessToken","refresh_token":"refresh-$userId","user":{"id":"$userId"}}"""

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
