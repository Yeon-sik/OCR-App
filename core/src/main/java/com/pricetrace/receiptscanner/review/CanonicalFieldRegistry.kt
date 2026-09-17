package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentEvidence
import com.pricetrace.receiptscanner.domain.ReceiptFulfillmentType
import com.pricetrace.receiptscanner.domain.FoodServiceRole
import com.pricetrace.receiptscanner.domain.ReceiptBenefitKind
import com.pricetrace.receiptscanner.ingestion.CONSUMPTION_AMOUNT_STATUSES
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.PurchaseKind
import com.pricetrace.receiptscanner.ingestion.PurchaseRecord
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordStatus
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V2_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V4_SCHEMA
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.nutrition.NutritionContract
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.nutrition.NutritionUnit
import java.math.BigDecimal

/** The control kind used by both Android and Desktop structured review. */
enum class CanonicalFieldType(val wireValue: String) {
    TEXT("text"),
    INTEGER("integer"),
    DECIMAL("decimal"),
    ENUM("enum"),
    DATE("date"),
    DATETIME("datetime"),
}

/**
 * A field which the shared editor is allowed to mutate.
 *
 * The registry is deliberately allow-list based. Evidence, identity, source and routing fields
 * do not appear here, so a caller cannot turn the editor into an arbitrary JSON patcher.
 */
data class CanonicalEditableField(
    val path: String,
    val label: String,
    val type: CanonicalFieldType,
    val nullable: Boolean,
    val enumValues: List<String> = emptyList(),
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val value: String? = null,
    /** When true, [min] is an exclusive lower bound (used by positive quantities). */
    val minExclusive: Boolean = false,
)

object CanonicalFieldRegistry {
    private val receiptLinePath = Regex("""receipt\.line_items\[([^]]+)]\.(.+)""")
    private val receiptPaymentPath = Regex("""receipt\.payments\[(\d+)]\.(.+)""")
    private val productPath = Regex("""product_candidates\[([^]]+)]\.(.+)""")
    private val nutritionPath = Regex("""nutrition\[([^]]+)]\.(.+)""")
    private val consumptionPath = Regex("""consumption\[([^]]+)]\.(.+)""")
    private val consumptionItemPath = Regex("""items\[([^]]+)]\.(.+)""")
    private val purchasePath = Regex("""purchase_records\[([^]]+)]\.(.+)""")
    private val purchaseLinePath = Regex("""line_items\[([^]]+)]\.(.+)""")

