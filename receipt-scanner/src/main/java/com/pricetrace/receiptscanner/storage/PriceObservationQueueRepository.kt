package com.pricetrace.receiptscanner.storage

import android.content.Context
import com.pricetrace.receiptscanner.publisher.PriceObservationAppliedAction
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitPayload
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResponse
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResult
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitter
import kotlinx.coroutines.CancellationException
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

enum class PriceObservationQueueStatus(val wireValue: String) {
    PENDING("pending"),
    RETRYABLE_FAILURE("retryable_failure"),
    NEEDS_REVIEW("needs_review"),
    SUCCEEDED("succeeded"),
}

data class PriceObservationQueueEntry(
    val queueId: String,
    /** Local-only context for returning to the review screen; never part of the RPC payload. */
    val localDocumentId: String?,
    val localLineItemId: String?,
    val payload: PriceObservationSubmitPayload,
    val status: PriceObservationQueueStatus,
    val attemptCount: Int,
    val lastError: String?,
    val observationId: String?,
    val replayed: Boolean?,
    val appliedAction: PriceObservationAppliedAction?,
    val createdAt: String,
    val updatedAt: String,
)

fun interface PriceObservationIdempotencyKeyGenerator {
    fun generate(): String
}

object SecurePriceObservationIdempotencyKeyGenerator : PriceObservationIdempotencyKeyGenerator {
    private val random = SecureRandom()

    override fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

interface PriceObservationQueueRepository {
    suspend fun enqueue(
        storeId: String,
        observedOn: String,
        catalogProductId: String,
        unitPriceKrw: Int,
        localDocumentId: String? = null,
        localLineItemId: String? = null,
        createdAt: String = OffsetDateTime.now().toString(),
    ): PriceObservationQueueEntry

    suspend fun get(queueId: String): PriceObservationQueueEntry?

    suspend fun latestForLine(
        localDocumentId: String,
        localLineItemId: String,
    ): PriceObservationQueueEntry?

    suspend fun markAttempt(queueId: String, updatedAt: String = OffsetDateTime.now().toString())

    suspend fun recordSuccess(
        queueId: String,
        response: PriceObservationSubmitResponse,
        updatedAt: String = OffsetDateTime.now().toString(),
    )

