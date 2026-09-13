package com.pricetrace.receiptscanner.review

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlan
import com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner
import com.pricetrace.receiptscanner.ingestion.IngestionConsumption
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.IngestionSession
import com.pricetrace.receiptscanner.ingestion.LocalEvidence
import com.pricetrace.receiptscanner.ingestion.PurchaseRecord
import com.pricetrace.receiptscanner.ingestion.PurchaseRecordEvidence
import com.pricetrace.receiptscanner.ingestion.SourceAttachment
import com.pricetrace.receiptscanner.ingestion.SourceAttachmentType
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.nutrition.NutritionField
import com.pricetrace.receiptscanner.ingestion.ProjectionStatus

/** Single source of truth for destination identity colors. UI layers convert these hex values to Color. */
object DestinationColorTokens {
    const val PRICE_TRACE = "#22C55E"
    const val CASH_OS = "#FACC15"
    const val FITNESS = "#7DD3FC"
}

enum class ReviewDestination(val shortLabel: String, val fullLabel: String, val colorHex: String) {
    PRICE_TRACE("PT", "PriceTrace", DestinationColorTokens.PRICE_TRACE),
    CASH_OS("Cash", "CashOS", DestinationColorTokens.CASH_OS),
    FITNESS("Fitness", "Fitness", DestinationColorTokens.FITNESS),
}

enum class ReviewDestinationStatus(val label: String) {
    PLANNED("전송 예정"),
    UNSELECTED("선택 해제"),
    CONDITION_UNMET("조건 미충족"),
    NOT_APPLICABLE("대상 아님"),
}

enum class ReviewEvidenceKind(val label: String) {
    RECEIPT("영수증"),
    NUTRITION_LABEL("영양성분표"),
    FOOD_PHOTO("음식 사진"),
    PRODUCT_PHOTO("상품 사진"),
    ORDER_HISTORY("주문내역"),
    PAYMENT_HISTORY("결제내역"),
    USER_INPUT("사용자 입력"),
}

data class ReviewEvidenceBadge(
    val kind: ReviewEvidenceKind,
    val sourceIds: List<String> = emptyList(),
)

data class ReviewDestinationBadge(
    val destination: ReviewDestination,
    val status: ReviewDestinationStatus,
    val projectionStatuses: List<ProjectionStatus> = emptyList(),
)

/** A display-only row. It never becomes part of a canonical wire model. */
data class ReviewRow(
    val id: String,
    val section: String,
    val item: String,
    val value: String,
    val evidence: List<ReviewEvidenceBadge> = emptyList(),
    val destinations: List<ReviewDestinationBadge> = emptyList(),
    val confidence: String? = null,
    val details: List<ReviewDetail> = emptyList(),
)

data class ReviewDetail(val item: String, val value: String)