    /** Returns only fields supported by the typed review UI for this envelope. */
    fun fields(envelope: YeonsikOcrEnvelope): List<CanonicalEditableField> = buildList {
        if (envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA && envelope.receipt != null) {
            val receipt = envelope.receipt
            add(text("receipt.merchant.name", "판매처명", nullable = true))
            add(text("receipt.merchant.branch_name", "지점명"))
            add(text("receipt.merchant.address", "판매처 주소"))
            add(text("receipt.merchant.phone", "판매처 전화"))
            add(text("receipt.merchant.business_registration_number", "사업자등록번호"))
            add(date("receipt.document.issued_on", "구매일"))
            add(datetime("receipt.document.issued_at", "구매 시각"))
            add(enum("receipt.document.fulfillment.type", "수령 방식", ReceiptFulfillmentType.entries.map { it.wireValue }, nullable = false))
            add(enum("receipt.document.fulfillment.evidence", "수령 방식 근거", ReceiptFulfillmentEvidence.entries.map { it.wireValue }, nullable = false))
            receipt.lineItems.forEach { line ->
                val prefix = "receipt.line_items[${line.id}]"
                add(text("$prefix.description", "${line.id} 설명", nullable = true))
                add(enum("$prefix.type", "${line.id} 행 유형", ReceiptLineType.entries.map { it.wireValue }, nullable = false))
                add(decimal("$prefix.quantity", "${line.id} 수량", nullable = true, min = BigDecimal.ZERO, minExclusive = true))
                add(integer("$prefix.unit_price_amount_minor", "${line.id} 단가", nullable = true, min = BigDecimal.ZERO))
                add(integer("$prefix.gross_amount_minor", "${line.id} 공급가", nullable = true, min = BigDecimal.ZERO))
                add(integer("$prefix.discount_amount_minor", "${line.id} 할인", nullable = true, min = BigDecimal.ZERO))
                add(integer("$prefix.tax_amount_minor", "${line.id} 세금", nullable = true, min = BigDecimal.ZERO))
                add(integer("$prefix.net_amount_minor", "${line.id} 결제 금액", nullable = true))
                add(enum("$prefix.food_service.role", "${line.id} 메뉴 역할", FoodServiceRole.entries.map { it.wireValue }, nullable = false))
                add(text("$prefix.food_service.applies_to_line_id", "${line.id} 옵션 부모"))
                add(enum("$prefix.food_service.benefit_kind", "${line.id} 혜택", ReceiptBenefitKind.entries.map { it.wireValue }))
            }
            val prefix = "receipt.totals"
            add(integer("$prefix.items_gross_amount_minor", "상품 총액", nullable = true, min = BigDecimal.ZERO))
            add(integer("$prefix.discount_amount_minor", "총 할인", nullable = true, min = BigDecimal.ZERO))
            add(integer("$prefix.tax_amount_minor", "총 세금", nullable = true, min = BigDecimal.ZERO))
            add(integer("$prefix.fee_amount_minor", "수수료", nullable = true))
            add(integer("$prefix.tip_amount_minor", "팁", nullable = true))
            add(integer("$prefix.rounding_amount_minor", "반올림", nullable = true))
            add(integer("$prefix.grand_total_amount_minor", "총 결제 금액", nullable = true))
            receipt.payments.forEachIndexed { index, _ ->
                val prefix = "receipt.payments[$index]"
                add(enum("$prefix.method", "$index 결제 수단", RECEIPT_PAYMENT_METHOD_VALUES, nullable = false))
                add(integer("$prefix.amount_minor", "$index 결제 금액", nullable = true))
                add(enum("$prefix.status", "$index 결제 상태", RECEIPT_PAYMENT_STATUS_VALUES, nullable = false))
                add(text("$prefix.reference", "$index 결제 참조"))
            }
        }

        if (envelope.merchantCandidate != null &&
            !(envelope.schemaVersion == YEONSIK_OCR_V2_SCHEMA && envelope.receipt != null)
        ) {
            add(text("merchant_candidate.name", "상점 후보명", nullable = false))
            add(text("merchant_candidate.branch_name", "상점 후보 지점"))
            add(text("merchant_candidate.address", "상점 후보 주소"))
            add(text("merchant_candidate.phone", "상점 후보 전화"))
            add(text("merchant_candidate.business_registration_number", "상점 후보 사업자등록번호"))
            add(enum("merchant_candidate.business_kind", "상점 후보 업종", BUSINESS_KIND_VALUES, nullable = false))
        }

        envelope.productCandidates.forEach { candidate ->
            val prefix = "product_candidates[${candidate.clientKey}]"
            add(text("$prefix.product_name", "${candidate.clientKey} 상품명", nullable = false))
            add(text("$prefix.brand_name", "${candidate.clientKey} 브랜드"))
            add(text("$prefix.manufacturer_name", "${candidate.clientKey} 제조사"))
            add(text("$prefix.specification_text", "${candidate.clientKey} 규격"))
            add(text("$prefix.variant_name", "${candidate.clientKey} 옵션"))
            if (envelope.schemaVersion != YEONSIK_OCR_V2_SCHEMA) {
                add(text("$prefix.sub_brand_name", "${candidate.clientKey} 서브 브랜드"))
                add(text("$prefix.merchant_sku", "${candidate.clientKey} 판매자 SKU"))
            }
            add(decimal("$prefix.content_amount", "${candidate.clientKey} 내용량", nullable = true, min = BigDecimal.ZERO, minExclusive = true))
            add(enum("$prefix.content_unit", "${candidate.clientKey} 내용량 단위", PRODUCT_CONTENT_UNIT_VALUES))
            add(integer(
                "$prefix.package_count",
                "${candidate.clientKey} 포장 수",
                nullable = true,
                min = BigDecimal.ONE,
                max = BigDecimal.valueOf(Int.MAX_VALUE.toLong()),
            ))
        }

        envelope.nutrition.forEach { nutrition ->
            val prefix = "nutrition[${nutrition.clientKey}]"
            when (nutrition) {
                is IngestionNutrition.ProductLabel -> {
                    add(text("$prefix.product_name", "${nutrition.clientKey} 메뉴/상품명", nullable = false))
                    add(text("$prefix.brand", "${nutrition.clientKey} 브랜드"))
                    add(enum("$prefix.category", "${nutrition.clientKey} 분류", NutritionContract.categories.toList().sorted(), nullable = false))
                    add(decimal("$prefix.basis_amount", "${nutrition.clientKey} 기준량", nullable = true, min = BigDecimal.ZERO, minExclusive = true))
                    add(enum("$prefix.basis_unit", "${nutrition.clientKey} 기준 단위", NutritionUnit.supported.toList().sorted(), nullable = false))
                    NutritionField.entries.forEach { field ->
                        add(decimal("$prefix.nutrients.${field.wireKey}", "${nutrition.clientKey} ${field.koreanLabel}", nullable = true, min = BigDecimal.ZERO))
                    }
                }
                is IngestionNutrition.RestaurantEstimate,
                is IngestionNutrition.RestaurantMenuEstimate,
                is IngestionNutrition.MealComponentEstimate,
                -> {
                    add(text("$prefix.menu_name", "${nutrition.clientKey} 메뉴명", nullable = false))
                    NutritionField.entries.forEach { field ->
                        add(decimal("$prefix.nutrients.${field.wireKey}", "${nutrition.clientKey} ${field.koreanLabel}", nullable = true, min = BigDecimal.ZERO))
                    }
                }
            }
        }

        envelope.consumption.forEach { consumption ->
            val prefix = "consumption[${consumption.clientKey}]"
            add(datetime("$prefix.consumed_at", "${consumption.clientKey} 섭취 시각"))
            consumption.items.forEach { item ->
                val itemPrefix = "$prefix.items[${item.nutritionClientKey}]"
                add(decimal("$itemPrefix.amount", "${consumption.clientKey}/${item.nutritionClientKey} 섭취량", nullable = true, min = BigDecimal.ZERO, minExclusive = true))
                add(enum("$itemPrefix.unit", "${consumption.clientKey}/${item.nutritionClientKey} 단위", NutritionUnit.supported.toList().sorted()))
                add(enum(
                    "$itemPrefix.amount_status",
                    "${consumption.clientKey}/${item.nutritionClientKey} 섭취량 상태",
                    CONSUMPTION_AMOUNT_STATUSES.toList().sorted(),
                    nullable = false,
                ))
            }
        }

        if (envelope.schemaVersion == YEONSIK_OCR_V4_SCHEMA) {
            envelope.purchaseRecords.forEach { record -> addAll(purchaseFields(record)) }
        }
    }.map { field -> field.copy(value = value(envelope, field.path)) }

