package com.pricetrace.receiptscanner.ingestion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class EvidenceSupabaseArchivePortTest {
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
                EvidenceHttpResponse(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"user\":{\"id\":\"11111111-1111-1111-1111-111111111111\"}}"),
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
        assertEquals("new-access", transport.requests.first { "/rest/v1/canonical_artifacts" in it.url && it.headers["Authorization"]?.contains("new-access") == true }
            .headers["Authorization"]?.substringAfter("Bearer "))
    }

    private class ScriptedTransport(
        private val responses: MutableList<EvidenceHttpResponse>,
    ) : EvidenceHttpTransport {
        val requests = mutableListOf<EvidenceHttpRequest>()
        override suspend fun execute(request: EvidenceHttpRequest): EvidenceHttpResponse {
            requests += request
            request.bodyStream?.invoke()?.use { input -> input.copyTo(ByteArrayOutputStream()) }
            return responses.removeFirst()
        }
    }

    private class SignedInStore : EvidenceSupabaseStore {
        private var config = EvidenceSupabaseConfig(
            url = "https://evidence.example.test",
            publishableKey = "sb_publishable_12345678901234567890",
            userId = "11111111-1111-1111-1111-111111111111",
            email = "test@example.com",
            accessToken = "token",
            refreshToken = "refresh",
        )
        override fun read(): EvidenceSupabaseConfig = config
        override fun saveConnection(url: String, publishableKey: String) = Result.success(config)
        override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String): Result<EvidenceSupabaseConfig> {
            config = config.copy(userId = userId, email = email, accessToken = accessToken, refreshToken = refreshToken)
            return Result.success(config)
        }
        override fun clearSession() = true
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
