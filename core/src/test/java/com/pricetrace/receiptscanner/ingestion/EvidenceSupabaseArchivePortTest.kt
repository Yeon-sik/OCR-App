package com.pricetrace.receiptscanner.ingestion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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
        assertEquals(1, opens)
        assertEquals(1, transport.requests.count { "/storage/v1/object/" in it.url })
    }

    private class ScriptedTransport(
        private val responses: MutableList<EvidenceHttpResponse>,
    ) : EvidenceHttpTransport {
        val requests = mutableListOf<EvidenceHttpRequest>()
        override suspend fun execute(request: EvidenceHttpRequest): EvidenceHttpResponse {
            requests += request
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
        override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String) =
            Result.success(config)
        override fun clearSession() = true
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
