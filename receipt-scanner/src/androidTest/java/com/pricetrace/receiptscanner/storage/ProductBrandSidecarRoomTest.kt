package com.pricetrace.receiptscanner.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProductBrandSidecarRoomTest {
    @Test fun selectionRestoresFromRoomWithoutChangingSourceFingerprint()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ReceiptDatabase::class.java).allowMainThreadQueries().build()
        try {
            val draft=ProductBrandResolutionDraft("p",ProductBrandFactField.BRAND,"Observed",ProductBrandSelectionIntent.SELECTED,"00000000-0000-4000-8000-000000000001","Canonical")
            val session=IngestionSession("sidecar","document","source-key","fingerprint",IngestionReviewStatus.NEEDS_REVIEW,"now","now",emptyList(),brandResolutionDrafts=listOf(draft))
            RoomIngestionSessionStore(db.receiptSessionDao()).save(session)
            val restored=requireNotNull(RoomIngestionSessionStore(db.receiptSessionDao()).get("sidecar"))
            assertEquals(session,restored)
            assertEquals("fingerprint",restored.canonicalFingerprint)
        } finally { db.close() }
    }
}