    fun descriptor(envelope: YeonsikOcrEnvelope, path: String): CanonicalEditableField? {
        val canonical = canonicalPath(envelope, path) ?: return null
        return fields(envelope).firstOrNull { it.path == canonical }
    }

    /** Compares the materialized value with the first value recorded for this field. */
    fun isModified(field: CanonicalEditableField, edits: List<CanonicalReviewEdit>): Boolean {
        val firstEdit = edits.firstOrNull { it.fieldPath == field.path } ?: return false
        return when (field.type) {
            CanonicalFieldType.INTEGER,
            CanonicalFieldType.DECIMAL,
            -> runCatching {
                when {
                    firstEdit.previousValue == null && field.value == null -> false
                    firstEdit.previousValue == null || field.value == null -> true
                    else -> BigDecimal(firstEdit.previousValue).compareTo(BigDecimal(field.value)) != 0
                }
            }.getOrDefault(firstEdit.previousValue != field.value)
            else -> firstEdit.previousValue != field.value
        }
    }

    /** Normalizes UI aliases while refusing paths that cannot be produced by the registry. */
    fun canonicalPath(envelope: YeonsikOcrEnvelope, path: String): String? {
        var candidate = path.trim()
            .removePrefix("canonical.")
            .replace("purchase_record[", "purchase_records[")
            .replace("product_candidate[", "product_candidates[")
        if (envelope.receipt != null && candidate.startsWith("merchant.")) candidate = "receipt.$candidate"
        if (envelope.receipt != null && candidate.startsWith("line_items[")) candidate = "receipt.$candidate"
        if (!candidate.startsWith("receipt.") && candidate.startsWith("document.")) candidate = "receipt.$candidate"
        if (candidate.startsWith("purchase_records[") && candidate.contains(".lines[")) {
            candidate = candidate.replace(".lines[", ".line_items[")
        }
        receiptLinePath.matchEntire(candidate)?.let { match ->
            val line = envelope.receipt?.lineItems?.findByKey(match.groupValues[1])
            if (line != null) candidate = "receipt.line_items[" + line.id + "]." + match.groupValues[2]
        }
        purchasePath.matchEntire(candidate)?.let { recordMatch ->
            val record = envelope.purchaseRecords.singleOrNull { it.clientKey == recordMatch.groupValues[1] }
            if (record != null) {
                val field = recordMatch.groupValues[2]
                purchaseLinePath.matchEntire(field)?.let { lineMatch ->
                    val line = record.lineItems.findByKey(lineMatch.groupValues[1])
                    if (line != null) {
                        val key = line.lineKey ?: record.lineItems.indexOf(line).toString()
                        candidate = "purchase_records[" + record.clientKey + "].line_items[" + key + "]." + lineMatch.groupValues[2]
                    }
                }
            }
        }
        return fields(envelope).firstOrNull { it.path == candidate }?.path
    }

