package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.correction.ReceiptCorrectionCandidate
import com.pricetrace.receiptscanner.correction.ReceiptCorrectionPolicy
import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.QuantityUnit
import com.pricetrace.receiptscanner.domain.ReceiptIdentifier
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.ReceiptStatus
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.requireLocalDocumentId
import com.pricetrace.receiptscanner.domain.ReceiptValidator
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.ReceiptV2Payment
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import com.pricetrace.receiptscanner.domain.purchaseLocalTime
import com.pricetrace.receiptscanner.domain.userEnteredSourceReference
import com.pricetrace.receiptscanner.domain.withPurchaseLocalTime
import com.pricetrace.receiptscanner.export.ReceiptV2Json
import com.pricetrace.receiptscanner.parser.AmountParser
import com.pricetrace.receiptscanner.storage.ReviewEdit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime

data class ReceiptReviewState(
    val receipt: ReceiptV2,
    val edits: List<ReviewEdit> = emptyList(),
    val reconciliationReason: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** Pure review state holder. Failed JSON imports never mutate [state]. */
class ReceiptReviewController(
    initialReceipt: ReceiptV2,
    initialEdits: List<ReviewEdit> = emptyList(),
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    private val mutableState = MutableStateFlow(
        ReceiptReviewState(
            receipt = initialReceipt,
            edits = initialEdits,
            reconciliationReason = initialEdits
                .lastOrNull { it.fieldPath == RECONCILIATION_REASON_PATH }
                ?.newValue,
        ),
    )
    val state: StateFlow<ReceiptReviewState> = mutableState.asStateFlow()

    /**
     * Undo restores an earlier draft but never removes a history row: reverting appends a compensating
     * edit instead, so what the reviewer did stays auditable and already persisted rows never diverge
     * from what the controller reports.
     */
    private data class ReviewCheckpoint(
        val receipt: ReceiptV2,
        val reconciliationReason: String?,
        val fieldPath: String,
        val previousValue: String?,
        val newValue: String?,
    )

    private val undoStack = ArrayDeque<ReviewCheckpoint>()
    private val redoStack = ArrayDeque<ReviewCheckpoint>()

    /** Keeps every history row unique even when several edits land within one clock reading. */
    private var editSequence: Int = initialEdits.size

    fun replaceFromJson(json: String): Result<ReceiptV2> {
        val decoded = runCatching { ReceiptV2Json.decode(json) }
        decoded.onSuccess { receipt ->
            undoStack.clear()
            redoStack.clear()
            mutableState.value = ReceiptReviewState(receipt)
        }
        return decoded
    }

    fun undo(): Boolean {
        val checkpoint = undoStack.removeLastOrNull() ?: return false
        val current = mutableState.value
        redoStack.addLast(
            ReviewCheckpoint(
                receipt = current.receipt,
                reconciliationReason = current.reconciliationReason,
                fieldPath = checkpoint.fieldPath,
                previousValue = checkpoint.previousValue,
                newValue = checkpoint.newValue,
            ),
        )
        publish(
            receipt = checkpoint.receipt,
            reconciliationReason = checkpoint.reconciliationReason,
            fieldPath = checkpoint.fieldPath,
            previous = checkpoint.newValue,
            next = checkpoint.previousValue,
            provenanceJson = UNDO_PROVENANCE,
        )
        return true
    }

    fun redo(): Boolean {
        val checkpoint = redoStack.removeLastOrNull() ?: return false
        val current = mutableState.value
        undoStack.addLast(
            ReviewCheckpoint(
                receipt = current.receipt,
                reconciliationReason = current.reconciliationReason,
                fieldPath = checkpoint.fieldPath,
                previousValue = checkpoint.previousValue,
                newValue = checkpoint.newValue,
            ),
        )
        publish(
            receipt = checkpoint.receipt,
            reconciliationReason = checkpoint.reconciliationReason,
            fieldPath = checkpoint.fieldPath,
            previous = checkpoint.previousValue,
            next = checkpoint.newValue,
            provenanceJson = REDO_PROVENANCE,
        )
        return true
    }

    /**
     * Adds a row the reviewer read on paper but OCR never produced. It carries a user transcription
     * marker rather than an OCR reference, so the export never presents it as recognized text.
     */
    fun addLineItem(
        afterLineItemId: String? = null,
        type: ReceiptLineType = ReceiptLineType.PRODUCT,
        netAmountMinor: Long? = null,
    ): String {
        val current = mutableState.value
        val lineItemId = StableIds.userEnteredLineId(current.receipt.document.requireLocalDocumentId(), now(), editSequence)
        val tracksGross = type == ReceiptLineType.PRODUCT || type == ReceiptLineType.SERVICE
        val item = ReceiptV2LineItem(
            id = lineItemId,
            type = type,
            description = null,
            sourceLineReferences = listOf(userEnteredSourceReference(lineItemId)),
            identifiers = emptyList(),
            quantity = null,
            unitPriceAmountMinor = null,
            grossAmountMinor = netAmountMinor.takeIf { tracksGross },
            discountAmountMinor = null,
            taxAmountMinor = null,
            netAmountMinor = netAmountMinor,
            confidence = ConfidenceLevel.LOW,
            taxRatePercent = null,
        )
        val insertAt = current.receipt.lineItems
            .indexOfFirst { it.id == afterLineItemId }
            .let { index -> if (index < 0) current.receipt.lineItems.size else index + 1 }
        val lineItems = current.receipt.lineItems.toMutableList().apply { add(insertAt, item) }
        commit(
            receipt = current.receipt.copy(lineItems = lineItems),
            fieldPath = "line_items[$lineItemId]",
            previous = null,
            next = ReceiptV2Json.encodeCanonicalLineItem(item),
        )
        return lineItemId
    }

    /** Removes a row OCR invented. The full row is kept in history so the deletion stays recoverable. */
    fun removeLineItem(lineItemId: String): Boolean {
        val current = mutableState.value
        val index = current.receipt.lineItems.indexOfFirst { it.id == lineItemId }
        if (index < 0) return false
        val removed = current.receipt.lineItems[index]
        val lineItems = current.receipt.lineItems.toMutableList().apply { removeAt(index) }
        commit(
            receipt = current.receipt.copy(lineItems = lineItems),
            fieldPath = "line_items[$lineItemId]",
            previous = ReceiptV2Json.encodeCanonicalLineItem(removed),
            next = null,
        )
        return true
    }

    fun updateMerchantName(value: String?) = updateEditable(
        fieldPath = "merchant.name",
        previous = mutableState.value.receipt.merchant.name,
        value = value,
    ) { receipt, next -> receipt.copy(merchant = receipt.merchant.copy(name = next)) }

    fun updateBranchName(value: String?) = updateEditable(
        fieldPath = "merchant.branch_name",
        previous = mutableState.value.receipt.merchant.branchName,
        value = value,
    ) { receipt, next -> receipt.copy(merchant = receipt.merchant.copy(branchName = next)) }

    fun updateBusinessRegistrationNumber(value: String?) = updateEditable(
        fieldPath = "merchant.business_registration_number",
        previous = mutableState.value.receipt.merchant.businessRegistrationNumber,
        value = value,
    ) { receipt, next -> receipt.copy(merchant = receipt.merchant.copy(businessRegistrationNumber = next)) }

    fun updateAddress(value: String?) = updateEditable(
        fieldPath = "merchant.address",
        previous = mutableState.value.receipt.merchant.address,
        value = value,
    ) { receipt, next -> receipt.copy(merchant = receipt.merchant.copy(address = next)) }

    fun updatePhone(value: String?) = updateEditable(
        fieldPath = "merchant.phone",
        previous = mutableState.value.receipt.merchant.phone,
        value = value,
    ) { receipt, next -> receipt.copy(merchant = receipt.merchant.copy(phone = next)) }

    fun updateOriginalDocumentId(value: String?) = updateEditable(
        fieldPath = "document.source.original_document_id",
        previous = mutableState.value.receipt.document.source.originalDocumentId,
        value = value,
    ) { receipt, next ->
        receipt.copy(document = receipt.document.copy(source = receipt.document.source.copy(originalDocumentId = next)))
    }

    fun updateIssuedOn(value: String?) = update(
        fieldPath = "document.issued_on",
        previous = mutableState.value.receipt.document.issuedOn,
        next = value,
    ) { receipt -> receipt.copy(document = receipt.document.copy(issuedOn = value.cleaned())) }

    fun updateIssuedAt(value: String?) = update(
        fieldPath = "document.issued_at",
        previous = mutableState.value.receipt.document.issuedAt,
        next = value,
    ) { receipt -> receipt.copy(document = receipt.document.copy(issuedAt = value.cleaned())) }

    fun updateIssuedLocalTime(value: String?): Boolean {
        val cleaned = value.cleaned()
        val normalized = mutableState.value.receipt.document.source
            .withPurchaseLocalTime(cleaned)
            .purchaseLocalTime()
        if (cleaned != null && normalized == null) return false
        return update(
            fieldPath = "document.source.notes.purchase_local_time",
            previous = mutableState.value.receipt.document.source.purchaseLocalTime(),
            next = normalized,
        ) { receipt ->
            receipt.copy(
                document = receipt.document.copy(
                    source = receipt.document.source.withPurchaseLocalTime(normalized),
                ),
            )
        }
    }

    fun updateCurrency(value: String?) = update(
        fieldPath = "document.currency",
        previous = mutableState.value.receipt.document.currency,
        next = value,
    ) { receipt -> receipt.copy(document = receipt.document.copy(currency = value.cleaned()?.uppercase())) }

    fun updateSubtotal(value: String?): Boolean = updateTotalAmount(
        field = "subtotal_amount_minor",
        value = value,
        previous = { it.totals.subtotalAmountMinor },
        transform = { receipt, amount -> receipt.copy(totals = receipt.totals.copy(itemsGrossAmountMinor = amount)) },
    )

    fun updateDiscountTotal(value: String?): Boolean = updateTotalAmount(
        field = "discount_amount_minor",
        value = value,
        previous = { it.totals.discountAmountMinor },
        transform = { receipt, amount -> receipt.copy(totals = receipt.totals.copy(discountAmountMinor = amount)) },
    )

    fun updateTaxTotal(value: String?): Boolean = updateTotalAmount(
        field = "tax_amount_minor",
        value = value,
        previous = { it.totals.taxAmountMinor },
        transform = { receipt, amount -> receipt.copy(totals = receipt.totals.copy(taxAmountMinor = amount)) },
    )

    fun updateFeeTotal(value: String?): Boolean = updateTotalAmount(
        field = "fee_amount_minor",
        value = value,
        previous = { it.totals.feeAmountMinor },
        transform = { receipt, amount -> receipt.copy(totals = receipt.totals.copy(feeAmountMinor = amount)) },
    )

    fun updateGrandTotal(value: String?): Boolean = updateTotalAmount(
        field = "grand_total_amount_minor",
        value = value,
        previous = { it.totals.grandTotalAmountMinor },
        transform = { receipt, amount -> receipt.copy(totals = receipt.totals.copy(grandTotalAmountMinor = amount)) },
    )

    private fun updateTotalAmount(
        field: String,
        value: String?,
        previous: (ReceiptV2) -> Long?,
        transform: (ReceiptV2, Long?) -> ReceiptV2,
    ): Boolean {
        val parsed = value.cleaned()?.let(AmountParser::normalizeMinor)
        if (value.cleaned() != null && parsed == null) return false
        return update(
            fieldPath = "totals.$field",
            previous = previous(mutableState.value.receipt)?.toString(),
            next = parsed?.toString(),
        ) { receipt -> transform(receipt, parsed) }
    }

    fun updatePaymentMethod(index: Int, value: String?): Boolean = updatePayment(
        index = index,
        field = "method",
        previous = { it.method },
        next = value.editableText(),
        transform = { payment -> payment.copy(method = value.editableText()?.takeIf(String::isNotBlank) ?: "unknown") },
    )

    fun updatePaymentAmount(index: Int, value: String?): Boolean {
        val cleaned = value.cleaned()
        val amount = cleaned?.let(AmountParser::normalizeMinor)
        if (cleaned != null && amount == null) return false
        return updatePayment(
            index = index,
            field = "amount_minor",
            previous = { it.amountMinor?.toString() },
            next = amount?.toString(),
            transform = { payment -> payment.copy(amountMinor = amount) },
        )
    }

    fun updateLineDescription(lineId: String, value: String?): Boolean = updateLine(
        lineId = lineId,
        field = "description",
        nextValue = value.editableText(),
        previous = { it.description },
        transform = { item -> item.copy(description = value.editableText(), confidence = ConfidenceLevel.USER_VERIFIED) },
    )

    fun updateLineType(lineId: String, value: ReceiptLineType): Boolean = updateLine(
        lineId = lineId,
        field = "type",
        nextValue = value.wireValue,
        previous = { it.type.wireValue },
        transform = { item -> item.copy(type = value, confidence = ConfidenceLevel.USER_VERIFIED) },
    )

    fun updateMerchantSku(lineId: String, value: String?): Boolean = updateLine(
        lineId = lineId,
        field = "identifiers.merchant_sku",
        nextValue = value.editableText(),
        previous = { item -> item.identifiers.firstOrNull { it.scheme == "merchant_sku" }?.value },
        transform = { item ->
            val retained = item.identifiers.filterNot { it.scheme == "merchant_sku" }
            item.copy(
                identifiers = retained + listOfNotNull(
                    value.editableText()?.let { ReceiptIdentifier("merchant_sku", it) },
                ),
                confidence = ConfidenceLevel.USER_VERIFIED,
            )
        },
    )

    fun updateLineQuantity(lineId: String, value: String?, unit: QuantityUnit = QuantityUnit.EACH): Boolean {
        val cleaned = value.cleaned()
        if (cleaned != null && cleaned.toBigDecimalOrNull() == null) return false
        return updateLine(
            lineId = lineId,
            field = "quantity",
            nextValue = cleaned,
            previous = { it.quantity?.value },
            transform = { item ->
                item.copy(
                    quantity = cleaned?.let { ReceiptQuantity(it, unit) },
                    confidence = ConfidenceLevel.USER_VERIFIED,
                )
            },
        )
    }

    fun updateLineUnitPrice(lineId: String, value: String?): Boolean = updateLineAmount(
        lineId,
        "unit_price_amount_minor",
        value,
        previous = { it.unitPriceAmountMinor },
        transform = { item, amount -> item.copy(unitPriceAmountMinor = amount) },
    )

    fun updateLineNetAmount(lineId: String, value: String?): Boolean = updateLineAmount(
        lineId,
        "net_amount_minor",
        value,
        previous = { it.netAmountMinor },
        transform = { item, amount ->
            val simpleGrossTracksNet =
                (item.type == ReceiptLineType.PRODUCT || item.type == ReceiptLineType.SERVICE) &&
                    item.grossAmountMinor == item.netAmountMinor
            item.copy(
                netAmountMinor = amount,
                grossAmountMinor = if (simpleGrossTracksNet) amount else item.grossAmountMinor,
            )
        },
    )

    fun setReconciliationReason(value: String?) {
        val current = mutableState.value
        val next = value.editableText()
        if (current.reconciliationReason == next) return
        commit(
            receipt = current.receipt,
            reconciliationReason = next,
            fieldPath = RECONCILIATION_REASON_PATH,
            previous = current.reconciliationReason,
            next = next,
        )
    }

    /**
     * Applies a previously validated AI candidate as an auditable draft edit. The value remains low
     * confidence until the reviewer compares it with the preserved image and finishes normal review.
     */
    fun applyCorrectionSuggestion(candidate: ReceiptCorrectionCandidate): Boolean {
        val current = mutableState.value
        val merchantField = ReceiptCorrectionPolicy.parseMerchantFieldPath(candidate.fieldPath)
        if (merchantField != null) return applyMerchantCorrection(current, candidate, merchantField)
        val (lineItemId, field) = ReceiptCorrectionPolicy.parseLineFieldPath(candidate.fieldPath) ?: return false
        if (ReceiptCorrectionPolicy.currentValue(current.receipt, candidate.fieldPath).cleaned() != candidate.oldValue.cleaned()) {
            return false
        }
        val index = current.receipt.lineItems.indexOfFirst { it.id == lineItemId }
        if (index < 0) return false
        val original = current.receipt.lineItems[index]
        val proposed = candidate.proposedValue.cleaned() ?: return false
        val updated = when (field) {
            "description" -> original.copy(description = proposed, confidence = ConfidenceLevel.LOW)
            "quantity" -> {
                if (proposed.toBigDecimalOrNull() == null) return false
                original.copy(
                    quantity = ReceiptQuantity(proposed, original.quantity?.unit ?: QuantityUnit.EACH),
                    confidence = ConfidenceLevel.LOW,
                )
            }
            "unit_price_amount_minor" -> {
                val amount = AmountParser.normalizeMinor(proposed) ?: return false
                original.copy(unitPriceAmountMinor = amount, confidence = ConfidenceLevel.LOW)
            }
            "net_amount_minor" -> {
                val amount = AmountParser.normalizeMinor(proposed) ?: return false
                val simpleGrossTracksNet =
                    (original.type == ReceiptLineType.PRODUCT || original.type == ReceiptLineType.SERVICE) &&
                        original.grossAmountMinor == original.netAmountMinor
                original.copy(
                    netAmountMinor = amount,
                    grossAmountMinor = if (simpleGrossTracksNet) amount else original.grossAmountMinor,
                    confidence = ConfidenceLevel.LOW,
                )
            }
            else -> return false
        }
        val items = current.receipt.lineItems.toMutableList().apply { set(index, updated) }
        commit(
            receipt = current.receipt.copy(lineItems = items),
            fieldPath = candidate.fieldPath,
            previous = candidate.oldValue.cleaned(),
            next = proposed,
            provenanceJson = buildJsonObject {
                put("user_modified", true)
                put("ai_suggestion_accepted", true)
                put("provider", candidate.providerId)
                put("model", candidate.model)
                put("prompt_version", candidate.promptVersion)
                put("source_line_ids", JsonArray(candidate.sourceLineIds.map(::JsonPrimitive)))
            }.toString(),
        )
        return true
    }

    private fun applyMerchantCorrection(
        current: ReceiptReviewState,
        candidate: ReceiptCorrectionCandidate,
        field: String,
    ): Boolean {
        if (ReceiptCorrectionPolicy.currentValue(current.receipt, candidate.fieldPath).cleaned() != candidate.oldValue.cleaned()) {
            return false
        }
        val proposed = candidate.proposedValue.cleaned() ?: return false
        val updatedMerchant = when (field) {
            "name" -> current.receipt.merchant.copy(name = proposed)
            "branch_name" -> current.receipt.merchant.copy(branchName = proposed)
            "business_registration_number" ->
                current.receipt.merchant.copy(businessRegistrationNumber = proposed)
            "address" -> current.receipt.merchant.copy(address = proposed)
            "phone" -> current.receipt.merchant.copy(phone = proposed)
            else -> return false
        }
        commit(
            receipt = current.receipt.copy(merchant = updatedMerchant),
            fieldPath = candidate.fieldPath,
            previous = candidate.oldValue.cleaned(),
            next = proposed,
            provenanceJson = buildJsonObject {
                put("user_modified", true)
                put("ai_suggestion_accepted", true)
                put("provider", candidate.providerId)
                put("model", candidate.model)
                put("prompt_version", candidate.promptVersion)
                put("source_line_ids", JsonArray(candidate.sourceLineIds.map(::JsonPrimitive)))
            }.toString(),
        )
        return true
    }

    fun markUserVerified(): Result<ReceiptV2> {
        val current = mutableState.value
        return ReceiptValidator.markUserVerified(current.receipt, current.reconciliationReason).onSuccess { receipt ->
            mutableState.value = current.copy(receipt = receipt)
        }
    }

    private fun updateLineAmount(
        lineId: String,
        field: String,
        value: String?,
        previous: (ReceiptV2LineItem) -> Long?,
        transform: (ReceiptV2LineItem, Long?) -> ReceiptV2LineItem,
    ): Boolean {
        val cleaned = value.cleaned()
        val parsed = cleaned?.let(AmountParser::normalizeMinor)
        if (cleaned != null && parsed == null) return false
        return updateLine(
            lineId = lineId,
            field = field,
            nextValue = parsed?.toString(),
            previous = { previous(it)?.toString() },
            transform = { item -> transform(item, parsed).copy(confidence = ConfidenceLevel.USER_VERIFIED) },
        )
    }

    private fun updateLine(
        lineId: String,
        field: String,
        nextValue: String?,
        previous: (ReceiptV2LineItem) -> String?,
        transform: (ReceiptV2LineItem) -> ReceiptV2LineItem,
    ): Boolean {
        val current = mutableState.value
        val index = current.receipt.lineItems.indexOfFirst { it.id == lineId }
        if (index < 0) return false
        val original = current.receipt.lineItems[index]
        val updatedItems = current.receipt.lineItems.toMutableList().apply { set(index, transform(original)) }
        commit(
            receipt = current.receipt.copy(lineItems = updatedItems),
            fieldPath = "line_items[$lineId].$field",
            previous = previous(original),
            next = nextValue,
        )
        return true
    }

    private fun updatePayment(
        index: Int,
        field: String,
        previous: (ReceiptV2Payment) -> String?,
        next: String?,
        transform: (ReceiptV2Payment) -> ReceiptV2Payment,
    ): Boolean {
        val current = mutableState.value
        val original = current.receipt.payments.getOrNull(index) ?: return false
        val payments = current.receipt.payments.toMutableList().apply { set(index, transform(original)) }
        commit(
            receipt = current.receipt.copy(payments = payments),
            fieldPath = "payments[$index].$field",
            previous = previous(original),
            next = next,
        )
        return true
    }

    private fun update(
        fieldPath: String,
        previous: String?,
        next: String?,
        transform: (ReceiptV2) -> ReceiptV2,
    ): Boolean {
        commit(transform(mutableState.value.receipt), fieldPath, previous, next.cleaned())
        return true
    }

    private fun updateEditable(
        fieldPath: String,
        previous: String?,
        value: String?,
        transform: (ReceiptV2, String?) -> ReceiptV2,
    ): Boolean {
        val next = value.editableText()
        commit(
            receipt = transform(mutableState.value.receipt, next),
            fieldPath = fieldPath,
            previous = previous,
            next = next,
        )
        return true
    }

    private fun commit(
        receipt: ReceiptV2,
        fieldPath: String,
        previous: String?,
        next: String?,
        reconciliationReason: String? = mutableState.value.reconciliationReason,
        provenanceJson: String = USER_MODIFIED_PROVENANCE,
    ) {
        val current = mutableState.value
        undoStack.addLast(
            ReviewCheckpoint(
                receipt = current.receipt,
                reconciliationReason = current.reconciliationReason,
                fieldPath = fieldPath,
                previousValue = previous,
                newValue = next,
            ),
        )
        while (undoStack.size > MAX_UNDO_DEPTH) undoStack.removeFirst()
        redoStack.clear()
        publish(receipt, reconciliationReason, fieldPath, previous, next, provenanceJson)
    }

    private fun publish(
        receipt: ReceiptV2,
        reconciliationReason: String?,
        fieldPath: String,
        previous: String?,
        next: String?,
        provenanceJson: String,
    ) {
        val current = mutableState.value
        val editedAt = now()
        val draftReceipt = receipt.asDraftAfterEdit()
        mutableState.value = current.copy(
            receipt = draftReceipt,
            reconciliationReason = reconciliationReason,
            edits = current.edits + ReviewEdit(
                id = StableIds.editId(draftReceipt.document.requireLocalDocumentId(), fieldPath, editedAt, editSequence++),
                documentId = draftReceipt.document.requireLocalDocumentId(),
                fieldPath = fieldPath,
                previousValue = previous,
                newValue = next,
                provenanceJson = provenanceJson,
                editedAt = editedAt,
            ),
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
        )
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    /** Keep spaces while the reviewer is still typing a description. */
    private fun String?.editableText(): String? = this?.takeIf(String::isNotBlank)

    private fun ReceiptV2.asDraftAfterEdit(): ReceiptV2 = copy(
        document = document.copy(
            status = ReceiptStatus.DRAFT,
            source = document.source.copy(transcriptionStatus = TranscriptionStatus.PARSED),
        ),
    )

    companion object {
        private const val RECONCILIATION_REASON_PATH = "review.reconciliation_reason"
        private const val USER_MODIFIED_PROVENANCE = "{\"user_modified\":true}"
        private const val UNDO_PROVENANCE = "{\"user_modified\":true,\"undo\":true}"
        private const val REDO_PROVENANCE = "{\"user_modified\":true,\"redo\":true}"
        private const val MAX_UNDO_DEPTH = 50
    }
}
