package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.VerificationBasis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class DesktopReviewRecoveryTest {
    private val path = "product_candidates[product-brandx-chicken-20260910].product_name"
    private fun controller(dir: Path) = DesktopIngestionController(DesktopSessionStore(dir),
        DesktopProjectionBundle(DesktopRuntimeConfig.load(emptyMap(), dir.resolve("none.env"))))
    private suspend fun sample(c: DesktopIngestionController) = c.importJson(File("../examples/yeonsik-ocr.v3.text-only-retail.example.json").readText())

    @Test fun restartRecoversUnappliedInputWithoutChangingExistingSessionBytes() = runBlocking {
        val dir = Files.createTempDirectory("draft-restart")
        val c = controller(dir)
        sample(c)
        val id = c.state.value.ingestionId!!
        val original = Files.readAllBytes(dir.resolve("$id.json"))
        c.updateReviewDraft(path, "복원할 상품명")
        val restarted = controller(dir)
        assertTrue(restarted.restoreLatestIfAvailable())
        assertEquals("복원할 상품명", restarted.state.value.reviewDrafts[path])
        assertNotNull(restarted.state.value.draftRestoreNotice)
        assertArrayEquals(original, Files.readAllBytes(dir.resolve("$id.json")))
        assertNotNull(reviewConfirmationBlocker(restarted.state.value, false, true))
        restarted.applyAllReviewDrafts()
        val afterApply = controller(dir)
        afterApply.load(id)
        assertTrue(afterApply.state.value.reviewDrafts.isEmpty())
        assertEquals("복원할 상품명", afterApply.state.value.envelope!!.productCandidates.single().productName)
    }

    @Test fun cancellationRemovesSidecarAndNewerSessionDoesNotHideOlderDraft() = runBlocking {
        val dir = Files.createTempDirectory("draft-selection")
        val c = controller(dir)
        sample(c)
        val id = c.state.value.ingestionId!!
        val original = Files.readAllBytes(dir.resolve("$id.json"))
        c.updateReviewDraft(path, "미완료 입력")
        c.importJson(File("../examples/yeonsik-ocr.v3.merchant.example.json").readText())
        val restarted = controller(dir)
        restarted.restoreLatestIfAvailable()
        assertEquals(id, restarted.state.value.ingestionId)
        restarted.discardAllReviewDrafts()
        assertTrue(restarted.flushReviewDrafts())
        assertFalse(Files.exists(dir.resolve("drafts/$id.json")))
        assertArrayEquals(original, Files.readAllBytes(dir.resolve("$id.json")))
    }

    @Test fun writeFailurePreservesInputBlocksCloseAndCanBeRetried() = runBlocking {
        val dir = Files.createTempDirectory("draft-failure")
        val c = controller(dir)
        assertTrue(c.flushReviewDrafts())
        sample(c)
        c.draftStore = object : DesktopReviewDraftStore(dir.resolve("drafts")) {
            override fun save(id: String, fingerprint: String, values: Map<String, String>) { throw java.io.IOException("disk full") }
        }
        c.updateReviewDraft(path, "저장 실패해도 보존")
        assertNotNull(c.state.value.draftSaveError)
        assertFalse(c.flushReviewDrafts())
        assertEquals("저장 실패해도 보존", c.state.value.reviewDrafts[path])
        c.verify(VerificationBasis.MANUAL_CANONICAL_REVIEW)
        assertNull(c.state.value.session!!.verifiedCanonicalFingerprint)
        c.draftStore = DesktopReviewDraftStore(dir.resolve("drafts"))
        assertTrue(c.flushReviewDrafts())
        val restarted = controller(dir)
        restarted.restoreLatestIfAvailable()
        assertEquals("저장 실패해도 보존", restarted.state.value.reviewDrafts[path])
    }

    @Test fun unreadableDraftIsNeverOverwrittenByEditingOrClosing() = runBlocking {
        val dir = Files.createTempDirectory("draft-corrupt")
        val c = controller(dir)
        sample(c)
        val id = c.state.value.ingestionId!!
        Files.createDirectories(dir.resolve("drafts"))
        val file = dir.resolve("drafts/$id.json")
        Files.writeString(file, "{ incomplete saved draft")
        val restarted = controller(dir)
        restarted.restoreLatestIfAvailable()
        assertNotNull(restarted.state.value.draftSaveError)
        restarted.updateReviewDraft(path, "덮어쓰기 금지")
        restarted.discardAllReviewDrafts()
        assertTrue(restarted.flushReviewDrafts())
        assertNotNull(reviewConfirmationBlocker(restarted.state.value, false, true))
        assertEquals("{ incomplete saved draft", Files.readString(file))
    }

    @Test fun changedCanonicalIsFlaggedAndNeverAutomaticallyApplied() = runBlocking {
        val dir = Files.createTempDirectory("draft-stale")
        val c = controller(dir)
        sample(c)
        val id = c.state.value.ingestionId!!
        val before = c.state.value.canonicalJson
        c.draftStore.save(id, "old-fingerprint", mapOf(path to "이전 초안"))
        val restarted = controller(dir)
        restarted.restoreLatestIfAvailable()
        assertTrue(restarted.state.value.draftRestoreNotice.orEmpty().contains("변경되었습니다"))
        assertEquals(before, restarted.state.value.canonicalJson)
        assertNotNull(reviewConfirmationBlocker(restarted.state.value, false, true))
    }
}
