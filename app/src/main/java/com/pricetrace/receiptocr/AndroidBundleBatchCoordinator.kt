package com.pricetrace.receiptocr

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream

enum class AndroidBundleImportPhase { IMPORTING, ARCHIVING }

data class AndroidBundleBatchInput(
    val itemId: String,
    val sourceName: String,
    val sourceUri: String,
)

data class AndroidBundleBatchProjectionSummary(
    val projection: IngestionProjection,
    val status: ProjectionStatus,
    val error: String? = null,
)

data class AndroidBundleBatchItem(
    val itemId: String,
    val sourceName: String,
    val sourceUri: String? = null,
    val status: AndroidBundleBatchItemStatus = AndroidBundleBatchItemStatus.QUEUED,
    val ingestionId: String? = null,
    val schema: String? = null,
    val mode: String? = null,
    val merchantOrPlatform: String? = null,
    val archiveStatus: AndroidEvidenceArchiveStatus? = null,
    val reviewStatus: IngestionReviewStatus? = null,
    val projectionSummary: List<AndroidBundleBatchProjectionSummary> = emptyList(),
    val duplicateOfIngestionId: String? = null,
    val error: String? = null,
)

enum class AndroidBundleBatchItemStatus {
    QUEUED,
    IMPORTING,
    ARCHIVING,
    REVIEW_REQUIRED,
    READY_TO_SUBMIT,
    SUBMITTING,
    COMPLETED,
    FAILED,
    DUPLICATE,
}

data class AndroidBundleBatchSummary(
    val total: Int = 0,
    val queued: Int = 0,
    val reviewRequired: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val duplicate: Int = 0,
) {
    companion object {
        fun from(items: List<AndroidBundleBatchItem>): AndroidBundleBatchSummary = AndroidBundleBatchSummary(
            total = items.size,
            queued = items.count { it.status == AndroidBundleBatchItemStatus.QUEUED },
            reviewRequired = items.count { it.status == AndroidBundleBatchItemStatus.REVIEW_REQUIRED },
            completed = items.count { it.status == AndroidBundleBatchItemStatus.COMPLETED },
            failed = items.count { it.status == AndroidBundleBatchItemStatus.FAILED },
            duplicate = items.count { it.status == AndroidBundleBatchItemStatus.DUPLICATE },
        )
    }
}

data class AndroidBundleBatchState(
    val items: List<AndroidBundleBatchItem> = emptyList(),
    val busy: Boolean = false,
) {
    val summary: AndroidBundleBatchSummary
        get() = AndroidBundleBatchSummary.from(items)
}

data class AndroidBundleBatchResult(
    val state: AndroidCanonicalJsonValidatorState,
    val batchState: AndroidBundleBatchState,
)

