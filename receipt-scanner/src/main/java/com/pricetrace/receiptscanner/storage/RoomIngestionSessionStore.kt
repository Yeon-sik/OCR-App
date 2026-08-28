package com.pricetrace.receiptscanner.storage

import android.content.Context
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.IngestionSessionStore

class RoomIngestionSessionStore internal constructor(
    private val dao: ReceiptSessionDao,
) : IngestionSessionStore {
    companion object {
        fun create(context: Context): RoomIngestionSessionStore = RoomIngestionSessionStore(
            ReceiptDatabase.getInstance(context).receiptSessionDao(),
        )
    }

    override suspend fun get(ingestionId: String): IngestionSession? {
        val entity = dao.getIngestionSession(ingestionId) ?: return null
        return entity.toDomain(dao.getIngestionProjections(ingestionId), dao.getIngestionAttachments(ingestionId))
    }

    override suspend fun findByCanonicalFingerprint(fingerprint: String): IngestionSession? {
        val entity = dao.getIngestionSessionByFingerprint(fingerprint) ?: return null
        return entity.toDomain(dao.getIngestionProjections(entity.ingestionId), dao.getIngestionAttachments(entity.ingestionId))
    }

    override suspend fun delete(ingestionId: String) {
        dao.deleteIngestionSnapshot(ingestionId)
    }

    override suspend fun save(session: IngestionSession) {
        dao.upsertIngestionSnapshot(
            session.toEntity(),
            session.projections.map { it.toEntity(session.ingestionId) },
            session.attachments.map { it.toEntity(session.ingestionId) },
        )
    }
}