    /** Current value in the typed domain, represented in the same form accepted by updateField. */
    fun value(envelope: YeonsikOcrEnvelope, path: String): String? {
        val canonical = canonicalPathWithoutLookup(path)
        return when {
            canonical == "receipt.merchant.name" -> envelope.receipt?.merchant?.name
            canonical == "receipt.merchant.branch_name" -> envelope.receipt?.merchant?.branchName
            canonical == "receipt.merchant.address" -> envelope.receipt?.merchant?.address
            canonical == "receipt.merchant.phone" -> envelope.receipt?.merchant?.phone
            canonical == "receipt.merchant.business_registration_number" -> envelope.receipt?.merchant?.businessRegistrationNumber
            canonical == "receipt.document.issued_on" -> envelope.receipt?.document?.issuedOn
            canonical == "receipt.document.issued_at" -> envelope.receipt?.document?.issuedAt
            canonical == "receipt.document.fulfillment.type" -> envelope.receipt?.document?.fulfillment?.type?.wireValue
            canonical == "receipt.document.fulfillment.evidence" -> envelope.receipt?.document?.fulfillment?.evidence?.wireValue
            canonical.startsWith("receipt.line_items[") -> receiptLineValue(envelope, canonical)
            canonical.startsWith("receipt.totals.") -> receiptTotalsValue(envelope, canonical.removePrefix("receipt.totals."))
            receiptPaymentPath.matches(canonical) -> receiptPaymentValue(envelope, receiptPaymentPath.matchEntire(canonical)!!)
            canonical.startsWith("merchant_candidate.") -> merchantCandidateValue(envelope, canonical.removePrefix("merchant_candidate."))
            productPath.matches(canonical) -> productValue(envelope, productPath.matchEntire(canonical)!!)
            nutritionPath.matches(canonical) -> nutritionValue(envelope, nutritionPath.matchEntire(canonical)!!)
            consumptionPath.matches(canonical) -> consumptionValue(envelope, consumptionPath.matchEntire(canonical)!!)
            purchasePath.matches(canonical) -> purchaseValue(envelope, purchasePath.matchEntire(canonical)!!)
            else -> null
        }
    }

