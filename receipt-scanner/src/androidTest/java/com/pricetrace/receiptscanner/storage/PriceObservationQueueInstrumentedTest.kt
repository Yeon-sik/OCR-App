package com.pricetrace.receiptscanner.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pricetrace.receiptscanner.publisher.PriceObservationAppliedAction
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitPayload
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResponse
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResult
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PriceObservationQueueInstrumentedTest {
    @Test
    fun roomQueuePersistsOpaqueKeyAndSuccessfulRpcResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = RoomPriceObservationQueueRepository.create(context)
        val documentId = "queue-instrumented-${UUID.randomUUID()}"
        val lineItemId = "line-${UUID.randomUUID()}"
        val queued = repository.enqueue(
            storeId = STORE_ID,
            observedOn = "2026-08-13",
            catalogProductId = CATALOG_PRODUCT_ID,
            unitPriceKrw = 1590,
            localDocumentId = documentId,
            localLineItemId = lineItemId,
            createdAt = "2026-08-13T12:00:00+09:00",
        )

        assertEquals(PriceObservationQueueStatus.PENDING, queued.status)
        assertFalse(queued.payload.idempotencyKey.contains(documentId))
        assertFalse(queued.payload.idempotencyKey.contains(lineItemId))
        assertNotEquals(documentId, queued.payload.idempotencyKey)
        assertNotEquals(lineItemId, queued.payload.idempotencyKey)
        assertEquals(queued, repository.get(queued.queueId))
        assertNotNull(repository.latestForLine(documentId, lineItemId))

        val processor = PriceObservationQueueProcessor(
            queue = repository,
            submitter = object : PriceObservationSubmitter {
                override suspend fun submit(payload: PriceObservationSubmitPayload): PriceObservationSubmitResult =
                    PriceObservationSubmitResult.Success(
                        PriceObservationSubmitResponse(
                            observationId = OBSERVATION_ID,
                            replayed = false,
                            appliedAction = PriceObservationAppliedAction.CREATED,
                        ),
                    )
            },
        )

        val succeeded = processor.submit(queued.queueId)

        assertEquals(PriceObservationQueueStatus.SUCCEEDED, succeeded.status)
        assertEquals(PriceObservationAppliedAction.CREATED, succeeded.appliedAction)
        assertEquals(1, succeeded.attemptCount)
        assertEquals(succeeded, repository.get(queued.queueId))
    }

    private companion object {
        const val STORE_ID = "11111111-1111-4111-8111-111111111111"
        const val CATALOG_PRODUCT_ID = "22222222-2222-4222-8222-222222222222"
        const val OBSERVATION_ID = "33333333-3333-4333-8333-333333333333"
    }
}
