package com.pricetrace.receiptscanner.domain

import com.pricetrace.receiptscanner.parser.multiplyExactMinor
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.math.BigDecimal

enum class ValidationSeverity {
    ERROR,
    WARNING,
}

enum class ValidationCode {
    MERCHANT_MISSING,
    PURCHASE_TIME_MISSING,
    PURCHASE_DATE_INVALID,
    PURCHASE_DATETIME_INVALID,
    CURRENCY_NOT_KRW,
    GRAND_TOTAL_MISSING,
    ITEMS_MISSING,
    ITEM_DESCRIPTION_MISSING,
    ITEM_QUANTITY_MISSING,
    ITEM_QUANTITY_INVALID,
    ITEM_UNIT_PRICE_MISSING,
    ITEM_AMOUNT_MISSING,
    SOURCE_REFERENCE_MISSING,
    ITEM_WITHOUT_OCR_EVIDENCE,
    ITEM_AMOUNT_CONSERVATION_FAILED,
    DUPLICATE_SOURCE_REFERENCE,
    DUPLICATE_LINE_ID,
    TOTAL_RECONCILIATION_FAILED,
}

data class ValidationIssue(
    val code: ValidationCode,
    val fieldPath: String,
    val message: String,
    val severity: ValidationSeverity = ValidationSeverity.ERROR,
)

data class ReconciliationResult(
    val lineNetTotalMinor: Long?,
    val grandTotalMinor: Long?,
    val differenceMinor: Long?,
) {
    val isBalanced: Boolean get() = differenceMinor == 0L
}

object ReceiptReconciler {
    fun reconcile(receipt: ReceiptV2): ReconciliationResult {
        val grandTotal = receipt.totals.grandTotalAmountMinor
        val values = receipt.lineItems.map { it.netAmountMinor }
        val lineTotal = if (values.isNotEmpty() && values.all { it != null }) {
            runCatching {
                values.filterNotNull().fold(0L) { sum, value -> Math.addExact(sum, value) }
            }.getOrNull()
        } else {
            null
        }
        return ReconciliationResult(
            lineNetTotalMinor = lineTotal,
            grandTotalMinor = grandTotal,
            differenceMinor = if (lineTotal != null && grandTotal != null) {
                runCatching { Math.subtractExact(grandTotal, lineTotal) }.getOrNull()
            } else {
                null
            },
        )
    }
}

data class ReceiptValidationResult(
    val issues: List<ValidationIssue>,
    val reconciliation: ReconciliationResult,
) {
    val canMarkUserVerified: Boolean get() = issues.none { it.severity == ValidationSeverity.ERROR }
}

