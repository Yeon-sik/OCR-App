package com.pricetrace.receiptscanner.storage

import com.pricetrace.receiptscanner.publisher.PriceObservationAppliedAction
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitPayload
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResponse
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResult
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceObservationQueueProcessorTest {
    @Test
    fun `timeout is retryable and preserves the same opaque key for a later success`() = runTest {
        val queue = FakeQueue(PriceObservationIdempotencyKeyGenerator { "random-opaque-key" })
        val submitter = FakeSubmitter(
            PriceObservationSubmitResult.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT),
            PriceObservationSubmitResult.Success(
                PriceObservationSubmitResponse(
                    observationId = "33333333-3333-3333-3333-333333333333",
                    replayed = true,
                    appliedAction = PriceObservationAppliedAction.REPLAYED,
                ),
            ),
        )
        val processor = PriceObservationQueueProcessor(queue, submitter)
        val first = queue.enqueue(
            storeId = "11111111-1111-1111-1111-111111111111",
            observedOn = "2026-08-13",
            catalogProductId = "22222222-2222-2222-2222-222222222222",
            unitPriceKrw = 1_000,
            localDocumentId = "receipt-document-id",
            localLineItemId = "receipt-item-id",
        )

        val retryable = processor.submit(first.queueId)
        assertEquals(PriceObservationQueueStatus.RETRYABLE_FAILURE, retryable.status)
        assertEquals(1, retryable.attemptCount)
        assertFalse(retryable.payload.idempotencyKey.contains("receipt-document-id"))
        assertFalse(retryable.payload.idempotencyKey.contains("receipt-item-id"))

        val succeeded = processor.submit(first.queueId)
        assertEquals(PriceObservationQueueStatus.SUCCEEDED, succeeded.status)
        assertEquals(2, succeeded.attemptCount)
        assertEquals(PriceObservationAppliedAction.REPLAYED, succeeded.appliedAction)
        assertEquals(retryable.payload.idempotencyKey, succeeded.payload.idempotencyKey)
        assertEquals(2, submitter.calls)
    }

    @Test
    fun `authentication and invalid selection become needs review without automatic retry`() = runTest {
        val queue = FakeQueue(PriceObservationIdempotencyKeyGenerator { "another-random-key" })
        val submitter = FakeSubmitter(
            PriceObservationSubmitResult.Failure(PriceObservationFailureKind.AUTHENTICATION),
        )
        val processor = PriceObservationQueueProcessor(queue, submitter)
        val entry = queue.enqueue(
            storeId = "11111111-1111-1111-1111-111111111111",
            observedOn = "2026-08-13",
            catalogProductId = "22222222-2222-2222-2222-222222222222",
            unitPriceKrw = 1_000,
        )

        val needsReview = processor.submit(entry.queueId)
        assertEquals(PriceObservationQueueStatus.NEEDS_REVIEW, needsReview.status)
        assertEquals(1, submitter.calls)
        assertTrue(needsReview.lastError!!.contains("authentication"))
    }

    private class FakeSubmitter(
        private vararg val results: PriceObservationSubmitResult,
    ) : PriceObservationSubmitter {
        var calls: Int = 0
            private set

        override suspend fun submit(payload: PriceObservationSubmitPayload): PriceObservationSubmitResult {
            calls += 1
            return results[calls - 1]
        }
    }

    private class FakeQueue(generator: PriceObservationIdempotencyKeyGenerator) : PriceObservationQueueRepository {
        private var entry: PriceObservationQueueEntry? = null
        private val keyGenerator = generator

        override suspend fun enqueue(
            storeId: String,
            observedOn: String,
            catalogProductId: String,
            unitPriceKrw: Int,
            localDocumentId: String?,
            localLineItemId: String?,
            createdAt: String,
        ): PriceObservationQueueEntry {
            val created = PriceObservationQueueEntry(
                queueId = "queue-1",
                localDocumentId = localDocumentId,
                localLineItemId = localLineItemId,
                payload = PriceObservationSubmitPayload(
                    idempotencyKey = keyGenerator.generate(),
                    storeId = storeId,
                    observedOn = observedOn,
                    catalogProductId = catalogProductId,
                    unitPriceKrw = unitPriceKrw,
                ),
                status = PriceObservationQueueStatus.PENDING,
                attemptCount = 0,
                lastError = null,
                observationId = null,
                replayed = null,
                appliedAction = null,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
            entry = created
            return created
        }

        override suspend fun get(queueId: String): PriceObservationQueueEntry? = entry

        override suspend fun latestForLine(localDocumentId: String, localLineItemId: String) = null

        override suspend fun markAttempt(queueId: String, updatedAt: String) {
            entry = requireNotNull(entry).copy(
                attemptCount = requireNotNull(entry).attemptCount + 1,
                updatedAt = updatedAt,
                lastError = null,
            )
        }

        override suspend fun recordSuccess(
            queueId: String,
            response: PriceObservationSubmitResponse,
            updatedAt: String,
        ) {
            entry = requireNotNull(entry).copy(
                status = PriceObservationQueueStatus.SUCCEEDED,
                observationId = response.observationId,
                replayed = response.replayed,
                appliedAction = response.appliedAction,
                updatedAt = updatedAt,
            )
        }

        override suspend fun recordFailure(
            queueId: String,
            failure: PriceObservationSubmitResult.Failure,
            updatedAt: String,
        ) {
            entry = requireNotNull(entry).copy(
                status = if (failure.retryable) {
                    PriceObservationQueueStatus.RETRYABLE_FAILURE
                } else {
                    PriceObservationQueueStatus.NEEDS_REVIEW
                },
                lastError = failure.message ?: failure.kind.name.lowercase(),
                updatedAt = updatedAt,
            )
        }
    }
}
