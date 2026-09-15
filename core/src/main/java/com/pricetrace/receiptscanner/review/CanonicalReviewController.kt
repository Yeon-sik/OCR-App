package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.domain.ReceiptBenefitKind
import com.pricetrace.receiptscanner.domain.ReceiptFoodService
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ingestion.CanonicalEnvelopeValidator
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlan
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.OffsetDateTime

/** Canonical, auditable edit record shared by Android and Desktop structured review. */
data class CanonicalReviewEdit(
    val id: String,
    val fieldPath: String,
    val previousValue: String?,
    val newValue: String?,
    val provenanceJson: String,
    val editedAt: String,
)

data class CanonicalReviewState(
    val envelope: YeonsikOcrEnvelope,
    val edits: List<CanonicalReviewEdit> = emptyList(),
    val canonicalFingerprint: String,
    val plan: CanonicalProjectionPlan,
    val validationIssues: List<String> = emptyList(),
    val error: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/**
 * Shared mutation API for structured .yeonsik review.
 *
 * It only changes canonical facts. Source attachment bytes and their binding are outside this
 * controller and are therefore never copied, regenerated, or edited here.
 */
class CanonicalReviewController(
    initialEnvelope: YeonsikOcrEnvelope,
    initialEdits: List<CanonicalReviewEdit> = emptyList(),
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    private val mutableState = MutableStateFlow(
        stateFor(initialEnvelope, initialEdits),
    )
    val state: StateFlow<CanonicalReviewState> = mutableState.asStateFlow()

    private data class Checkpoint(
        val envelope: YeonsikOcrEnvelope,
        val fieldPath: String,
        val previousValue: String?,
        val newValue: String?,
    )

    private val undoStack = ArrayDeque<Checkpoint>()
    private val redoStack = ArrayDeque<Checkpoint>()
    private var editSequence: Int = initialEdits.size

    fun updateMerchantName(value: String?): Boolean = updateMerchant(
        fieldPath = "receipt.merchant.name",
        previous = { it.name },
        value = value,
        transform = { merchant, next -> merchant.copy(name = next) },
    )

    fun updateMerchantBranchName(value: String?): Boolean = updateMerchant(
        fieldPath = "receipt.merchant.branch_name",
        previous = { it.branchName },
        value = value,
        transform = { merchant, next -> merchant.copy(branchName = next) },
    )

    fun updateMerchantAddress(value: String?): Boolean = updateMerchant(
        fieldPath = "receipt.merchant.address",
        previous = { it.address },
        value = value,
        transform = { merchant, next -> merchant.copy(address = next) },
    )

    fun updateMerchantPhone(value: String?): Boolean = updateMerchant(
        fieldPath = "receipt.merchant.phone",
        previous = { it.phone },
        value = value,
        transform = { merchant, next -> merchant.copy(phone = next) },
    )

    fun updateMerchantBusinessRegistrationNumber(value: String?): Boolean = updateMerchant(
        fieldPath = "receipt.merchant.business_registration_number",
        previous = { it.businessRegistrationNumber },
        value = value,
        transform = { merchant, next -> merchant.copy(businessRegistrationNumber = next) },
    )

    fun updateLineDescription(lineId: String, value: String?): Boolean = updateLine(
        lineId = lineId,
        field = "description",
        previous = { it.description },
        next = value.editableText(),
        transform = { item, _ -> item.copy(description = value.editableText(), confidence = ConfidenceLevel.USER_VERIFIED) },
    )

    fun updateLineType(lineId: String, value: ReceiptLineType): Boolean = updateLine(
        lineId = lineId,
        field = "type",
        previous = { it.type.wireValue },
        next = value.wireValue,
        transform = { item, _ ->
            if (item.foodService != null && value != ReceiptLineType.PRODUCT) {
                item
            } else {
                item.copy(type = value, confidence = ConfidenceLevel.USER_VERIFIED)
            }
        },
        reject = { item -> item.foodService != null && value != ReceiptLineType.PRODUCT },
    )

    /** Adds or changes only the food-service role; it never infers a benefit from an amount. */
    fun updateFoodServiceRole(lineId: String, value: FoodServiceRole): Boolean {
        val receipt = currentReceipt() ?: return fail("restaurant receipt is required")
        val line = receipt.lineItems.singleOrNull { it.id == lineId }
            ?: return fail("line_items[$lineId] was not found")
        if (receipt.merchant.businessKind != com.pricetrace.receiptscanner.domain.BusinessKind.FOOD_SERVICE ||
            line.type != ReceiptLineType.PRODUCT
        ) return fail("food_service role requires a restaurant product line")
        val previous = line.foodService
        val next = (previous ?: ReceiptFoodService(role = value)).copy(
            role = value,
            appliesToLineId = if (value == FoodServiceRole.OPTION) {
                previous?.appliesToLineId
            } else {
                null
            },
        )
        if (previous == next) return true
        return replaceLine(
            lineId = lineId,
            fieldPath = "receipt.line_items[$lineId].food_service.role",
            previousValue = previous?.role?.wireValue,
            newValue = value.wireValue,
            updated = line.copy(foodService = next, confidence = ConfidenceLevel.USER_VERIFIED),
        )
    }

    fun updateFoodServiceOptionParent(lineId: String, parentLineId: String?): Boolean {
        val receipt = currentReceipt() ?: return fail("restaurant receipt is required")
        val line = receipt.lineItems.singleOrNull { it.id == lineId }
            ?: return fail("line_items[$lineId] was not found")
        val foodService = line.foodService ?: return fail("food_service role is required before an option parent")
        if (foodService.role != FoodServiceRole.OPTION) return fail("only option lines may reference an option parent")
        val normalizedParent = parentLineId.editableText()
        if (normalizedParent != null) {
            val parent = receipt.lineItems.singleOrNull { it.id == normalizedParent }
            if (parent == null || parent.id == line.id || parent.foodService?.role != FoodServiceRole.MAIN) {
                return fail("option parent must reference another main restaurant line")
            }
        }
        if (foodService.appliesToLineId == normalizedParent) return true
        return replaceLine(
            lineId = lineId,
            fieldPath = "receipt.line_items[$lineId].food_service.applies_to_line_id",
            previousValue = foodService.appliesToLineId,
            newValue = normalizedParent,
            updated = line.copy(
                foodService = foodService.copy(appliesToLineId = normalizedParent),
                confidence = ConfidenceLevel.USER_VERIFIED,
            ),
        )
    }

    /**
     * Review-event checkbox semantics: checking creates only review_event; unchecking clears it
     * only when it was review_event. included/complimentary/promotion/other are preserved.
     */
    fun setRestaurantReviewEvent(lineId: String, checked: Boolean): Boolean {
        val receipt = currentReceipt() ?: return fail("restaurant receipt is required")
        val line = receipt.lineItems.singleOrNull { it.id == lineId }
            ?: return fail("line_items[$lineId] was not found")
        if (receipt.merchant.businessKind != com.pricetrace.receiptscanner.domain.BusinessKind.FOOD_SERVICE ||
            line.type != ReceiptLineType.PRODUCT
        ) return fail("review event requires a restaurant product line")
        val previousFoodService = line.foodService
        val previousBenefit = previousFoodService?.benefitKind
        val nextBenefit = when {
            checked -> ReceiptBenefitKind.REVIEW_EVENT
            previousBenefit == ReceiptBenefitKind.REVIEW_EVENT -> null
            else -> previousBenefit
        }
        if (nextBenefit == previousBenefit) return true
        val nextFoodService = (previousFoodService ?: ReceiptFoodService(FoodServiceRole.MAIN)).copy(
            benefitKind = nextBenefit,
        )
        return replaceLine(
            lineId = lineId,
            fieldPath = "receipt.line_items[$lineId].food_service.benefit_kind",
            previousValue = previousBenefit?.wireValue,
            newValue = nextBenefit?.wireValue,
            updated = line.copy(
                foodService = nextFoodService,
                confidence = ConfidenceLevel.USER_VERIFIED,
            ),
        )
    }

    fun undo(): Boolean {
        val checkpoint = undoStack.removeLastOrNull() ?: return false
        val current = mutableState.value
        redoStack.addLast(
            Checkpoint(
                envelope = current.envelope,
                fieldPath = checkpoint.fieldPath,
                previousValue = checkpoint.previousValue,
                newValue = checkpoint.newValue,
            ),
        )
        publish(
            envelope = checkpoint.envelope,
            fieldPath = checkpoint.fieldPath,
            previousValue = checkpoint.newValue,
            newValue = checkpoint.previousValue,
            provenanceJson = UNDO_PROVENANCE,
        )
        return true
    }

    fun redo(): Boolean {
        val checkpoint = redoStack.removeLastOrNull() ?: return false
        val current = mutableState.value
        undoStack.addLast(
            Checkpoint(
                envelope = current.envelope,
                fieldPath = checkpoint.fieldPath,
                previousValue = checkpoint.previousValue,
                newValue = checkpoint.newValue,
            ),
        )
        publish(
            envelope = checkpoint.envelope,
            fieldPath = checkpoint.fieldPath,
            previousValue = checkpoint.previousValue,
            newValue = checkpoint.newValue,
            provenanceJson = REDO_PROVENANCE,
        )
        return true
    }

    fun replaceFromEnvelope(envelope: YeonsikOcrEnvelope): Boolean {
        val issues = validate(envelope)
        if (issues.isNotEmpty()) return fail(issues.joinToString(", "))
        undoStack.clear()
        redoStack.clear()
        mutableState.value = stateFor(envelope, emptyList())
        return true
    }

    private fun updateMerchant(
        fieldPath: String,
        previous: (com.pricetrace.receiptscanner.domain.ReceiptMerchant) -> String?,
        value: String?,
        transform: (com.pricetrace.receiptscanner.domain.ReceiptMerchant, String?) -> com.pricetrace.receiptscanner.domain.ReceiptMerchant,
    ): Boolean {
        val envelope = mutableState.value.envelope
        val receipt = envelope.receipt ?: return fail("receipt is required")
        val nextValue = value.editableText()
        val nextMerchant = transform(receipt.merchant, nextValue)
        if (fieldPath.endsWith(".name") && envelope.merchantCandidate != null && nextMerchant.name.isNullOrBlank()) {
            return fail("merchant_candidate.name must remain a non-empty display fact")
        }
        val nextEnvelope = envelope.copy(
            receipt = receipt.copy(merchant = nextMerchant),
            merchantCandidate = envelope.merchantCandidate?.synchronizedWith(nextMerchant),
        )
        return commitEnvelope(
            envelope = nextEnvelope,
            fieldPath = fieldPath,
            previousValue = previous(receipt.merchant),
            newValue = nextValue,
        )
    }

    private fun updateLine(
        lineId: String,
        field: String,
        previous: (ReceiptV2LineItem) -> String?,
        next: String?,
        transform: (ReceiptV2LineItem, String?) -> ReceiptV2LineItem,
        reject: (ReceiptV2LineItem) -> Boolean = { false },
    ): Boolean {
        val receipt = currentReceipt() ?: return fail("receipt is required")
        val line = receipt.lineItems.singleOrNull { it.id == lineId }
            ?: return fail("line_items[$lineId] was not found")
        if (reject(line)) return fail("food_service requires a product line")
        return replaceLine(
            lineId = lineId,
            fieldPath = "receipt.line_items[$lineId].$field",
            previousValue = previous(line),
            newValue = next,
            updated = transform(line, next),
        )
    }

    private fun replaceLine(
        lineId: String,
        fieldPath: String,
        previousValue: String?,
        newValue: String?,
        updated: ReceiptV2LineItem,
    ): Boolean {
        val receipt = currentReceipt() ?: return fail("receipt is required")
        val lines = receipt.lineItems.map { if (it.id == lineId) updated else it }
        return commitEnvelope(
            envelope = mutableState.value.envelope.copy(receipt = receipt.copy(lineItems = lines)),
            fieldPath = fieldPath,
            previousValue = previousValue,
            newValue = newValue,
        )
    }

    private fun commitEnvelope(
        envelope: YeonsikOcrEnvelope,
        fieldPath: String,
        previousValue: String?,
        newValue: String?,
        provenanceJson: String = USER_MODIFIED_PROVENANCE,
    ): Boolean {
        val issues = validate(envelope)
        if (issues.isNotEmpty()) return fail(issues.joinToString(", "))
        val current = mutableState.value
        if (current.envelope == envelope) return true
        undoStack.addLast(Checkpoint(current.envelope, fieldPath, previousValue, newValue))
        while (undoStack.size > MAX_UNDO_DEPTH) undoStack.removeFirst()
        redoStack.clear()
        publish(envelope, fieldPath, previousValue, newValue, provenanceJson)
        return true
    }

    private fun publish(
        envelope: YeonsikOcrEnvelope,
        fieldPath: String,
        previousValue: String?,
        newValue: String?,
        provenanceJson: String,
    ) {
        val current = mutableState.value
        val editedAt = now()
        val documentId = envelope.receipt?.document?.localDocumentId
            ?: envelope.receipt?.document?.id
            ?: current.canonicalFingerprint
        mutableState.value = stateFor(
            envelope = envelope,
            edits = current.edits + CanonicalReviewEdit(
                id = StableIds.editId(documentId, fieldPath, editedAt, editSequence++),
                fieldPath = fieldPath,
                previousValue = previousValue,
                newValue = newValue,
                provenanceJson = provenanceJson,
                editedAt = editedAt,
            ),
        ).copy(error = null)
    }

    private fun currentReceipt(): ReceiptV2? = mutableState.value.envelope.receipt

    private fun fail(message: String): Boolean {
        mutableState.value = mutableState.value.copy(error = message)
        return false
    }

    private fun validate(envelope: YeonsikOcrEnvelope): List<String> =
        CanonicalEnvelopeValidator.validate(envelope)

    private fun stateFor(
        envelope: YeonsikOcrEnvelope,
        edits: List<CanonicalReviewEdit>,
    ): CanonicalReviewState {
        require(envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA) {
            "structured restaurant review requires yeonsik-ocr.v2"
        }
        val fingerprint = StableIds.sha256("ingestion|${YeonsikOcrEnvelopeCodec.canonicalize(envelope)}")
        return CanonicalReviewState(
            envelope = envelope,
            edits = edits,
            canonicalFingerprint = fingerprint,
            plan = CanonicalProjectionPlanner.plan(envelope),
        )
    }

    private fun com.pricetrace.receiptscanner.ingestion.MerchantCandidate.synchronizedWith(
        merchant: com.pricetrace.receiptscanner.domain.ReceiptMerchant,
    ) = copy(
        name = merchant.name?.trim()?.takeIf(String::isNotEmpty) ?: name,
        branchName = merchant.branchName,
        address = merchant.address,
        phone = merchant.phone,
        businessRegistrationNumber = merchant.businessRegistrationNumber,
        businessKind = merchant.businessKind,
    )

    private fun String?.editableText(): String? = this?.takeIf(String::isNotBlank)

    private companion object {
        private const val USER_MODIFIED_PROVENANCE = "{\"user_modified\":true}"
        private const val UNDO_PROVENANCE = "{\"user_modified\":true,\"undo\":true}"
        private const val REDO_PROVENANCE = "{\"user_modified\":true,\"redo\":true}"
        private const val MAX_UNDO_DEPTH = 50
    }
}