    suspend fun recordFailure(
        queueId: String,
        failure: PriceObservationSubmitResult.Failure,
        updatedAt: String = OffsetDateTime.now().toString(),
    )
}

/** Owns local retry/needs-review transitions while keeping the receipt publisher untouched. */
class PriceObservationQueueProcessor(
    private val queue: PriceObservationQueueRepository,
    private val submitter: PriceObservationSubmitter,
) {
    suspend fun submit(queueId: String): PriceObservationQueueEntry {
        val entry = requireNotNull(queue.get(queueId)) { "Price observation queue entry not found" }
        require(entry.status == PriceObservationQueueStatus.PENDING ||
            entry.status == PriceObservationQueueStatus.RETRYABLE_FAILURE) {
            "Only pending or retryable price observations can be submitted"
        }
        queue.markAttempt(queueId)
        return try {
            when (val result = submitter.submit(entry.payload)) {
                is PriceObservationSubmitResult.Success -> queue.recordSuccess(queueId, result.response)
                is PriceObservationSubmitResult.Failure -> queue.recordFailure(queueId, result)
            }
            requireNotNull(queue.get(queueId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = PriceObservationSubmitResult.Failure(
                kind = PriceObservationFailureKind.NETWORK,
                message = error::class.java.simpleName,
            )
            queue.recordFailure(queueId, failure)
            requireNotNull(queue.get(queueId))
        }
    }

    suspend fun enqueueAndSubmit(
        storeId: String,
        observedOn: String,
        catalogProductId: String,
        unitPriceKrw: Int,
        localDocumentId: String? = null,
        localLineItemId: String? = null,
    ): PriceObservationQueueEntry = submit(
        queue.enqueue(
            storeId = storeId,
            observedOn = observedOn,
            catalogProductId = catalogProductId,
            unitPriceKrw = unitPriceKrw,
            localDocumentId = localDocumentId,
            localLineItemId = localLineItemId,
        ).queueId,
    )
}

class RoomPriceObservationQueueRepository internal constructor(
    private val dao: ReceiptSessionDao,
    private val keyGenerator: PriceObservationIdempotencyKeyGenerator = SecurePriceObservationIdempotencyKeyGenerator,
) : PriceObservationQueueRepository {
    companion object {
        fun create(context: Context): RoomPriceObservationQueueRepository =
            RoomPriceObservationQueueRepository(ReceiptDatabase.getInstance(context).receiptSessionDao())
    }

    override suspend fun enqueue(
        storeId: String,
        observedOn: String,
        catalogProductId: String,
        unitPriceKrw: Int,
        localDocumentId: String?,
        localLineItemId: String?,
        createdAt: String,
    ): PriceObservationQueueEntry {
        val payload = PriceObservationSubmitPayload(
            idempotencyKey = keyGenerator.generate(),
            storeId = storeId,
            observedOn = observedOn,
            catalogProductId = catalogProductId,
            unitPriceKrw = unitPriceKrw,
        )
        val entity = PriceObservationQueueEntity(
            queueId = UUID.randomUUID().toString(),
            localDocumentId = localDocumentId,
            localLineItemId = localLineItemId,
            idempotencyKey = payload.idempotencyKey,
            storeId = payload.storeId,
            observedOn = payload.observedOn,
            catalogProductId = payload.catalogProductId,
            unitPriceKrw = payload.unitPriceKrw,
            status = PriceObservationQueueStatus.PENDING.wireValue,
            attemptCount = 0,
            lastError = null,
            observationId = null,
            replayed = null,
            appliedAction = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        dao.upsertPriceObservationQueue(entity)
        return entity.toDomain()
    }

    override suspend fun get(queueId: String): PriceObservationQueueEntry? =
        dao.getPriceObservationQueue(queueId)?.toDomain()

    override suspend fun latestForLine(
        localDocumentId: String,
        localLineItemId: String,
    ): PriceObservationQueueEntry? = dao.latestPriceObservationForLine(
        localDocumentId = localDocumentId,
        localLineItemId = localLineItemId,
    )?.toDomain()

    override suspend fun markAttempt(queueId: String, updatedAt: String) {
        val current = requireNotNull(dao.getPriceObservationQueue(queueId))
        require(current.status != PriceObservationQueueStatus.SUCCEEDED.wireValue)
        dao.upsertPriceObservationQueue(
            current.copy(
                attemptCount = current.attemptCount + 1,
                updatedAt = updatedAt,
                lastError = null,
            ),
        )
    }

    override suspend fun recordSuccess(
        queueId: String,
        response: PriceObservationSubmitResponse,
        updatedAt: String,
    ) {
        val current = requireNotNull(dao.getPriceObservationQueue(queueId))
        dao.upsertPriceObservationQueue(
            current.copy(
                status = PriceObservationQueueStatus.SUCCEEDED.wireValue,
                lastError = null,
                observationId = response.observationId,
                replayed = response.replayed,
                appliedAction = response.appliedAction.wireValue,
                updatedAt = updatedAt,
            ),
        )
    }

    override suspend fun recordFailure(
        queueId: String,
        failure: PriceObservationSubmitResult.Failure,
        updatedAt: String,
    ) {
        val current = requireNotNull(dao.getPriceObservationQueue(queueId))
        dao.upsertPriceObservationQueue(
            current.copy(
                status = if (failure.retryable) {
                    PriceObservationQueueStatus.RETRYABLE_FAILURE.wireValue
                } else {
                    PriceObservationQueueStatus.NEEDS_REVIEW.wireValue
                },
                lastError = failure.message ?: failure.kind.name.lowercase(),
                updatedAt = updatedAt,
            ),
        )
    }
}

private fun PriceObservationQueueEntity.toDomain() = PriceObservationQueueEntry(
    queueId = queueId,
    localDocumentId = localDocumentId,
    localLineItemId = localLineItemId,
    payload = PriceObservationSubmitPayload(
        idempotencyKey = idempotencyKey,
        storeId = storeId,
        observedOn = observedOn,
        catalogProductId = catalogProductId,
        unitPriceKrw = unitPriceKrw,
    ),
    status = PriceObservationQueueStatus.entries.first { it.wireValue == status },
    attemptCount = attemptCount,
    lastError = lastError,
    observationId = observationId,
    replayed = replayed,
    appliedAction = appliedAction?.let { value ->
        PriceObservationAppliedAction.entries.first { it.wireValue == value }
    },
    createdAt = createdAt,
    updatedAt = updatedAt,
)