    private fun purchaseFields(record: PurchaseRecord): List<CanonicalEditableField> {
        val prefix = "purchase_records[${record.clientKey}]"
        val fields = mutableListOf<CanonicalEditableField>()
        fields += text("$prefix.platform", "${record.clientKey} 플랫폼", nullable = false)
        fields += text("$prefix.platform_code", "${record.clientKey} 플랫폼 코드")
        fields += text("$prefix.seller", "${record.clientKey} 판매자")
        fields += text("$prefix.seller_branch_name", "${record.clientKey} 판매자 지점")
        fields += text("$prefix.seller_source_namespace", "${record.clientKey} 판매자 namespace")
        fields += text("$prefix.seller_source_code", "${record.clientKey} 판매자 코드")
        fields += enum("$prefix.seller_business_kind", "${record.clientKey} 판매자 업종", BUSINESS_KIND_VALUES)
        fields += text("$prefix.order_reference", "${record.clientKey} 주문 번호")
        fields += enum("$prefix.purchase_kind", "${record.clientKey} 구매 유형", PurchaseKind.entries.map { it.wireValue }, nullable = false)
        fields += date("$prefix.ordered_on", "${record.clientKey} 주문일")
        fields += datetime("$prefix.ordered_at", "${record.clientKey} 주문 시각")
        fields += date("$prefix.paid_on", "${record.clientKey} 결제일")
        fields += datetime("$prefix.paid_at", "${record.clientKey} 결제 시각")
        fields += enum("$prefix.status", "${record.clientKey} 상태", PurchaseRecordStatus.entries.map { it.wireValue }, nullable = false)
        listOf(
            "subtotal_amount_krw" to "소계",
            "discount_amount_krw" to "할인",
            "shipping_amount_krw" to "배송비",
            "tax_amount_krw" to "세금",
            "grand_total_amount_krw" to "총액",
            "paid_amount_krw" to "결제액",
        ).forEach { (name, label) ->
            fields += integer("$prefix.totals.$name", "${record.clientKey} $label", nullable = true, min = BigDecimal.ZERO)
        }
        fields += enum("$prefix.payment.method", "${record.clientKey} 결제 수단", PAYMENT_METHOD_VALUES)
        fields += text("$prefix.payment.provider", "${record.clientKey} 결제 제공자")
        fields += enum("$prefix.payment.status", "${record.clientKey} 결제 상태", PAYMENT_STATUS_VALUES)
        record.lineItems.forEachIndexed { index, line ->
            val key = line.lineKey ?: index.toString()
            val linePrefix = "$prefix.line_items[$key]"
            fields += text("$linePrefix.description", "$key 설명", nullable = false)
            fields += text("$linePrefix.seller_override", "$key 판매자 override")
            fields += text("$linePrefix.option_text", "$key 옵션")
            fields += enum("$linePrefix.price_status", "$key 가격 상태", listOf("itemized", "ambiguous", "unknown"), nullable = false)
            fields += text("$linePrefix.merchant_sku", "$key 판매자 SKU")
            fields += decimal("$linePrefix.quantity", "$key 수량", nullable = true, min = BigDecimal.ZERO, minExclusive = true)
            listOf(
                "unit_price_amount_krw" to "단가",
                "gross_amount_krw" to "공급가",
                "discount_amount_krw" to "할인",
                "net_amount_krw" to "결제 금액",
            ).forEach { (name, label) ->
                fields += integer("$linePrefix.$name", "$key $label", nullable = true, min = BigDecimal.ZERO)
            }
        }
        return fields
    }

