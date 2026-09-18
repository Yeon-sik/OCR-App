package com.yeonsik.ingestion.desktop

import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

enum class DesktopBatchItemStatus {
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

data class DesktopBatchProjectionSummary(
    val projection: IngestionProjection,
    val status: ProjectionStatus,
    val error: String? = null,
)

data class DesktopBatchWorkItem(
    val itemId: String,
    val sourcePath: String,
    val sourceFileName: String,
    val status: DesktopBatchItemStatus = DesktopBatchItemStatus.QUEUED,
    val ingestionId: String? = null,
    val schema: String? = null,
    val mode: String? = null,
    val merchantOrPlatform: String? = null,
    val archiveStatus: DesktopEvidenceArchiveStatus? = null,
    val reviewStatus: IngestionReviewStatus? = null,
    val projectionSummary: List<DesktopBatchProjectionSummary> = emptyList(),
    val duplicateOfIngestionId: String? = null,
    val error: String? = null,
)

data class DesktopBatchSummary(
    val total: Int = 0,
    val queued: Int = 0,
    val reviewRequired: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val duplicate: Int = 0,
) {
    companion object {
        fun from(items: List<DesktopBatchWorkItem>): DesktopBatchSummary = DesktopBatchSummary(
            total = items.size,
            queued = items.count { it.status == DesktopBatchItemStatus.QUEUED },
            reviewRequired = items.count { it.status == DesktopBatchItemStatus.REVIEW_REQUIRED },
            completed = items.count { it.status == DesktopBatchItemStatus.COMPLETED },
            failed = items.count { it.status == DesktopBatchItemStatus.FAILED },
            duplicate = items.count { it.status == DesktopBatchItemStatus.DUPLICATE },
        )
    }
}

data class DesktopBatchState(
    val items: List<DesktopBatchWorkItem> = emptyList(),
    val busy: Boolean = false,
) {
    val summary: DesktopBatchSummary
        get() = DesktopBatchSummary.from(items)
}

data class DesktopBundleImportResult(
    val state: DesktopUiState,
    val duplicateOfIngestionId: String? = null,
)

/** Import phases exposed only to the Desktop batch UI; Core remains single-ingestion. */
enum class DesktopBundleImportPhase { IMPORTING, ARCHIVING }

/** Sequentially coordinates independent Desktop imports without introducing a batch domain contract. */
class DesktopBatchCoordinator(
    private val controller: DesktopIngestionController,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(DesktopBatchState())
    private var activeItemId: String? = null
    val state: StateFlow<DesktopBatchState> = _state.asStateFlow()

    suspend fun importBundles(paths: List<Path>) = mutex.withLock {
        if (paths.isEmpty()) return@withLock
        activeItemId = null
        val normalized = paths.map(Path::toAbsolutePath).map(Path::normalize)
        val existingIds = _state.value.items.map(DesktopBatchWorkItem::itemId).toSet()
        val newItems = normalized.mapIndexed { index, path ->
            val itemId = uniqueItemId(index, path, existingIds)
            DesktopBatchWorkItem(
                itemId = itemId,
                sourcePath = path.toString(),
                sourceFileName = path.fileName?.toString().orEmpty().ifBlank { path.toString() },
            )
        }
        var items = _state.value.items + newItems
        publish(items, busy = true)

        newItems.forEach { queued ->
            val index = items.indexOfFirst { it.itemId == queued.itemId }
            if (index < 0) return@forEach
            items = items.replace(index, items[index].copy(status = DesktopBatchItemStatus.IMPORTING, error = null))
            publish(items, busy = true)
            val result = runCatching {
                controller.importBundle(Path.of(queued.sourcePath)) { phase ->
                    val nextStatus = when (phase) {
                        DesktopBundleImportPhase.IMPORTING -> DesktopBatchItemStatus.IMPORTING
                        DesktopBundleImportPhase.ARCHIVING -> DesktopBatchItemStatus.ARCHIVING
                    }
                    val currentIndex = items.indexOfFirst { it.itemId == queued.itemId }
                    if (currentIndex >= 0) {
                        items = items.replace(currentIndex, items[currentIndex].copy(status = nextStatus, error = null))
                        publish(items, busy = true)
                    }
                }
            }
            val currentIndex = items.indexOfFirst { it.itemId == queued.itemId }
            if (currentIndex >= 0) {
                val completed = result.fold(
                    onSuccess = { import -> fromImportResult(items[currentIndex], import) },
                    onFailure = { error ->
                        items[currentIndex].copy(
                            status = DesktopBatchItemStatus.FAILED,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
                items = items.replace(currentIndex, completed)
                publish(items, busy = true)
            }
        }
        publish(items, busy = false)
    }

    suspend fun openItem(itemId: String) = mutex.withLock {
        val item = _state.value.items.firstOrNull { it.itemId == itemId } ?: return@withLock
        val ingestionId = item.ingestionId ?: return@withLock
        activeItemId = itemId
        controller.load(ingestionId)
        refreshItem(itemId, controller.state.value)
    }

    /** Synchronizes the selected batch row after an existing single-item action completes. */
    suspend fun syncActiveItem() = mutex.withLock {
        activeItemId?.let { refreshItem(it, controller.state.value) }
    }

    /** Retries one failed import/archive/projection path; duplicate items never resubmit. */
    suspend fun retryItem(itemId: String, selectedProjections: Set<IngestionProjection> = emptySet()) = mutex.withLock {
        val item = _state.value.items.firstOrNull { it.itemId == itemId } ?: return@withLock
        if (item.status == DesktopBatchItemStatus.DUPLICATE) return@withLock
        activeItemId = itemId
        var items = _state.value.items
        val index = items.indexOfFirst { it.itemId == itemId }
        if (index < 0) return@withLock

        if (item.ingestionId == null) {
            items = items.replace(index, item.copy(status = DesktopBatchItemStatus.IMPORTING, error = null))
            publish(items, busy = true)
            val result = runCatching {
                controller.importBundle(Path.of(item.sourcePath)) { phase ->
                    val nextStatus = if (phase == DesktopBundleImportPhase.ARCHIVING) {
                        DesktopBatchItemStatus.ARCHIVING
                    } else {
                        DesktopBatchItemStatus.IMPORTING
                    }
                    val currentIndex = items.indexOfFirst { it.itemId == itemId }
                    if (currentIndex >= 0) {
                        items = items.replace(currentIndex, items[currentIndex].copy(status = nextStatus, error = null))
                        publish(items, busy = true)
                    }
                }
            }
            val currentIndex = items.indexOfFirst { it.itemId == itemId }
            if (currentIndex >= 0) {
                items = items.replace(
                    currentIndex,
                    result.fold(
                        onSuccess = { import -> fromImportResult(items[currentIndex], import) },
                        onFailure = { error ->
                            items[currentIndex].copy(
                                status = DesktopBatchItemStatus.FAILED,
                                error = error.message ?: error.javaClass.simpleName,
                            )
                        },
                    ),
                )
            }
            publish(items, busy = false)
            return@withLock
        }

        controller.load(item.ingestionId)
        var currentState = controller.state.value
        val archiveFailed = currentState.bundleMetadata?.archiveStatus == DesktopEvidenceArchiveStatus.FAILED
        val revisionFailed = currentState.bundleMetadata?.pendingRevision != null
        val verified = currentState.session?.let { session ->
            session.verifiedCanonicalFingerprint == session.canonicalFingerprint
        } == true
        when {
            archiveFailed -> {
                items = items.replace(index, item.copy(status = DesktopBatchItemStatus.ARCHIVING, error = null))
                publish(items, busy = true)
                controller.archiveEvidence()
                currentState = controller.state.value
            }
            revisionFailed -> {
                items = items.replace(index, item.copy(status = DesktopBatchItemStatus.REVIEW_REQUIRED, error = null))
                publish(items, busy = true)
                controller.retryCanonicalRevision()
                currentState = controller.state.value
            }
            verified -> {
                items = items.replace(index, item.copy(status = DesktopBatchItemStatus.SUBMITTING, error = null))
                publish(items, busy = true)
                controller.retry(selectedProjections)
                currentState = controller.state.value
            }
            else -> {
                items = items.replace(
                    index,
                    item.copy(error = "검수 완료 또는 보관 실패 상태의 항목만 재시도할 수 있습니다."),
                )
                publish(items, busy = false)
                return@withLock
            }
        }
        refreshItem(itemId, currentState)
    }

    private fun refreshItem(itemId: String, state: DesktopUiState) {
        val currentItems = _state.value.items
        val index = currentItems.indexOfFirst { it.itemId == itemId }
        if (index < 0) return
        val current = currentItems[index]
        publish(currentItems.replace(index, fromUiState(current, state)), busy = false)
    }

    private fun fromImportResult(
        item: DesktopBatchWorkItem,
        result: DesktopBundleImportResult,
    ): DesktopBatchWorkItem = fromUiState(
        item = item,
        state = result.state,
        duplicateOfIngestionId = result.duplicateOfIngestionId,
    )

    private fun fromUiState(
        item: DesktopBatchWorkItem,
        state: DesktopUiState,
        duplicateOfIngestionId: String? = item.duplicateOfIngestionId,
    ): DesktopBatchWorkItem {
        val session = state.session
        val activeProjections = session?.projections.orEmpty().filterNot {
            it.status == ProjectionStatus.DISABLED
        }
        val archiveStatus = state.bundleMetadata?.archiveStatus
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
            duplicateOfIngestionId != null -> DesktopBatchItemStatus.DUPLICATE
            session == null -> DesktopBatchItemStatus.FAILED
            archiveStatus == DesktopEvidenceArchiveStatus.FAILED -> DesktopBatchItemStatus.FAILED
            state.bundleMetadata?.pendingRevision != null -> DesktopBatchItemStatus.FAILED
            allUploaded -> DesktopBatchItemStatus.COMPLETED
            projectionFailure -> DesktopBatchItemStatus.FAILED
            verified && (state.bundleMetadata == null ||
                (archiveStatus == DesktopEvidenceArchiveStatus.ARCHIVED &&
                    state.bundleMetadata.verificationEventRecorded)) -> DesktopBatchItemStatus.READY_TO_SUBMIT
            else -> DesktopBatchItemStatus.REVIEW_REQUIRED
        }
        return item.copy(
            status = status,
            ingestionId = session?.ingestionId ?: item.ingestionId,
            schema = state.schema,
            mode = state.envelope?.mode?.wireValue,
            merchantOrPlatform = state.envelope?.displayMerchantOrPlatform(),
            archiveStatus = archiveStatus,
            reviewStatus = session?.reviewStatus,
            projectionSummary = activeProjections.map {
                DesktopBatchProjectionSummary(it.projection, it.status, it.lastError)
            },
            duplicateOfIngestionId = duplicateOfIngestionId,
            error = state.error ?: state.bundleMetadata?.archiveError ?: state.bundleMetadata?.revisionArchiveError ?: activeProjections
                .firstOrNull { it.lastError != null }
                ?.lastError,
        )
    }

    private fun publish(items: List<DesktopBatchWorkItem>, busy: Boolean) {
        _state.value = DesktopBatchState(items = items, busy = busy)
    }

    private fun uniqueItemId(index: Int, path: Path, existingIds: Set<String>): String {
        val base = "desktop-batch-$index-${path.toString().hashCode().toUInt().toString(16)}"
        if (base !in existingIds) return base
        var suffix = 2
        while ("$base-$suffix" in existingIds) suffix += 1
        return "$base-$suffix"
    }

    private fun List<DesktopBatchWorkItem>.replace(index: Int, value: DesktopBatchWorkItem): List<DesktopBatchWorkItem> =
        toMutableList().also { it[index] = value }
}

private fun com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope.displayMerchantOrPlatform(): String? =
    receipt?.merchant?.name?.takeIf(String::isNotBlank)
        ?: merchantCandidate?.name?.takeIf(String::isNotBlank)
        ?: purchaseRecords.map { it.platform }.distinct().joinToString(", ").takeIf(String::isNotBlank)