/** Sequential Android orchestration over the existing single-bundle validator. */
class AndroidBundleBatchCoordinator(
    private val validator: AndroidCanonicalJsonValidator,
    private val openInput: suspend (AndroidBundleBatchInput) -> InputStream,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AndroidBundleBatchState())
    val state: StateFlow<AndroidBundleBatchState> = _state.asStateFlow()

    suspend fun importBundles(
        inputs: List<AndroidBundleBatchInput>,
        previous: AndroidCanonicalJsonValidatorState,
        existing: List<AndroidBundleBatchItem> = _state.value.items,
        onUpdate: (AndroidBundleBatchState) -> Unit = {},
    ): AndroidBundleBatchResult = mutex.withLock {
        if (inputs.isEmpty()) return@withLock AndroidBundleBatchResult(previous, AndroidBundleBatchState(existing))
        val inputIds = inputs.map(AndroidBundleBatchInput::itemId).toSet()
        var items = existing.filterNot { it.itemId in inputIds } + inputs.map {
            AndroidBundleBatchItem(
                itemId = it.itemId,
                sourceName = it.sourceName,
                sourceUri = it.sourceUri,
            )
        }
        fun publish(busy: Boolean) {
            val next = AndroidBundleBatchState(items, busy)
            _state.value = next
            onUpdate(next)
        }

        publish(busy = true)
        var active = previous
        inputs.forEach { input ->
            val index = items.indexOfFirst { it.itemId == input.itemId }
            if (index < 0) return@forEach
            items = items.replace(index, items[index].copy(status = AndroidBundleBatchItemStatus.IMPORTING, error = null))
            publish(busy = true)
            val result = runCatching {
                openInput(input).use { stream ->
                    validator.importBundle(
                        input = stream,
                        sourceName = input.sourceName,
                        previous = active,
                        onPhase = { phase ->
                            val phaseStatus = if (phase == AndroidBundleImportPhase.ARCHIVING) {
                                AndroidBundleBatchItemStatus.ARCHIVING
                            } else {
                                AndroidBundleBatchItemStatus.IMPORTING
                            }
                            val currentIndex = items.indexOfFirst { it.itemId == input.itemId }
                            if (currentIndex >= 0) {
                                items = items.replace(currentIndex, items[currentIndex].copy(status = phaseStatus, error = null))
                                publish(busy = true)
                            }
                        },
                    )
                }
            }
            val currentIndex = items.indexOfFirst { it.itemId == input.itemId }
            if (currentIndex >= 0) {
                val item = result.fold(
                    onSuccess = { state ->
                        active = state
                        fromState(items[currentIndex], state)
                    },
                    onFailure = { error ->
                        items[currentIndex].copy(
                            status = AndroidBundleBatchItemStatus.FAILED,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
                items = items.replace(currentIndex, item)
                publish(busy = true)
            }
        }
        publish(busy = false)
        AndroidBundleBatchResult(active, _state.value)
    }

    internal fun itemFromState(
        item: AndroidBundleBatchItem,
        state: AndroidCanonicalJsonValidatorState,
    ): AndroidBundleBatchItem = fromState(item, state)

    private fun fromState(
        item: AndroidBundleBatchItem,
        state: AndroidCanonicalJsonValidatorState,
    ): AndroidBundleBatchItem {
        val session = state.session
        val activeProjections = session?.projections.orEmpty().filterNot {
            it.status == ProjectionStatus.DISABLED
        }
        val verified = session?.let {
            it.verifiedCanonicalFingerprint == it.canonicalFingerprint
        } == true
        val allUploaded = activeProjections.isNotEmpty() && activeProjections.all {
            it.status == ProjectionStatus.UPLOADED
        }
        val projectionFailure = verified && activeProjections.any {
            it.status == ProjectionStatus.FAILED || it.status == ProjectionStatus.BLOCKED
        }
        val status = when {
            state.duplicateOfIngestionId != null -> AndroidBundleBatchItemStatus.DUPLICATE
            session == null -> AndroidBundleBatchItemStatus.FAILED
            state.bundle?.archiveStatus == AndroidEvidenceArchiveStatus.FAILED -> AndroidBundleBatchItemStatus.FAILED
            allUploaded -> AndroidBundleBatchItemStatus.COMPLETED
            projectionFailure -> AndroidBundleBatchItemStatus.FAILED
            verified && (state.bundle == null ||
                (state.bundle.archiveStatus == AndroidEvidenceArchiveStatus.ARCHIVED &&
                    state.bundle.verificationEventRecorded)) -> AndroidBundleBatchItemStatus.READY_TO_SUBMIT
            else -> AndroidBundleBatchItemStatus.REVIEW_REQUIRED
        }
        return item.copy(
            status = status,
            ingestionId = session?.ingestionId ?: item.ingestionId,
            schema = state.envelope?.schemaVersion,
            mode = state.envelope?.mode?.wireValue,
            merchantOrPlatform = state.envelope?.displayMerchantOrPlatform(),
            archiveStatus = state.bundle?.archiveStatus,
            reviewStatus = session?.reviewStatus,
            projectionSummary = activeProjections.map {
                AndroidBundleBatchProjectionSummary(it.projection, it.status, it.lastError)
            },
            duplicateOfIngestionId = state.duplicateOfIngestionId,
            error = state.error ?: state.bundle?.archiveError ?: activeProjections
                .firstOrNull { it.lastError != null }
                ?.lastError,
        )
    }

    private fun List<AndroidBundleBatchItem>.replace(index: Int, value: AndroidBundleBatchItem): List<AndroidBundleBatchItem> =
        toMutableList().also { it[index] = value }
}

internal fun AndroidBundleBatchItem.fromRecovery(
    recovery: AndroidBundleRecoveryState,
    envelope: YeonsikOcrEnvelope?,
    session: com.pricetrace.receiptscanner.ingestion.IngestionSession?,
): AndroidBundleBatchItem {
    val activeProjections = session?.projections.orEmpty().filterNot { it.status == ProjectionStatus.DISABLED }
    val verified = session?.let { it.verifiedCanonicalFingerprint == it.canonicalFingerprint } == true
    val allUploaded = activeProjections.isNotEmpty() && activeProjections.all { it.status == ProjectionStatus.UPLOADED }
    val projectionFailure = verified && activeProjections.any {
        it.status == ProjectionStatus.FAILED || it.status == ProjectionStatus.BLOCKED
    }
    val status = when {
        session == null -> AndroidBundleBatchItemStatus.FAILED
        recovery.bundle.archiveStatus == AndroidEvidenceArchiveStatus.ARCHIVING ||
            recovery.bundle.archiveStatus == AndroidEvidenceArchiveStatus.FAILED -> AndroidBundleBatchItemStatus.FAILED
        allUploaded -> AndroidBundleBatchItemStatus.COMPLETED
        projectionFailure -> AndroidBundleBatchItemStatus.FAILED
        verified && recovery.bundle.archiveStatus == AndroidEvidenceArchiveStatus.ARCHIVED &&
            recovery.bundle.verificationEventRecorded -> AndroidBundleBatchItemStatus.READY_TO_SUBMIT
        else -> AndroidBundleBatchItemStatus.REVIEW_REQUIRED
    }
    return AndroidBundleBatchItem(
        itemId = recovery.ingestionId,
        sourceName = recovery.bundle.sourceName,
        ingestionId = recovery.ingestionId,
        schema = envelope?.schemaVersion,
        mode = envelope?.mode?.wireValue,
        merchantOrPlatform = envelope?.displayMerchantOrPlatform(),
        status = status,
        archiveStatus = recovery.bundle.archiveStatus,
        reviewStatus = session?.reviewStatus,
        projectionSummary = activeProjections.map {
            AndroidBundleBatchProjectionSummary(it.projection, it.status, it.lastError)
        },
        error = recovery.bundle.archiveError ?: activeProjections.firstOrNull { it.lastError != null }?.lastError,
    )
}

private fun YeonsikOcrEnvelope.displayMerchantOrPlatform(): String? =
    receipt?.merchant?.name?.takeIf(String::isNotBlank)
        ?: merchantCandidate?.name?.takeIf(String::isNotBlank)
        ?: purchaseRecords.map { it.platform }.distinct().joinToString(", ").takeIf(String::isNotBlank)

internal fun AndroidBundleBatchState.withItemStatus(
    itemId: String,
    status: AndroidBundleBatchItemStatus,
): AndroidBundleBatchState = copy(
    items = items.map { item ->
        if (item.itemId == itemId) item.copy(status = status, error = null) else item
    },
)