    private fun receiptLineValue(envelope: YeonsikOcrEnvelope, path: String): String? {
        val match = receiptLinePath.matchEntire(path) ?: return null
        val line = envelope.receipt?.lineItems?.findByKey(match.groupValues[1]) ?: return null
        return when (match.groupValues[2]) {
            "description" -> line.description
            "type" -> line.type.wireValue
            "quantity", "quantity.value" -> line.quantity?.value
            "unit_price_amount_minor" -> line.unitPriceAmountMinor?.toString()
            "gross_amount_minor" -> line.grossAmountMinor?.toString()
            "discount_amount_minor" -> line.discountAmountMinor?.toString()
            "tax_amount_minor" -> line.taxAmountMinor?.toString()
            "net_amount_minor" -> line.netAmountMinor?.toString()
            "food_service.role" -> line.foodService?.role?.wireValue
            "food_service.applies_to_line_id" -> line.foodService?.appliesToLineId
            "food_service.benefit_kind" -> line.foodService?.benefitKind?.wireValue
            else -> null
        }
    }

    private fun receiptTotalsValue(envelope: YeonsikOcrEnvelope, field: String): String? {
        val totals = envelope.receipt?.totals ?: return null
        return when (field) {
            "items_gross_amount_minor" -> totals.itemsGrossAmountMinor
            "discount_amount_minor" -> totals.discountAmountMinor
            "tax_amount_minor" -> totals.taxAmountMinor
            "fee_amount_minor" -> totals.feeAmountMinor
            "tip_amount_minor" -> totals.tipAmountMinor
            "rounding_amount_minor" -> totals.roundingAmountMinor
            "grand_total_amount_minor" -> totals.grandTotalAmountMinor
            else -> null
        }?.toString()
    }

    private fun receiptPaymentValue(envelope: YeonsikOcrEnvelope, match: MatchResult): String? {
        val payment = envelope.receipt?.payments?.getOrNull(match.groupValues[1].toInt()) ?: return null
        return when (match.groupValues[2]) {
            "method" -> payment.method
            "amount_minor" -> payment.amountMinor?.toString()
            "status" -> payment.status
            "reference" -> payment.reference
            else -> null
        }
    }

    private fun merchantCandidateValue(envelope: YeonsikOcrEnvelope, field: String): String? {
        val candidate = envelope.merchantCandidate ?: return null
        return when (field) {
            "name" -> candidate.name
            "branch_name" -> candidate.branchName
            "address" -> candidate.address
            "phone" -> candidate.phone
            "business_registration_number" -> candidate.businessRegistrationNumber
            "business_kind" -> candidate.businessKind.wireValue
            else -> null
        }
    }

    private fun productValue(envelope: YeonsikOcrEnvelope, match: MatchResult): String? {
        val candidate = envelope.productCandidates.singleOrNull { it.clientKey == match.groupValues[1] } ?: return null
        return when (match.groupValues[2]) {
            "product_name" -> candidate.productName
            "brand_name" -> candidate.brand
            "sub_brand_name" -> candidate.subBrand
            "manufacturer_name" -> candidate.manufacturer
            "specification_text" -> candidate.specification
            "variant_name" -> candidate.variant
            "content_amount" -> candidate.contentAmount?.toString()
            "content_unit" -> candidate.contentUnit
            "package_count" -> candidate.packageCount?.toString()
            "merchant_sku" -> candidate.merchantSku
            else -> null
        }
    }

