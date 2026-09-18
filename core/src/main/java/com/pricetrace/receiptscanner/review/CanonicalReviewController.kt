package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.BusinessKind
import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.domain.ReceiptBenefitKind
import com.pricetrace.receiptscanner.domain.ReceiptFoodService
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentEvidence
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentType
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptQuantity
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.ReceiptV2LineItem
import com.pricetrace.receiptscanner.domain.StableIds
import com.pricetrace.receiptscanner.ingestion.CanonicalEnvelopeValidator
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlan
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner
import com.pricetrace.receiptscanner.ingestion.IngestionConsumption
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionReviewStatus
import com.pricetrace.receiptscanner.ingestion.PurchaseKind
import com.pricetrace.receiptscanner.ingestion.PurchaseRecord
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordLine
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordPayment
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordStatus
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordTotals
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelopeCodec
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V4_SCHEMA
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.nutrition.NutritionUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** Canonical, auditable edit record shared by Android and Desktop structured review. */
data class CanonicalReviewEdit(
    val id: String,
    val fieldPath: String,
    val previousValue: String?,
    val newValue: String?,
    val provenanceJson: String,
    val editedAt: String,
    /** Allows revision archival to retain numbers as JSON numbers instead of strings. */
    val valueType: CanonicalFieldType? = null,
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
    /** Field-scoped validation failures from the last typed mutation. */
    val fieldErrors: Map<String, String> = emptyMap(),
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
    private val receiptLinePath = Regex("""receipt\.line_items\[([^]]+)]\.(.+)""")
    private val receiptPaymentPath = Regex("""receipt\.payments\[(\d+)]\.(.+)""")
    private val productPath = Regex("""product_candidates\[([^]]+)]\.(.+)""")
    private val nutritionPath = Regex("""nutrition\[([^]]+)]\.(.+)""")
    private val consumptionPath = Regex("""consumption\[([^]]+)]\.(.+)""")
    private val consumptionItemPath = Regex("""items\[([^]]+)]\.(.+)""")
    private val purchasePath = Regex("""purchase_records\[([^]]+)]\.(.+)""")
    private val purchaseLinePath = Regex("""line_items\[([^]]+)]\.(.+)""")

    private val mutableState = MutableStateFlow(
        stateFor(initialEnvelope, initialEdits),
    )
    val state: StateFlow<CanonicalReviewState> = mutableState.asStateFlow()

    private data class FieldChange(
        val fieldPath: String,
        val previousValue: String?,
        val newValue: String?,
        val valueType: CanonicalFieldType? = null,
    )

    private data class Checkpoint(
        val envelope: YeonsikOcrEnvelope,
        val changes: List<FieldChange>,
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
            ?: return fail("메뉴 역할을 먼저 지정하세요.")
        val previousBenefit = previousFoodService.benefitKind
        val nextBenefit = when {
            checked -> ReceiptBenefitKind.REVIEW_EVENT
            previousBenefit == ReceiptBenefitKind.REVIEW_EVENT -> null
            else -> previousBenefit
        }
        if (nextBenefit == previousBenefit) return true
        val nextFoodService = previousFoodService.copy(
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
                changes = checkpoint.changes,
            ),
        )
        publish(
            envelope = checkpoint.envelope,
            changes = checkpoint.changes.map { change ->
                change.copy(previousValue = change.newValue, newValue = change.previousValue)
            },
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
                changes = checkpoint.changes,
            ),
        )
        publish(
            envelope = checkpoint.envelope,
            changes = checkpoint.changes,
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
        val candidateChanges = merchantCandidateChanges(envelope, nextEnvelope)
        return commitEnvelope(
            envelope = nextEnvelope,
            changes = listOf(
                FieldChange(
                    fieldPath = fieldPath,
                    previousValue = previous(receipt.merchant),
                    newValue = nextValue,
                    valueType = CanonicalFieldRegistry.descriptor(envelope, fieldPath)?.type,
                ),
            ) + candidateChanges,
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
        val currentEnvelope = mutableState.value.envelope
        var nextEnvelope = currentEnvelope.copy(receipt = receipt.copy(lineItems = lines))
        val changes = mutableListOf(
            FieldChange(
                fieldPath = fieldPath,
                previousValue = previousValue,
                newValue = newValue,
                valueType = CanonicalFieldRegistry.descriptor(currentEnvelope, fieldPath)?.type,
            ),
        )
        if (fieldPath.endsWith(".description")) {
            val linked = nextEnvelope.nutrition.map { item ->
                when (item) {
                    is IngestionNutrition.RestaurantEstimate -> if (item.lineId == lineId && item.menuName == previousValue) {
                        item.copy(menuName = newValue.orEmpty())
                    } else item
                    is IngestionNutrition.RestaurantMenuEstimate -> if (item.lineId == lineId && item.menuName == previousValue) {
                        item.copy(menuName = newValue.orEmpty())
                    } else item
                    is IngestionNutrition.MealComponentEstimate -> if (item.lineId == lineId && item.menuName == previousValue) {
                        item.copy(menuName = newValue.orEmpty())
                    } else item
                    else -> item
                }
            }
            nextEnvelope = nextEnvelope.copy(nutrition = linked)
            nextEnvelope.nutrition.forEachIndexed { index, item ->
                val old = currentEnvelope.nutrition.getOrNull(index)
                if (old != null && old != item) {
                    val oldName = nutritionMenuName(old)
                    val newName = nutritionMenuName(item)
                    if (oldName != newName) {
                        changes += FieldChange(
                            fieldPath = "nutrition[${item.clientKey}].menu_name",
                            previousValue = oldName,
                            newValue = newName,
                            valueType = CanonicalFieldType.TEXT,
                        )
                    }
                }
            }
        }
        return commitEnvelope(
            envelope = nextEnvelope,
            changes = changes,
        )
    }

    private fun commitEnvelope(
        envelope: YeonsikOcrEnvelope,
        changes: List<FieldChange>,
        provenanceJson: String = USER_MODIFIED_PROVENANCE,
    ): Boolean {
        val current = mutableState.value
        val issues = validate(envelope)
        if (issues.isNotEmpty()) {
            val message = issues.joinToString(", ")
            mutableState.value = current.copy(
                validationIssues = issues,
                error = message,
                fieldErrors = changes.map { it.fieldPath }.distinct().associateWith { message },
            )
            return false
        }
        if (current.envelope == envelope) return true
        undoStack.addLast(Checkpoint(current.envelope, changes))
        while (undoStack.size > MAX_UNDO_DEPTH) undoStack.removeFirst()
        redoStack.clear()
        publish(envelope, changes, provenanceJson)
        return true
    }

    private fun publish(
        envelope: YeonsikOcrEnvelope,
        changes: List<FieldChange>,
        provenanceJson: String,
    ) {
        val current = mutableState.value
        val editedAt = now()
        val revisedEnvelope = envelope.copy(
            review = envelope.review.copy(
                status = IngestionReviewStatus.NEEDS_REVIEW,
            ),
        )
        val documentId = revisedEnvelope.receipt?.document?.localDocumentId
            ?: revisedEnvelope.receipt?.document?.id
            ?: current.canonicalFingerprint
        mutableState.value = stateFor(
            envelope = revisedEnvelope,
            edits = current.edits + changes.map { change ->
                CanonicalReviewEdit(
                    id = StableIds.editId(documentId, change.fieldPath, editedAt, editSequence++),
                    fieldPath = change.fieldPath,
                    previousValue = change.previousValue,
                    newValue = change.newValue,
                    provenanceJson = provenanceJson,
                    editedAt = editedAt,
                    valueType = change.valueType,
                )
            },
        ).copy(
            error = null,
            fieldErrors = emptyMap(),
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
        )
    }

    /**
     * Applies one registry-approved typed mutation. This is the common entry point used by the
     * Android and Desktop editors; the older convenience methods below remain source-compatible
     * for existing callers and tests.
     */
    fun updateField(fieldPath: String, value: String?): Boolean {
        val envelope = mutableState.value.envelope
        val canonicalPath = CanonicalFieldRegistry.canonicalPath(envelope, fieldPath)
            ?: return failField(fieldPath, "이 schema에서 편집할 수 없는 필드입니다.")
        val descriptor = CanonicalFieldRegistry.descriptor(envelope, canonicalPath)
            ?: return failField(canonicalPath, "typed field descriptor가 없습니다.")
        val parsed = runCatching { parseValue(descriptor, value) }.getOrElse { error ->
            return failField(canonicalPath, error.message ?: "값 형식이 올바르지 않습니다.")
        }
        val descriptorPrevious = CanonicalFieldRegistry.value(envelope, canonicalPath)
        val previousValue = normalizeCurrentValue(descriptor, descriptorPrevious)
        if (previousValue == parsed.canonicalValue) {
            mutableState.value = mutableState.value.copy(
                validationIssues = emptyList(),
                error = null,
                fieldErrors = emptyMap(),
            )
            return true
        }
        val appliedEnvelope = runCatching { applyTypedField(envelope, canonicalPath, parsed) }.getOrElse { error ->
            return failField(canonicalPath, error.message ?: "필드를 적용할 수 없습니다.")
        }
        val nextEnvelope = runCatching {
            syncLinkedFacts(envelope, appliedEnvelope, canonicalPath)
        }.getOrElse { error ->
            return failField(canonicalPath, error.message ?: "연결된 canonical 필드를 적용할 수 없습니다.")
        }
        val linkedChanges = linkedFactChanges(envelope, nextEnvelope, canonicalPath)
        return commitEnvelope(
            envelope = nextEnvelope,
            changes = listOf(
                FieldChange(
                    fieldPath = canonicalPath,
                    previousValue = previousValue,
                    newValue = parsed.canonicalValue,
                    valueType = descriptor.type,
                ),
            ) + linkedChanges,
        )
    }

    /** Naming alias for clients that model a UI action as a canonical field mutation. */
    fun applyTypedMutation(fieldPath: String, value: String?): Boolean = updateField(fieldPath, value)

    fun editableFields(): List<CanonicalEditableField> = CanonicalFieldRegistry.fields(mutableState.value.envelope)

    private data class ParsedValue(
        val value: Any?,
        val canonicalValue: String?,
    )

    private fun parseValue(descriptor: CanonicalEditableField, raw: String?): ParsedValue {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) {
            if (!descriptor.nullable) error("값을 비워 둘 수 없습니다.")
            return ParsedValue(null, null)
        }
        return when (descriptor.type) {
            CanonicalFieldType.TEXT -> ParsedValue(text, text)
            CanonicalFieldType.ENUM -> {
                val normalized = descriptor.enumValues.firstOrNull { it == text || it.equals(text, ignoreCase = true) }
                    ?: error("허용되지 않은 enum 값입니다: $text")
                ParsedValue(normalized, normalized)
            }
            CanonicalFieldType.INTEGER -> {
                val number = runCatching { BigDecimal(text) }.getOrElse { error("정수 값을 입력하세요.") }
                require(number.stripTrailingZeros().scale() <= 0) { "정수 값을 입력하세요." }
                val value = runCatching { number.longValueExact() }.getOrElse { error("정수 범위를 벗어났습니다.") }
                checkRange(descriptor, BigDecimal.valueOf(value))
                ParsedValue(value, value.toString())
            }
            CanonicalFieldType.DECIMAL -> {
                val number = runCatching { BigDecimal(text) }.getOrElse { error("숫자 값을 입력하세요.") }
                val double = number.toDouble()
                require(double.isFinite()) { "유한한 숫자만 입력할 수 있습니다." }
                checkRange(descriptor, number)
                val canonical = number.stripTrailingZeros().toPlainString()
                ParsedValue(double, canonical)
            }
            CanonicalFieldType.DATE -> {
                LocalDate.parse(text)
                ParsedValue(text, text)
            }
            CanonicalFieldType.DATETIME -> {
                OffsetDateTime.parse(text)
                ParsedValue(text, text)
            }
        }
    }

    private fun checkRange(descriptor: CanonicalEditableField, value: BigDecimal) {
        descriptor.min?.let {
            if (descriptor.minExclusive) {
                require(value > it) { "${descriptor.label}은(는) ${it.toPlainString()}보다 커야 합니다." }
            } else {
                require(value >= it) { "${descriptor.label}은(는) ${it.toPlainString()} 이상이어야 합니다." }
            }
        }
        descriptor.max?.let { require(value <= it) { "${descriptor.label}은(는) ${it.toPlainString()} 이하여야 합니다." } }
    }

    private fun normalizeCurrentValue(descriptor: CanonicalEditableField, value: String?): String? =
        if (value == null) null else runCatching { parseValue(descriptor, value).canonicalValue }.getOrDefault(value)

    private fun syncLinkedFacts(
        original: YeonsikOcrEnvelope,
        applied: YeonsikOcrEnvelope,
        path: String,
    ): YeonsikOcrEnvelope {
        val lineMatch = receiptLinePath.matchEntire(path)
        if (lineMatch == null || lineMatch.groupValues[2] != "description") return applied
        val originalLine = original.receipt?.lineItems?.findByKey(lineMatch.groupValues[1]) ?: return applied
        val nextLine = applied.receipt?.lineItems?.findByKey(originalLine.id) ?: return applied
        val nextMenuName = nextLine.description?.takeIf(String::isNotBlank)
        val linkedNutrition = applied.nutrition.map { item ->
            when (item) {
                is IngestionNutrition.RestaurantEstimate -> if (item.lineId == originalLine.id && item.menuName == originalLine.description) item.copy(
                    menuName = requireNotNull(nextMenuName) { "연결된 nutrition 메뉴명은 비워 둘 수 없습니다." },
                ) else item
                is IngestionNutrition.RestaurantMenuEstimate -> if (item.lineId == originalLine.id && item.menuName == originalLine.description) item.copy(
                    menuName = requireNotNull(nextMenuName) { "연결된 nutrition 메뉴명은 비워 둘 수 없습니다." },
                ) else item
                is IngestionNutrition.MealComponentEstimate -> if (item.lineId == originalLine.id && item.menuName == originalLine.description) item.copy(
                    menuName = requireNotNull(nextMenuName) { "연결된 nutrition 메뉴명은 비워 둘 수 없습니다." },
                ) else item
                else -> item
            }
        }
        return applied.copy(nutrition = linkedNutrition)
    }

    private fun linkedFactChanges(
        original: YeonsikOcrEnvelope,
        applied: YeonsikOcrEnvelope,
        path: String,
    ): List<FieldChange> = buildList {
        if (path.startsWith("receipt.merchant.")) {
            addAll(merchantCandidateChanges(original, applied))
        }
        val lineMatch = receiptLinePath.matchEntire(path)
        if (lineMatch != null && lineMatch.groupValues[2] == "description") {
            original.nutrition.forEachIndexed { index, oldItem ->
                val newItem = applied.nutrition.getOrNull(index) ?: return@forEachIndexed
                val oldName = nutritionMenuName(oldItem)
                val newName = nutritionMenuName(newItem)
                if (oldName != newName) add(FieldChange("nutrition[" + newItem.clientKey + "].menu_name", oldName, newName, CanonicalFieldType.TEXT))
            }
        }
    }

    private fun merchantCandidateChanges(
        original: YeonsikOcrEnvelope,
        applied: YeonsikOcrEnvelope,
    ): List<FieldChange> {
        val fields = listOf(
            "name",
            "branch_name",
            "address",
            "phone",
            "business_registration_number",
            "business_kind",
        )
        return fields.mapNotNull { field ->
            val path = "merchant_candidate.$field"
            val previous = candidateValue(original.merchantCandidate, path)
            val next = candidateValue(applied.merchantCandidate, path)
            if (previous == next) null else FieldChange(
                fieldPath = path,
                previousValue = previous,
                newValue = next,
                valueType = CanonicalFieldRegistry.descriptor(original, path)?.type
                    ?: if (field == "business_kind") CanonicalFieldType.ENUM else CanonicalFieldType.TEXT,
            )
        }
    }

    private fun applyTypedField(
        envelope: YeonsikOcrEnvelope,
        path: String,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope = when {
        path.startsWith("receipt.") -> applyReceiptField(envelope, path, parsed)
        path.startsWith("merchant_candidate.") -> applyMerchantCandidateField(envelope, path, parsed)
        path.startsWith("product_candidates[") -> applyProductCandidateField(envelope, path, parsed)
        path.startsWith("nutrition[") -> applyNutritionField(envelope, path, parsed)
        path.startsWith("consumption[") -> applyConsumptionField(envelope, path, parsed)
        path.startsWith("purchase_records[") -> applyPurchaseField(envelope, path, parsed)
        else -> error("이 필드는 편집할 수 없습니다.")
    }

    private fun applyReceiptField(
        envelope: YeonsikOcrEnvelope,
        path: String,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope {
        val receipt = envelope.receipt ?: error("receipt가 없습니다.")
        val value = parsed.value
        if (path == "receipt.merchant.name" && value == null && envelope.merchantCandidate != null) {
            error("merchant_candidate.name must remain a non-empty display fact")
        }
        return when {
            path == "receipt.merchant.name" -> updateMerchantTyped(envelope, receipt.merchant.copy(name = value as String?))
            path == "receipt.merchant.branch_name" -> updateMerchantTyped(envelope, receipt.merchant.copy(branchName = value as String?))
            path == "receipt.merchant.address" -> updateMerchantTyped(envelope, receipt.merchant.copy(address = value as String?))
            path == "receipt.merchant.phone" -> updateMerchantTyped(envelope, receipt.merchant.copy(phone = value as String?))
            path == "receipt.merchant.business_registration_number" -> updateMerchantTyped(envelope, receipt.merchant.copy(businessRegistrationNumber = value as String?))
            path == "receipt.document.issued_on" -> envelope.copy(receipt = receipt.copy(document = receipt.document.copy(issuedOn = value as String?)))
            path == "receipt.document.issued_at" -> envelope.copy(receipt = receipt.copy(document = receipt.document.copy(issuedAt = value as String?)))
            path == "receipt.document.fulfillment.type" -> envelope.copy(
                receipt = receipt.copy(document = receipt.document.copy(fulfillment = receipt.document.fulfillment.copy(
                    type = enumValue<ReceiptFulfillmentType>(value),
                ))),
            )
            path == "receipt.document.fulfillment.evidence" -> envelope.copy(
                receipt = receipt.copy(document = receipt.document.copy(fulfillment = receipt.document.fulfillment.copy(
                    evidence = enumValue<ReceiptFulfillmentEvidence>(value),
                ))),
            )
            receiptLinePath.matches(path) -> applyReceiptLineField(envelope, receiptLinePath.matchEntire(path)!!, parsed)
            path.startsWith("receipt.totals.") -> {
                val totals = receipt.totals
                val amount = value as Long?
                val next = when (path.removePrefix("receipt.totals.")) {
                    "items_gross_amount_minor" -> totals.copy(itemsGrossAmountMinor = amount)
                    "discount_amount_minor" -> totals.copy(discountAmountMinor = amount)
                    "tax_amount_minor" -> totals.copy(taxAmountMinor = amount)
                    "fee_amount_minor" -> totals.copy(feeAmountMinor = amount)
                    "tip_amount_minor" -> totals.copy(tipAmountMinor = amount)
                    "rounding_amount_minor" -> totals.copy(roundingAmountMinor = amount)
                    "grand_total_amount_minor" -> totals.copy(grandTotalAmountMinor = amount)
                    else -> error("receipt totals field is not editable")
                }
                envelope.copy(receipt = receipt.copy(totals = next))
            }
            receiptPaymentPath.matches(path) -> {
                val match = receiptPaymentPath.matchEntire(path)!!
                val index = match.groupValues[1].toInt()
                val payment = receipt.payments.getOrNull(index) ?: error("payments[$index]가 없습니다.")
                val nextPayment = when (match.groupValues[2]) {
                    "method" -> payment.copy(method = (value as? String) ?: error("결제 수단은 필수입니다."))
                    "amount_minor" -> payment.copy(amountMinor = value as Long?)
                    "status" -> payment.copy(status = (value as? String) ?: error("결제 상태는 필수입니다."))
                    "reference" -> payment.copy(reference = value as String?)
                    else -> error("receipt payment field is not editable")
                }
                envelope.copy(receipt = receipt.copy(payments = receipt.payments.toMutableList().also { it[index] = nextPayment }))
            }
            else -> error("receipt field is not editable")
        }
    }

    private fun applyReceiptLineField(
        envelope: YeonsikOcrEnvelope,
        match: MatchResult,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope {
        val receipt = requireNotNull(envelope.receipt)
        val line = receipt.lineItems.findByKey(match.groupValues[1]) ?: error("line_items[${match.groupValues[1]}]가 없습니다.")
        val value = parsed.value
        val nextLine = when (match.groupValues[2]) {
            "description" -> line.copy(description = value as String?)
            "type" -> line.copy(type = enumValue<ReceiptLineType>(value))
            "quantity", "quantity.value" -> line.copy(
                quantity = parsed.canonicalValue?.let { nextValue ->
                    (line.quantity ?: ReceiptQuantity(value = nextValue)).copy(value = nextValue)
                },
            )
            "unit_price_amount_minor" -> line.copy(unitPriceAmountMinor = value as Long?)
            "gross_amount_minor" -> line.copy(grossAmountMinor = value as Long?)
            "discount_amount_minor" -> line.copy(discountAmountMinor = value as Long?)
            "tax_amount_minor" -> line.copy(taxAmountMinor = value as Long?)
            "net_amount_minor" -> line.copy(netAmountMinor = value as Long?)
            "food_service.role" -> {
                val role = enumValue<FoodServiceRole>(value)
                line.copy(
                    foodService = (line.foodService ?: ReceiptFoodService(role)).copy(
                        role = role,
                        appliesToLineId = if (role == FoodServiceRole.OPTION) line.foodService?.appliesToLineId else null,
                    ),
                )
            }
            "food_service.applies_to_line_id" -> line.copy(
                foodService = (line.foodService ?: error("food_service role을 먼저 지정하세요.")).copy(appliesToLineId = value as String?),
            )
            "food_service.benefit_kind" -> line.copy(
                foodService = (line.foodService ?: error("food_service role을 먼저 지정하세요.")).copy(
                    benefitKind = value?.let { enumValue<ReceiptBenefitKind>(it) },
                ),
            )
            else -> error("receipt line field is not editable")
        }
        val updated = receipt.lineItems.map { if (it.id == line.id) nextLine else it }
        return envelope.copy(receipt = receipt.copy(lineItems = updated))
    }

    private fun updateMerchantTyped(
        envelope: YeonsikOcrEnvelope,
        merchant: com.pricetrace.receiptscanner.domain.ReceiptMerchant,
    ): YeonsikOcrEnvelope = envelope.copy(
        receipt = requireNotNull(envelope.receipt).copy(merchant = merchant),
        merchantCandidate = envelope.merchantCandidate?.synchronizedWith(merchant),
    )

    private fun applyMerchantCandidateField(
        envelope: YeonsikOcrEnvelope,
        path: String,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope {
        val candidate = envelope.merchantCandidate ?: error("merchant_candidate가 없습니다.")
        val value = parsed.value
        val next = when (path.removePrefix("merchant_candidate.")) {
            "name" -> candidate.copy(name = (value as? String) ?: error("상점 후보명은 필수입니다."))
            "branch_name" -> candidate.copy(branchName = value as String?)
            "address" -> candidate.copy(address = value as String?)
            "phone" -> candidate.copy(phone = value as String?)
            "business_registration_number" -> candidate.copy(businessRegistrationNumber = value as String?)
            "business_kind" -> candidate.copy(businessKind = enumValue(value))
            else -> error("merchant_candidate field is not editable")
        }
        return envelope.copy(merchantCandidate = next)
    }

    private fun applyProductCandidateField(
        envelope: YeonsikOcrEnvelope,
        path: String,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope {
        val match = productPath.matchEntire(path) ?: error("product candidate path is invalid")
        val candidate = envelope.productCandidates.singleOrNull { it.clientKey == match.groupValues[1] }
            ?: error("product_candidates[${match.groupValues[1]}]가 없습니다.")
        val value = parsed.value
        val next = when (match.groupValues[2]) {
            "product_name" -> candidate.copy(productName = (value as? String) ?: error("상품명은 필수입니다."))
            "brand_name" -> candidate.copy(brand = value as String?)
            "sub_brand_name" -> candidate.copy(subBrand = value as String?)
            "manufacturer_name" -> candidate.copy(manufacturer = value as String?)
            "specification_text" -> candidate.copy(specification = value as String?)
            "variant_name" -> candidate.copy(variant = value as String?)
            "content_amount" -> candidate.copy(contentAmount = value as Double?)
            "content_unit" -> candidate.copy(contentUnit = value as String?)
            "package_count" -> candidate.copy(packageCount = (value as Long?)?.let { Math.toIntExact(it) })
            "merchant_sku" -> candidate.copy(merchantSku = value as String?)
            else -> error("product candidate field is not editable")
        }
        return envelope.copy(productCandidates = envelope.productCandidates.map { if (it.clientKey == candidate.clientKey) next else it })
    }

    private fun applyNutritionField(
        envelope: YeonsikOcrEnvelope,
        path: String,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope {
        val match = nutritionPath.matchEntire(path) ?: error("nutrition path is invalid")
        val item = envelope.nutrition.singleOrNull { it.clientKey == match.groupValues[1] }
            ?: error("nutrition[${match.groupValues[1]}]가 없습니다.")
        val field = match.groupValues[2]
        val value = parsed.value
        val next: IngestionNutrition = when (item) {
            is IngestionNutrition.ProductLabel -> when (field) {
                "product_name" -> item.copy(draft = item.draft.copy(productName = (value as? String) ?: error("영양 상품명은 필수입니다.")))
                "brand" -> item.copy(draft = item.draft.copy(brand = value as String?))
                "category" -> item.copy(draft = item.draft.copy(category = (value as? String) ?: error("영양 분류는 필수입니다.")))
                "basis_amount" -> item.copy(draft = item.draft.copy(basisAmount = value as Double?))
                "basis_unit" -> item.copy(draft = item.draft.copy(basisUnit = (value as? String) ?: error("영양 기준 단위는 필수입니다.")))
                else -> item.copy(draft = updateNutritionDraftNutrient(item.draft, field, value as Double?))
            }
            is IngestionNutrition.RestaurantEstimate -> item.updateEstimate(field, value)
            is IngestionNutrition.RestaurantMenuEstimate -> item.updateEstimate(field, value)
            is IngestionNutrition.MealComponentEstimate -> item.updateEstimate(field, value)
        }
        return envelope.copy(nutrition = envelope.nutrition.map { if (it.clientKey == item.clientKey) next else it })
    }

    private fun updateNutritionDraftNutrient(draft: NutritionLabelDraft, field: String, value: Double?): NutritionLabelDraft {
        val nutritionField = field.removePrefix("nutrients.").let { NutritionField.fromWireKey(it) }
            ?: error("nutrition nutrient field is not editable")
        return draft.withNutrient(nutritionField, value)
    }

    private fun IngestionNutrition.RestaurantEstimate.updateEstimate(field: String, value: Any?): IngestionNutrition.RestaurantEstimate =
        if (field == "menu_name") copy(menuName = value as? String ?: error("메뉴명은 필수입니다."))
        else copy(estimate = estimate.copy(nutrients = estimate.nutrients.updated(field, value as Double?)))

    private fun IngestionNutrition.RestaurantMenuEstimate.updateEstimate(field: String, value: Any?): IngestionNutrition.RestaurantMenuEstimate =
        if (field == "menu_name") copy(menuName = value as? String ?: error("메뉴명은 필수입니다."))
        else copy(estimate = estimate.copy(nutrients = estimate.nutrients.updated(field, value as Double?)))

    private fun IngestionNutrition.MealComponentEstimate.updateEstimate(field: String, value: Any?): IngestionNutrition.MealComponentEstimate =
        if (field == "menu_name") copy(menuName = value as? String ?: error("메뉴명은 필수입니다."))
        else copy(estimate = estimate.copy(nutrients = estimate.nutrients.updated(field, value as Double?)))

    private fun Map<NutritionField, Double?>.updated(field: String, value: Double?): Map<NutritionField, Double?> {
        val nutritionField = field.removePrefix("nutrients.").let { NutritionField.fromWireKey(it) }
            ?: error("nutrition nutrient field is not editable")
        return toMutableMap().also { values ->
            if (value == null) values.remove(nutritionField) else values[nutritionField] = value
        }
    }

    private fun applyConsumptionField(
        envelope: YeonsikOcrEnvelope,
        path: String,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope {
        val match = consumptionPath.matchEntire(path) ?: error("consumption path is invalid")
        val item = envelope.consumption.singleOrNull { it.clientKey == match.groupValues[1] }
            ?: error("consumption[${match.groupValues[1]}]가 없습니다.")
        val field = match.groupValues[2]
        val value = parsed.value
        val next = if (field == "consumed_at") {
            item.copy(consumedAt = value as String?)
        } else {
            val itemMatch = consumptionItemPath.matchEntire(field) ?: error("consumption item field is not editable")
            val target = item.items.singleOrNull { it.nutritionClientKey == itemMatch.groupValues[1] }
                ?: error("consumption item을 찾을 수 없습니다.")
            val nextItem = when (itemMatch.groupValues[2]) {
                "amount" -> target.copy(amount = value as Double?)
                "unit" -> target.copy(unit = value as String?)
                "amount_status" -> target.copy(amountStatus = (value as? String) ?: error("섭취량 상태는 필수입니다."))
                else -> error("consumption item field is not editable")
            }
            item.copy(items = item.items.map { if (it.nutritionClientKey == target.nutritionClientKey) nextItem else it })
        }
        return envelope.copy(consumption = envelope.consumption.map { if (it.clientKey == item.clientKey) next else it })
    }

    private fun applyPurchaseField(
        envelope: YeonsikOcrEnvelope,
        path: String,
        parsed: ParsedValue,
    ): YeonsikOcrEnvelope {
        val match = purchasePath.matchEntire(path) ?: error("purchase path is invalid")
        val record = envelope.purchaseRecords.singleOrNull { it.clientKey == match.groupValues[1] }
            ?: error("purchase_records[${match.groupValues[1]}]가 없습니다.")
        val field = match.groupValues[2]
        val value = parsed.value
        val next = when {
            field == "platform" -> record.copy(platform = (value as? String) ?: error("플랫폼은 필수입니다."))
            field == "platform_code" -> record.copy(platformCode = value as String?)
            field == "seller" -> record.copy(seller = value as String?)
            field == "seller_branch_name" -> record.copy(sellerBranchName = value as String?)
            field == "seller_source_namespace" -> record.copy(sellerSourceNamespace = value as String?)
            field == "seller_source_code" -> record.copy(sellerSourceCode = value as String?)
            field == "seller_business_kind" -> record.copy(sellerBusinessKind = value as String?)
            field == "order_reference" -> record.copy(orderReference = value as String?)
            field == "purchase_kind" -> record.copy(purchaseKind = enumValue(value))
            field == "ordered_on" -> record.copy(orderedOn = value as String?)
            field == "ordered_at" -> record.copy(orderedAt = value as String?)
            field == "paid_on" -> record.copy(paidOn = value as String?)
            field == "paid_at" -> record.copy(paidAt = value as String?)
            field == "status" -> record.copy(status = enumValue(value))
            field.startsWith("totals.") -> record.copy(totals = updatePurchaseTotals(record.totals, field.removePrefix("totals."), value as Long?))
            field.startsWith("payment.") -> record.copy(payment = updatePurchasePayment(record.payment, field.removePrefix("payment."), value as String?))
            field.startsWith("line_items[") -> updatePurchaseLine(record, field, parsed)
            else -> error("purchase record field is not editable")
        }
        return envelope.copy(purchaseRecords = envelope.purchaseRecords.map { if (it.clientKey == record.clientKey) next else it })
    }

    private fun updatePurchaseTotals(totals: PurchaseRecordTotals, field: String, value: Long?): PurchaseRecordTotals = when (field) {
        "subtotal_amount_krw" -> totals.copy(subtotalAmountKrw = value)
        "discount_amount_krw" -> totals.copy(discountAmountKrw = value)
        "shipping_amount_krw" -> totals.copy(shippingAmountKrw = value)
        "tax_amount_krw" -> totals.copy(taxAmountKrw = value)
        "grand_total_amount_krw" -> totals.copy(grandTotalAmountKrw = value)
        "paid_amount_krw" -> totals.copy(paidAmountKrw = value)
        else -> error("purchase totals field is not editable")
    }

    private fun updatePurchasePayment(payment: PurchaseRecordPayment?, field: String, value: String?): PurchaseRecordPayment? {
        val nextMethod = if (field == "method") value else payment?.method
        val nextProvider = if (field == "provider") value else payment?.provider
        val nextStatus = if (field == "status") value else payment?.status
        return if (nextMethod == null && nextProvider == null && nextStatus == null) null
        else PurchaseRecordPayment(nextMethod, nextProvider, nextStatus)
    }

    private fun updatePurchaseLine(record: PurchaseRecord, field: String, parsed: ParsedValue): PurchaseRecord {
        val match = purchaseLinePath.matchEntire(field) ?: error("purchase line path is invalid")
        val line = record.lineItems.findByKey(match.groupValues[1]) ?: error("purchase line을 찾을 수 없습니다.")
        val value = parsed.value
        val nextLine = when (match.groupValues[2]) {
            "description" -> line.copy(description = (value as? String) ?: error("구매 행 설명은 필수입니다."))
            "seller_override" -> line.copy(sellerOverride = value as String?)
            "option_text" -> line.copy(optionText = value as String?)
            "price_status" -> line.copy(priceStatus = (value as? String) ?: error("가격 상태는 필수입니다."))
            "merchant_sku" -> line.copy(merchantSku = value as String?)
            "quantity" -> line.copy(quantity = value as Double?)
            "unit_price_amount_krw" -> line.copy(unitPriceAmountKrw = value as Long?)
            "gross_amount_krw" -> line.copy(grossAmountKrw = value as Long?)
            "discount_amount_krw" -> line.copy(discountAmountKrw = value as Long?)
            "net_amount_krw" -> line.copy(netAmountKrw = value as Long?)
            else -> error("purchase line field is not editable")
        }
        return record.copy(lineItems = record.lineItems.map { if (it === line) nextLine else it })
    }

    private inline fun <reified T : Enum<T>> enumValue(value: Any?): T = enumValues<T>().firstOrNull {
        val enum = it as Enum<*>
        enum.name.equals(value as? String, ignoreCase = true) || enumWireValue(enum) == value
    } ?: error("허용되지 않은 enum 값입니다: $value")

    private fun enumWireValue(value: Enum<*>): String? = when (value) {
        is ReceiptLineType -> value.wireValue
        is ReceiptFulfillmentType -> value.wireValue
        is ReceiptFulfillmentEvidence -> value.wireValue
        is FoodServiceRole -> value.wireValue
        is ReceiptBenefitKind -> value.wireValue
        is BusinessKind -> value.wireValue
        is PurchaseKind -> value.wireValue
        is PurchaseRecordStatus -> value.wireValue
        else -> null
    }

    private fun failField(path: String, message: String): Boolean {
        mutableState.value = mutableState.value.copy(
            error = "$path: $message",
            validationIssues = emptyList(),
            fieldErrors = mapOf(path to message),
        )
        return false
    }

    private fun currentReceipt(): ReceiptV2? = mutableState.value.envelope.receipt

    private fun candidateValue(
        candidate: com.pricetrace.receiptscanner.ingestion.MerchantCandidate?,
        path: String,
    ): String? {
        val value = candidate ?: return null
        return when (path.removePrefix("merchant_candidate.")) {
            "name" -> value.name
            "branch_name" -> value.branchName
            "address" -> value.address
            "phone" -> value.phone
            "business_registration_number" -> value.businessRegistrationNumber
            "business_kind" -> value.businessKind.wireValue
            else -> null
        }
    }

    private fun nutritionMenuName(item: IngestionNutrition): String? = when (item) {
        is IngestionNutrition.ProductLabel -> null
        is IngestionNutrition.RestaurantEstimate -> item.menuName
        is IngestionNutrition.RestaurantMenuEstimate -> item.menuName
        is IngestionNutrition.MealComponentEstimate -> item.menuName
    }

    private fun List<ReceiptV2LineItem>.findByKey(key: String): ReceiptV2LineItem? =
        singleOrNull { it.id == key } ?: key.toIntOrNull()?.let { getOrNull(it) }

    private fun List<PurchaseRecordLine>.findByKey(key: String): PurchaseRecordLine? =
        singleOrNull { it.lineKey == key } ?: key.toIntOrNull()?.let { getOrNull(it) }

    private fun fail(message: String): Boolean {
        mutableState.value = mutableState.value.copy(
            error = message,
            validationIssues = emptyList(),
            fieldErrors = emptyMap(),
        )
        return false
    }

    private fun validate(envelope: YeonsikOcrEnvelope): List<String> =
        CanonicalEnvelopeValidator.validate(envelope)

    private fun stateFor(
        envelope: YeonsikOcrEnvelope,
        edits: List<CanonicalReviewEdit>,
    ): CanonicalReviewState {
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
