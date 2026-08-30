package com.pricetrace.receiptscanner.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.ProjectionState
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IngestionPersistenceInstrumentedTest {
    @Test
    fun projectionStatusAndEvidenceSurviveRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, ReceiptDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        lateinit var session: IngestionSession
        try {
            session = IngestionSession(
                ingestionId = "ingestion-test",
                localDocumentId = "local-test",
                envelopeStorageKey = "local-test/ingestion/yeonsik-ocr.json",
                canonicalFingerprint = "a".repeat(64),
                reviewStatus = IngestionReviewStatus.READY,
                createdAt = "2026-08-27T00:00:00Z",
                updatedAt = "2026-08-27T00:01:00Z",
                projections = listOf(
                    ProjectionState(IngestionProjection.PRICETRACE_RECEIPT, ProjectionStatus.UPLOADED, "k", "remote", updatedAt = "2026-08-27T00:01:00Z"),
                    ProjectionState(IngestionProjection.FITNESS_NUTRITION, ProjectionStatus.FAILED, attemptCount = 1, lastError = "timeout", updatedAt = "2026-08-27T00:01:00Z"),
                ),
                attachments = listOf(LocalEvidence("receipt", SourceAttachmentType.RECEIPT, true)),
                verifiedArtifactFingerprints = mapOf("receipt" to "receipt-artifact-fingerprint"),
            )
            RoomIngestionSessionStore(database.receiptSessionDao()).save(session)
        } finally {
            database.close()
        }

        val restartedDatabase = Room.databaseBuilder(context, ReceiptDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        try {
            val restarted = RoomIngestionSessionStore(restartedDatabase.receiptSessionDao()).get("ingestion-test")
            assertEquals(session, restarted)
            assertEquals("a".repeat(64), restarted?.canonicalFingerprint)
            assertEquals(mapOf("receipt" to "receipt-artifact-fingerprint"), restarted?.verifiedArtifactFingerprints)
        } finally {
            restartedDatabase.close()
            context.deleteDatabase(TEST_DB)
        }
    }
    companion object {
        private const val TEST_DB = "receipt-db-ingestion-persistence-test"
    }
}