    private fun nutritionValue(envelope: YeonsikOcrEnvelope, match: MatchResult): String? {
        val item = envelope.nutrition.singleOrNull { it.clientKey == match.groupValues[1] } ?: return null
        return when (val field = match.groupValues[2]) {
            "product_name" -> (item as? IngestionNutrition.ProductLabel)?.draft?.productName
            "brand" -> (item as? IngestionNutrition.ProductLabel)?.draft?.brand
            "category" -> (item as? IngestionNutrition.ProductLabel)?.draft?.category
            "basis_amount" -> (item as? IngestionNutrition.ProductLabel)?.draft?.basisAmount?.toString()
            "basis_unit" -> (item as? IngestionNutrition.ProductLabel)?.draft?.basisUnit
            "menu_name" -> when (item) {
                is IngestionNutrition.RestaurantEstimate -> item.menuName
                is IngestionNutrition.RestaurantMenuEstimate -> item.menuName
                is IngestionNutrition.MealComponentEstimate -> item.menuName
                else -> null
            }
            else -> field.removePrefix("nutrients.").let { nutrientKey ->
                val nutrient = NutritionField.fromWireKey(nutrientKey) ?: return null
                when (item) {
                    is IngestionNutrition.ProductLabel -> item.draft.nutrients[nutrient]?.toString()
                    is IngestionNutrition.RestaurantEstimate -> item.estimate.nutrients[nutrient]?.toString()
                    is IngestionNutrition.RestaurantMenuEstimate -> item.estimate.nutrients[nutrient]?.toString()
                    is IngestionNutrition.MealComponentEstimate -> item.estimate.nutrients[nutrient]?.toString()
                }
            }
        }
    }

    private fun consumptionValue(envelope: YeonsikOcrEnvelope, match: MatchResult): String? {
        val consumption = envelope.consumption.singleOrNull { it.clientKey == match.groupValues[1] } ?: return null
        val field = match.groupValues[2]
        if (!field.startsWith("items[")) return if (field == "consumed_at") consumption.consumedAt else null
        val itemMatch = consumptionItemPath.matchEntire(field) ?: return null
        val item = consumption.items.singleOrNull { it.nutritionClientKey == itemMatch.groupValues[1] } ?: return null
        return when (itemMatch.groupValues[2]) {
            "amount" -> item.amount?.toString()
            "unit" -> item.unit
            "amount_status" -> item.amountStatus
            else -> null
        }
    }