object ReceiptValidator {
    fun validateForUserVerification(
        receipt: ReceiptV2,
        reconciliationReason: String? = null,
    ): ReceiptValidationResult {
        val issues = buildList {
            if (receipt.merchant.name.isNullOrBlank() && receipt.merchant.businessRegistrationNumber.isNullOrBlank()) {
                add(error(ValidationCode.MERCHANT_MISSING, "merchant", "판매처 식별 정보가 필요합니다."))
            }
            if (receipt.document.issuedOn == null && receipt.document.issuedAt == null) {
                add(error(ValidationCode.PURCHASE_TIME_MISSING, "document.issued_on", "구매일 또는 구매시각이 필요합니다."))
            }
            if (receipt.document.issuedOn != null && !receipt.document.issuedOn.isIsoDate()) {
                add(error(ValidationCode.PURCHASE_DATE_INVALID, "document.issued_on", "구매일은 유효한 YYYY-MM-DD여야 합니다."))
            }
            if (receipt.document.issuedAt != null && !receipt.document.issuedAt.isOffsetDateTime()) {
                add(
                    error(
                        ValidationCode.PURCHASE_DATETIME_INVALID,
                        "document.issued_at",
                        "구매시각은 offset을 포함한 유효한 ISO-8601이어야 합니다.",
                    ),
                )
            }
            if (receipt.document.currency != "KRW") {
                add(error(ValidationCode.CURRENCY_NOT_KRW, "document.currency", "통화가 KRW로 확인되어야 합니다."))
            }
            if (receipt.totals.grandTotalAmountMinor == null) {
                add(error(ValidationCode.GRAND_TOTAL_MISSING, "totals.grand_total_amount_minor", "최종 합계가 필요합니다."))
            }
            if (receipt.lineItems.isEmpty()) {
                add(error(ValidationCode.ITEMS_MISSING, "line_items", "검수할 영수증 행이 최소 1개 필요합니다."))
            }

            val lineIds = receipt.lineItems.map { it.id }
            if (lineIds.distinct().size != lineIds.size) {
                add(error(ValidationCode.DUPLICATE_LINE_ID, "line_items", "상품 행 ID가 중복됩니다."))
            }
            val sourceReferences = receipt.lineItems.flatMap { it.sourceLineReferences }
            if (sourceReferences.distinct().size != sourceReferences.size) {
                add(
                    error(
                        ValidationCode.DUPLICATE_SOURCE_REFERENCE,
                        "line_items.source_line_references",
                        "OCR source line reference가 여러 행에서 중복됩니다.",
                    ),
                )
            }

            receipt.lineItems.forEachIndexed { index, item ->
                if (item.sourceLineReferences.isEmpty()) {
                    add(
                        error(
                            ValidationCode.SOURCE_REFERENCE_MISSING,
                            "line_items[$index].source_line_references",
                            "OCR source line reference가 필요합니다.",
                        ),
                    )
                } else if (item.isUserEntered()) {
                    // The reviewer transcribed a row OCR never produced. That is legitimate evidence from
                    // the paper receipt, but it must stay visible instead of passing as a recognized row.
                    add(
                        warning(
                            ValidationCode.ITEM_WITHOUT_OCR_EVIDENCE,
                            "line_items[$index].source_line_references",
                            "OCR 근거 없이 사용자가 직접 입력한 행입니다. 원본과 한 번 더 대조하세요.",
                        ),
                    )
                }
                if (item.netAmountMinor == null) {
                    add(error(ValidationCode.ITEM_AMOUNT_MISSING, "line_items[$index].net_amount_minor", "행 금액이 필요합니다."))
                }
                if (item.type == ReceiptLineType.PRODUCT || item.type == ReceiptLineType.SERVICE) {
                    if (item.description.isNullOrBlank()) {
                        add(error(ValidationCode.ITEM_DESCRIPTION_MISSING, "line_items[$index].description", "행 설명이 필요합니다."))
                    }
                    if (item.quantity == null) {
                        add(error(ValidationCode.ITEM_QUANTITY_MISSING, "line_items[$index].quantity", "명시적 수량 확인이 필요합니다."))
                    } else if (runCatching { BigDecimal(item.quantity.value) > BigDecimal.ZERO }.getOrDefault(false).not()) {
                        add(error(ValidationCode.ITEM_QUANTITY_INVALID, "line_items[$index].quantity", "상품 수량은 0보다 커야 합니다."))
                    }
                    val expected = item.quantity?.let { quantity ->
                        // Some receipts omit unit price; conservation is enforceable only when it was printed or entered.
                        item.unitPriceAmountMinor?.let { unitPrice -> multiplyExactMinor(quantity.value, unitPrice) }
                    }
                    val observed = item.grossAmountMinor ?: item.netAmountMinor
                    if (expected != null && observed != null && expected != observed) {
                        add(
                            error(
                                ValidationCode.ITEM_AMOUNT_CONSERVATION_FAILED,
                                "line_items[$index]",
                                "수량 × 단가가 행 금액과 일치하지 않습니다.",
                            ),
                        )
                    }
                }
            }
        }.toMutableList()

        val reconciliation = ReceiptReconciler.reconcile(receipt)
        if (!reconciliation.isBalanced && reconciliationReason.isNullOrBlank()) {
            issues += error(
                ValidationCode.TOTAL_RECONCILIATION_FAILED,
                "totals.grand_total_amount_minor",
                "행 합계와 최종 합계의 불일치를 해소하거나 검수 사유를 기록해야 합니다.",
            )
        }
        return ReceiptValidationResult(issues, reconciliation)
    }

    fun markUserVerified(receipt: ReceiptV2, reconciliationReason: String? = null): Result<ReceiptV2> {
        val validation = validateForUserVerification(receipt, reconciliationReason)
        if (!validation.canMarkUserVerified) {
            return Result.failure(ReceiptValidationException(validation.issues))
        }
        return Result.success(
            receipt.copy(
                document = receipt.document.copy(
                    status = ReceiptStatus.FINAL,
                    source = receipt.document.source.copy(
                        transcriptionStatus = TranscriptionStatus.USER_VERIFIED,
                        notes = (
                            receipt.document.source.notes + listOfNotNull(
                                reconciliationReason?.takeIf(String::isNotBlank)?.let { "reconciliation_review: $it" },
                            )
                        ).distinct(),
                    ),
                ),
                lineItems = receipt.lineItems.map { item ->
                    item.copy(confidence = ConfidenceLevel.USER_VERIFIED)
                },
            ),
        )
    }

    private fun error(code: ValidationCode, path: String, message: String) = ValidationIssue(
        code = code,
        fieldPath = path,
        message = message,
    )

    private fun warning(code: ValidationCode, path: String, message: String) = ValidationIssue(
        code = code,
        fieldPath = path,
        message = message,
        severity = ValidationSeverity.WARNING,
    )

    private fun String.isIsoDate(): Boolean = try {
        LocalDate.parse(this)
        true
    } catch (_: DateTimeParseException) {
        false
    }

    private fun String.isOffsetDateTime(): Boolean = try {
        OffsetDateTime.parse(this)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}

class ReceiptValidationException(val issues: List<ValidationIssue>) :
    IllegalStateException(issues.joinToString { it.code.name })