data class ReviewViewModel(
    val schema: String,
    val rows: List<ReviewRow>,
    val destinations: List<ReviewDestinationBadge>,
) {
    companion object {
        /** Maps both yeonsik-ocr.v2 and v4 without consulting projection_targets for routing. */
        fun fromCanonical(
            envelope: YeonsikOcrEnvelope,
            session: IngestionSession? = null,
            evidence: List<LocalEvidence> = emptyList(),
            selectedProjections: Set<IngestionProjection>? = null,
        ): ReviewViewModel {
            val plan = CanonicalProjectionPlanner.plan(envelope)
            val destinationBadges = destinationBadgesFor(plan, session, selectedProjections, IngestionProjection.entries.toSet())
            val declaredSourceFiles = envelope.source.sourceFiles.associateBy(SourceAttachment::id)
            // A canonical reference is displayable only when the existing local evidence binding
            // confirms that its attachment is readable. Manual review with no bindings remains honest.
            val sourceFiles = if (evidence.isEmpty()) {
                emptyMap()
            } else {
                evidence.filter(LocalEvidence::fileReadable).flatMap { local ->
                    val attachment = declaredSourceFiles[local.attachmentId] ?: local.pageId?.let(declaredSourceFiles::get)
                    attachment?.let { bound -> listOf(bound.id to bound, local.pageId to bound) }.orEmpty()
                }.filter { it.first != null }.associate { it.first!! to it.second }
            }
            val rows = buildList {
                envelope.receipt?.let { receipt ->
                    val receiptDestinations = destinationBadgesFor(plan, session, selectedProjections, RECEIPT_PROJECTIONS)
                    val receiptEvidence = receiptEvidence(sourceFiles)
                    add("판매처", "판매처명", receipt.merchant.name, receiptEvidence, receiptDestinations)
                    add("판매처", "지점명", receipt.merchant.branchName, receiptEvidence, receiptDestinations)
                    add("문서", "구매일", receipt.document.issuedOn, receiptEvidence, receiptDestinations)
                    add("문서", "통화", receipt.document.currency, receiptEvidence, receiptDestinations)
                    add("금액", "최종 결제금액", receipt.totals.grandTotalAmountMinor?.let { "$it ${receipt.document.currency.orEmpty()}" }, receiptEvidence, receiptDestinations)
                    receipt.lineItems.forEach { line ->
                        add(
                            "상품",
                            line.description ?: "이름 미확인",
                            listOfNotNull(
                                line.quantity?.let { "수량 ${it.value}" },
                                line.netAmountMinor?.let { "금액 $it" },
                            ).joinToString(" · ").ifBlank { "값 미확인" },
                            receiptEvidence(sourceFiles),
                            receiptDestinations,
                            confidence = line.confidence.name,
                        )
                    }
                }
                envelope.merchantCandidate?.let { candidate ->
                    val candidateDestinations = destinationBadgesFor(plan, session, selectedProjections, setOf(IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE))
                    add("판매처 후보", "판매처명", candidate.name, evidenceFor(sourceFiles, candidate.sourceAttachmentIds, envelope.source.userText), candidateDestinations)
                    add("판매처 후보", "지점명", candidate.branchName, evidenceFor(sourceFiles, candidate.sourceAttachmentIds, envelope.source.userText), candidateDestinations)
                    add("판매처 후보", "주소", candidate.address, evidenceFor(sourceFiles, candidate.sourceAttachmentIds, envelope.source.userText), candidateDestinations)
                }
                envelope.productCandidates.forEach { candidate ->
                    val ids = candidate.effectiveSourceAttachmentIds
                    add(
                        "상품 후보",
                        candidate.productName,
                        listOfNotNull(candidate.brand, candidate.specification, candidate.contentAmount?.let { "${it} ${candidate.contentUnit}" }).joinToString(" · ").ifBlank { "값 미확인" },
                        evidenceFor(sourceFiles, ids, envelope.source.userText, candidate.evidence.map { it.sourceType to it.sourceAttachmentIds }),
                        destinationBadgesFor(plan, session, selectedProjections, setOf(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)),
                        confidence = percent(candidate.confidence),
                        details = buildList {
                            candidate.manufacturer?.let { add(ReviewDetail("제조사", it)) }
                            candidate.variant?.let { add(ReviewDetail("variant", it)) }
                            candidate.packageCount?.let { add(ReviewDetail("포장 수량", it.toString())) }
                            if (candidate.barcodes.isNotEmpty()) add(ReviewDetail("바코드", candidate.barcodes.joinToString { "${it.scheme}:${it.value}" }))
                            candidate.merchantSku?.let { add(ReviewDetail("판매자 SKU", it)) }
                        },
                    )
                }
                envelope.priceObservations.forEach { observation ->
                    add(
                        "가격 관측",
                        observation.itemName ?: observation.productClientKey ?: observation.clientKey,
                        listOfNotNull(observation.netAmountMinor?.let { "$it ${observation.currency}" }, observation.observedOn ?: observation.observedAt).joinToString(" · ").ifBlank { "값 미확인" },
                        evidenceFor(sourceFiles, observation.sourceAttachmentIds, envelope.source.userText, observation.evidence.map { it.sourceType to it.sourceAttachmentIds }),
                        destinationBadgesFor(plan, session, selectedProjections, setOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION)),
                        confidence = percent(observation.confidence),
                    )
                }
                envelope.purchaseRecords.forEach { purchase -> addPurchaseRows(this, purchase, sourceFiles, envelope, plan, session, selectedProjections) }
                envelope.nutrition.forEach { nutrition -> addNutritionRow(this, nutrition, sourceFiles, envelope, plan, session, selectedProjections) }
                envelope.consumption.forEach { consumption ->
                    add(
                        "섭취",
                        consumption.clientKey,
                        listOfNotNull(consumption.consumedAt, consumption.status.wireValue).joinToString(" · ").ifBlank { "값 미확인" },
                        evidenceFor(sourceFiles, emptyList(), envelope.source.userText),
                        destinationBadgesFor(plan, session, selectedProjections, setOf(IngestionProjection.FITNESS_MEAL)),
                        details = consumption.items.map { ReviewDetail(it.nutritionClientKey, "${it.amount ?: "미확인"} ${it.unit.orEmpty()} · ${it.amountStatus}") },
                    )
                }
            }.filter { it.value.isNotBlank() }
            return ReviewViewModel(envelope.schemaVersion, rows, destinationBadges)
        }

        /** Used by the legacy receipt.v2 screen; canonical routing remains in Core. */
        fun fromReceipt(receipt: ReceiptV2, verified: Boolean): ReviewViewModel {
            val status = if (verified) ReviewDestinationStatus.PLANNED else ReviewDestinationStatus.CONDITION_UNMET
            val pt = ReviewDestinationBadge(ReviewDestination.PRICE_TRACE, status)
            val cash = ReviewDestinationBadge(ReviewDestination.CASH_OS, status)
            val fitness = ReviewDestinationBadge(ReviewDestination.FITNESS, ReviewDestinationStatus.NOT_APPLICABLE)
            val all = listOf(pt, cash, fitness)
            val receiptDestinations = listOf(pt, cash)
            val evidence = listOf(ReviewEvidenceBadge(ReviewEvidenceKind.RECEIPT))
            val rows = buildList {
                add(ReviewRow("merchant", "판매처", "판매처명", receipt.merchant.name.orEmpty(), evidence, receiptDestinations))
                add(ReviewRow("date", "문서", "구매일", receipt.document.issuedOn.orEmpty(), evidence, receiptDestinations))
                receipt.lineItems.forEach { line ->
                    add(ReviewRow(line.id, "상품", line.description ?: "이름 미확인", line.netAmountMinor?.let { "$it ${receipt.document.currency.orEmpty()}" }.orEmpty(), evidence, receiptDestinations, line.confidence.name))
                }
                add(ReviewRow("total", "금액", "최종 결제금액", receipt.totals.grandTotalAmountMinor?.toString().orEmpty(), evidence, receiptDestinations))
            }.filter { it.value.isNotBlank() }
            return ReviewViewModel(receipt.schemaVersion, rows, all)
        }

        private fun destinationBadgesFor(
            plan: CanonicalProjectionPlan,
            session: IngestionSession?,
            selectedProjections: Set<IngestionProjection>?,
            relevantProjections: Set<IngestionProjection>,
        ): List<ReviewDestinationBadge> = ReviewDestination.entries.mapNotNull { destination ->
                val projections = projectionsFor(destination).intersect(relevantProjections)
                if (projections.isEmpty()) return@mapNotNull null
                val eligible = projections.intersect(plan.eligible)
                val actual = session?.projections.orEmpty().filter { it.projection in projections }
                val status = when {
                    eligible.isEmpty() && actual.any { it.status == ProjectionStatus.BLOCKED } -> ReviewDestinationStatus.CONDITION_UNMET
                    eligible.isEmpty() -> ReviewDestinationStatus.NOT_APPLICABLE
                    actual.any { it.status == ProjectionStatus.BLOCKED } -> ReviewDestinationStatus.CONDITION_UNMET
                    selectedProjections != null && eligible.none { it in selectedProjections } -> ReviewDestinationStatus.UNSELECTED
                    else -> ReviewDestinationStatus.PLANNED
                }
                ReviewDestinationBadge(destination, status, actual.map { it.status })
            }

        private fun projectionsFor(destination: ReviewDestination): Set<IngestionProjection> = when (destination) {
            ReviewDestination.PRICE_TRACE -> setOf(
                IngestionProjection.PRICETRACE_RECEIPT,
                IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
                IngestionProjection.PRICETRACE_MERCHANT_CANDIDATE,
                IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,
            )
            ReviewDestination.CASH_OS -> setOf(IngestionProjection.CASHOS_RECEIPT, IngestionProjection.CASHOS_TRANSACTION)
            ReviewDestination.FITNESS -> setOf(
                IngestionProjection.FITNESS_NUTRITION,
                IngestionProjection.FITNESS_MEAL,
                IngestionProjection.FITNESS_PRODUCT_NUTRITION_LINK,
            )
        }

        private fun addPurchaseRows(
            rows: MutableList<ReviewRow>,
            purchase: PurchaseRecord,
            sourceFiles: Map<String, SourceAttachment>,
            envelope: YeonsikOcrEnvelope,
            plan: CanonicalProjectionPlan,
            session: IngestionSession?,
            selectedProjections: Set<IngestionProjection>?,
        ) {
            val evidence = evidenceFor(sourceFiles, purchase.evidence.flatMap(PurchaseRecordEvidence::sourceAttachmentIds), envelope.source.userText, purchase.evidence.map { it.sourceType to it.sourceAttachmentIds })
            val destinations = destinationBadgesFor(plan, session, selectedProjections, PURCHASE_PROJECTIONS)
            fun addPurchaseField(item: String, value: String?) = rows.add(
                ReviewRow("purchase:${purchase.clientKey}:$item", "구매", item, value.orEmpty(), evidence, destinations, percent(purchase.confidence))
            )
            addPurchaseField("플랫폼", purchase.platform)
            addPurchaseField("판매자", purchase.seller)
            addPurchaseField("주문일·시각", listOfNotNull(purchase.orderedOn?.let { "날짜 $it" }, purchase.orderedAt?.let { "시각 $it" }).joinToString(" · ").ifBlank { null })
            addPurchaseField("결제일·시각", listOfNotNull(purchase.paidOn?.let { "날짜 $it" }, purchase.paidAt?.let { "시각 $it" }).joinToString(" · ").ifBlank { null })
            addPurchaseField("상태", purchase.status.wireValue)
            addPurchaseField("결제금액", purchase.totals.cashOsAmountKrw?.let { "$it KRW" })
            purchase.orderReference?.let { addPurchaseField("주문번호", it) }
            purchase.payment?.method?.let { addPurchaseField("결제수단", it) }
            purchase.payment?.status?.let { addPurchaseField("결제상태", it) }
            purchase.lineItems.forEachIndexed { index, line ->
                rows.add(ReviewRow("purchase:${purchase.clientKey}:line:$index", "구매 상품", line.description.orEmpty(), listOfNotNull(line.quantity?.let { "수량 $it" }, line.netAmountKrw?.let { "$it KRW" }).joinToString(" · "), evidence, destinations, details = buildList {
                    line.optionText?.let { add(ReviewDetail("옵션", it)) }
                    line.merchantSku?.let { add(ReviewDetail("판매자 SKU", it)) }
                    line.priceStatus.let { add(ReviewDetail("가격 상태", it)) }
                    line.grossAmountKrw?.let { add(ReviewDetail("정가", "$it KRW")) }
                    line.discountAmountKrw?.let { add(ReviewDetail("할인", "$it KRW")) }
                }))
            }
        }

        private fun addNutritionRow(
            rows: MutableList<ReviewRow>,
            nutrition: IngestionNutrition,
            sourceFiles: Map<String, SourceAttachment>,
            envelope: YeonsikOcrEnvelope,
            plan: CanonicalProjectionPlan,
            session: IngestionSession?,
            selectedProjections: Set<IngestionProjection>?,
        ) {
            val nutritionData = when (nutrition) {
                is IngestionNutrition.ProductLabel -> NutritionDisplayData(
                    name = nutrition.draft.productName,
                    nutrients = nutrition.draft.nutrients,
                    confidence = nutrition.draft.evidence.values.flatten().mapNotNull { it.confidence?.toDouble() }.averageOrNull(),
                )
                is IngestionNutrition.RestaurantEstimate -> NutritionDisplayData(
                    name = nutrition.menuName,
                    nutrients = nutrition.estimate.nutrients,
                    confidence = nutrition.estimate.confidenceScore,
                    provenance = nutrition.estimate.nutrientProvenance.map { it.key to it.value.sourceType },
                )
                is IngestionNutrition.RestaurantMenuEstimate -> NutritionDisplayData(
                    name = nutrition.menuName,
                    nutrients = nutrition.estimate.nutrients,
                    confidence = nutrition.estimate.confidenceScore,
                    provenance = nutrition.estimate.nutrientProvenance.map { it.key to it.value.sourceType },
                )
                is IngestionNutrition.MealComponentEstimate -> NutritionDisplayData(
                    name = nutrition.menuName,
                    nutrients = nutrition.estimate.nutrients,
                    confidence = nutrition.estimate.confidenceScore,
                    provenance = nutrition.estimate.nutrientProvenance.map { it.key to it.value.sourceType },
                )
            }
            val name = nutritionData.name
            val nutrients = nutritionData.nutrients
            val confidence = nutritionData.confidence
            val provenance = nutritionData.provenance
            val summary = listOfNotNull(
                nutrients[NutritionField.CALORIES_KCAL]?.let { "${number(it)} kcal" },
                nutrients[NutritionField.PROTEIN_GRAMS]?.let { "P ${number(it)}g" },
                nutrients[NutritionField.CARBS_GRAMS]?.let { "C ${number(it)}g" },
                nutrients[NutritionField.FAT_GRAMS]?.let { "F ${number(it)}g" },
            ).joinToString(" · ").ifBlank { "영양 값 미확인" }
            val details = listOfNotNull(
                nutrients[NutritionField.SODIUM_MG]?.let { ReviewDetail("나트륨", "${number(it)} mg") },
                nutrients[NutritionField.SUGARS_GRAMS]?.let { ReviewDetail("당류", "${number(it)} g") },
                nutrients[NutritionField.SATURATED_FAT_GRAMS]?.let { ReviewDetail("포화지방", "${number(it)} g") },
            ) + provenance.map { (field, source) -> ReviewDetail("provenance ${field.koreanLabel}", source) }
            val nutritionEvidenceIds = when (nutrition) {
                is IngestionNutrition.ProductLabel -> nutrition.draft.evidence.values.flatten().map { it.pageId }
                is IngestionNutrition.RestaurantEstimate -> nutrition.estimate.nutrientProvenance.values.flatMap { it.evidenceRefs }
                is IngestionNutrition.RestaurantMenuEstimate -> nutrition.estimate.nutrientProvenance.values.flatMap { it.evidenceRefs }
                is IngestionNutrition.MealComponentEstimate -> nutrition.estimate.nutrientProvenance.values.flatMap { it.evidenceRefs }
            }.filter { it in sourceFiles }.distinct()
            val typedNutritionEvidence = when (nutrition) {
                is IngestionNutrition.ProductLabel -> emptyList()
                is IngestionNutrition.RestaurantEstimate -> nutrition.estimate.nutrientProvenance.values.mapNotNull { it.sourceType to it.evidenceRefs.filter { ref -> ref in sourceFiles } }.filter { it.second.isNotEmpty() }
                is IngestionNutrition.RestaurantMenuEstimate -> nutrition.estimate.nutrientProvenance.values.mapNotNull { it.sourceType to it.evidenceRefs.filter { ref -> ref in sourceFiles } }.filter { it.second.isNotEmpty() }
                is IngestionNutrition.MealComponentEstimate -> nutrition.estimate.nutrientProvenance.values.mapNotNull { it.sourceType to it.evidenceRefs.filter { ref -> ref in sourceFiles } }.filter { it.second.isNotEmpty() }
            }
            rows.add(ReviewRow("nutrition:${nutrition.clientKey}", "영양", name, summary, evidenceFor(sourceFiles, nutritionEvidenceIds, envelope.source.userText, typedNutritionEvidence), destinationBadgesFor(plan, session, selectedProjections, setOf(IngestionProjection.FITNESS_NUTRITION)), confidence?.let(::percent), details))
        }

        private val RECEIPT_PROJECTIONS = setOf(
            IngestionProjection.PRICETRACE_RECEIPT,
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            IngestionProjection.CASHOS_RECEIPT,
        )
        private val PURCHASE_PROJECTIONS = setOf(
            IngestionProjection.PRICETRACE_PRICE_OBSERVATION,
            IngestionProjection.CASHOS_TRANSACTION,
        )

        private data class NutritionDisplayData(
            val name: String,
            val nutrients: Map<NutritionField, Double?>,
            val confidence: Double?,
            val provenance: List<Pair<NutritionField, String>> = emptyList(),
        )
        private fun List<Double>.averageOrNull(): Double? = takeIf { isNotEmpty() }?.average()
        private fun percent(value: Double): String = "${(value * 100).toInt()}%"
        private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        private fun MutableList<ReviewRow>.add(section: String, item: String, value: String?, evidence: List<ReviewEvidenceBadge>, destinations: List<ReviewDestinationBadge>, confidence: String? = null, details: List<ReviewDetail> = emptyList()) {
            add(ReviewRow("$section:$item", section, item, value.orEmpty(), evidence, destinations, confidence, details))
        }

        private fun receiptEvidence(sourceFiles: Map<String, SourceAttachment>): List<ReviewEvidenceBadge> =
            evidenceFor(sourceFiles, sourceFiles.values.filter { it.type == SourceAttachmentType.RECEIPT }.map(SourceAttachment::id), null)

        private fun evidenceFor(
            sourceFiles: Map<String, SourceAttachment>,
            ids: List<String>,
            userText: String?,
            typed: List<Pair<String, List<String>>> = emptyList(),
        ): List<ReviewEvidenceBadge> {
            val result = linkedMapOf<ReviewEvidenceKind, MutableList<String>>()
            fun add(kind: ReviewEvidenceKind, sourceIds: List<String> = emptyList()) { result.getOrPut(kind) { mutableListOf() }.addAll(sourceIds) }
            ids.distinct().mapNotNull { sourceFiles[it] }.forEach { attachment -> add(attachment.type.toEvidenceKind(), listOf(attachment.id)) }
            typed.forEach { (sourceType, sourceIds) ->
                when (sourceType) {
                    "user_statement" -> add(ReviewEvidenceKind.USER_INPUT)
                    "order_history" -> add(ReviewEvidenceKind.ORDER_HISTORY, sourceIds)
                    "payment_history" -> add(ReviewEvidenceKind.PAYMENT_HISTORY, sourceIds)
                    "food_photo", "menu_photo" -> add(ReviewEvidenceKind.FOOD_PHOTO, sourceIds)
                    "product_photo", "package_label" -> add(ReviewEvidenceKind.PRODUCT_PHOTO, sourceIds)
                    "nutrition_label" -> add(ReviewEvidenceKind.NUTRITION_LABEL, sourceIds)
                    "food_image_estimate" -> add(ReviewEvidenceKind.FOOD_PHOTO, sourceIds)
                    "receipt", "ocr" -> add(ReviewEvidenceKind.RECEIPT, sourceIds)
                }
            }
            if (!userText.isNullOrBlank()) add(ReviewEvidenceKind.USER_INPUT)
            return result.map { (kind, sourceIds) -> ReviewEvidenceBadge(kind, sourceIds.distinct()) }
        }

        private fun SourceAttachmentType.toEvidenceKind(): ReviewEvidenceKind = when (this) {
            SourceAttachmentType.RECEIPT -> ReviewEvidenceKind.RECEIPT
            SourceAttachmentType.FOOD_PHOTO, SourceAttachmentType.MENU_PHOTO -> ReviewEvidenceKind.FOOD_PHOTO
            SourceAttachmentType.PRODUCT_PHOTO -> ReviewEvidenceKind.PRODUCT_PHOTO
            SourceAttachmentType.NUTRITION_LABEL -> ReviewEvidenceKind.NUTRITION_LABEL
            SourceAttachmentType.ORDER_HISTORY -> ReviewEvidenceKind.ORDER_HISTORY
            SourceAttachmentType.PAYMENT_HISTORY -> ReviewEvidenceKind.PAYMENT_HISTORY
        }
    }
}