    private fun purchaseValue(envelope: YeonsikOcrEnvelope, match: MatchResult): String? {
        val recordMatch = purchasePath.matchEntire("purchase_records[${match.groupValues[1]}].${match.groupValues[2]}")
            ?: return null
        val record = envelope.purchaseRecords.singleOrNull { it.clientKey == recordMatch.groupValues[1] } ?: return null
        val field = recordMatch.groupValues[2]
        return when {
            field == "platform" -> record.platform
            field == "platform_code" -> record.platformCode
            field == "seller" -> record.seller
            field == "seller_branch_name" -> record.sellerBranchName
            field == "seller_source_namespace" -> record.sellerSourceNamespace
            field == "seller_source_code" -> record.sellerSourceCode
            field == "seller_business_kind" -> record.sellerBusinessKind
            field == "order_reference" -> record.orderReference
            field == "purchase_kind" -> record.purchaseKind.wireValue
            field == "ordered_on" -> record.orderedOn
            field == "ordered_at" -> record.orderedAt
            field == "paid_on" -> record.paidOn
            field == "paid_at" -> record.paidAt
            field == "status" -> record.status.wireValue
            field.startsWith("totals.") -> when (field.removePrefix("totals.")) {
                "subtotal_amount_krw" -> record.totals.subtotalAmountKrw
                "discount_amount_krw" -> record.totals.discountAmountKrw
                "shipping_amount_krw" -> record.totals.shippingAmountKrw
                "tax_amount_krw" -> record.totals.taxAmountKrw
                "grand_total_amount_krw" -> record.totals.grandTotalAmountKrw
                "paid_amount_krw" -> record.totals.paidAmountKrw
                else -> null
            }?.toString()
            field.startsWith("payment.") -> when (field.removePrefix("payment.")) {
                "method" -> record.payment?.method
                "provider" -> record.payment?.provider
                "status" -> record.payment?.status
                else -> null
            }
            field.startsWith("line_items[") -> {
                val lineMatch = purchaseLinePath.matchEntire(field) ?: return null
                val line = record.lineItems.findByKey(lineMatch.groupValues[1]) ?: return null
                when (lineMatch.groupValues[2]) {
                    "description" -> line.description
                    "seller_override" -> line.sellerOverride
                    "option_text" -> line.optionText
                    "price_status" -> line.priceStatus
                    "merchant_sku" -> line.merchantSku
                    "quantity" -> line.quantity?.toString()
                    "unit_price_amount_krw" -> line.unitPriceAmountKrw?.toString()
                    "gross_amount_krw" -> line.grossAmountKrw?.toString()
                    "discount_amount_krw" -> line.discountAmountKrw?.toString()
                    "net_amount_krw" -> line.netAmountKrw?.toString()
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun List<com.pricetrace.receiptscanner.domain.ReceiptV2LineItem>.findByKey(key: String) =
        singleOrNull { it.id == key } ?: key.toIntOrNull()?.let { getOrNull(it) }

    private fun List<com.pricetrace.receiptscanner.ingestion.PurchaseRecordLine>.findByKey(key: String) =
        singleOrNull { it.lineKey == key } ?: key.toIntOrNull()?.let { getOrNull(it) }

    private fun canonicalPathWithoutLookup(path: String): String = path.trim()
        .removePrefix("canonical.")
        .replace("purchase_record[", "purchase_records[")
        .replace("product_candidate[", "product_candidates[")
        .let { if (it.startsWith("merchant.")) "receipt.$it" else it }
        .let { if (it.startsWith("line_items[")) "receipt.$it" else it }
        .replace(".lines[", ".line_items[")

    private fun text(path: String, label: String, nullable: Boolean = true) = CanonicalEditableField(path, label, CanonicalFieldType.TEXT, nullable)
    private fun integer(path: String, label: String, nullable: Boolean, min: BigDecimal? = null, max: BigDecimal? = null, minExclusive: Boolean = false) = CanonicalEditableField(path, label, CanonicalFieldType.INTEGER, nullable, min = min, max = max, minExclusive = minExclusive)
    private fun decimal(path: String, label: String, nullable: Boolean, min: BigDecimal? = null, max: BigDecimal? = null, minExclusive: Boolean = false) = CanonicalEditableField(path, label, CanonicalFieldType.DECIMAL, nullable, min = min, max = max, minExclusive = minExclusive)
    private fun date(path: String, label: String) = CanonicalEditableField(path, label, CanonicalFieldType.DATE, true)
    private fun datetime(path: String, label: String) = CanonicalEditableField(path, label, CanonicalFieldType.DATETIME, true)
    private fun enum(path: String, label: String, values: List<String>, nullable: Boolean = true) = CanonicalEditableField(path, label, CanonicalFieldType.ENUM, nullable, values.distinct().sorted())

    private val RECEIPT_PAYMENT_STATUS_VALUES = listOf("authorized", "paid", "refunded", "voided", "unknown")
    private val RECEIPT_PAYMENT_METHOD_VALUES = listOf("cash", "card", "bank_transfer", "mobile_payment", "gift_card", "points", "mixed", "unknown")
    private val PRODUCT_CONTENT_UNIT_VALUES = listOf("g", "ml", "each")
    private val PAYMENT_STATUS_VALUES = listOf("pending", "paid", "cancelled", "refunded", "unknown")
    private val PAYMENT_METHOD_VALUES = listOf("card", "bank_transfer", "mobile_payment", "points", "mixed", "unknown")
    private val BUSINESS_KIND_VALUES = listOf(
        "retail", "food_service", "transport", "accommodation", "healthcare", "professional_service",
        "utility", "government", "financial", "marketplace", "other", "unknown",
    )
}
