package com.pricetrace.receiptscanner.correction

import com.pricetrace.receiptscanner.domain.ConfidenceLevel
import com.pricetrace.receiptscanner.domain.ReceiptLineType
import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.domain.isUserEntered
import com.pricetrace.receiptscanner.ocr.OcrDocument

data class ReceiptCorrectionProvider(
    val id: String,
    val displayName: String,
    val model: String,
    val isAvailable: Boolean,
    val unavailableReason: String? = null,
)

data class ReceiptCorrectionTarget(
    val lineItemId: String,
    val description: String?,
    val quantity: String?,
    val unitPriceAmountMinor: Long?,
    val netAmountMinor: Long?,
    val sourceLineIds: List<String>,
)

data class ReceiptCorrectionEvidenceLine(
    val id: String,
    val pageIndex: Int,
    val text: String,
)

data class ReceiptCorrectionEvidenceImage(
    val id: String,
    val mimeType: String,
    val bytes: ByteArray,
    val sourceLineIds: List<String>,
)

data class ReceiptCorrectionRequest(
    val documentId: String,
    val targets: List<ReceiptCorrectionTarget>,
    val evidenceLines: List<ReceiptCorrectionEvidenceLine>,
    val evidenceImages: List<ReceiptCorrectionEvidenceImage> = emptyList(),
)

data class ReceiptCorrectionCandidate(
    val id: String,
    val fieldPath: String,
    val oldValue: String?,
    val proposedValue: String,
    val sourceLineIds: List<String>,
    val confidencePercent: Int,
    val reason: String,
    val providerId: String,
    val model: String,
    val promptVersion: String,
)

data class ReceiptCorrectionBatch(
    val candidates: List<ReceiptCorrectionCandidate>,
    val providerId: String,
    val model: String,
    val promptVersion: String,
)

enum class ReceiptCorrectionFailureReason {
    NOT_CONFIGURED,
    NO_ELIGIBLE_EVIDENCE,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    PROVIDER,
    INVALID_RESPONSE,
}

sealed interface ReceiptCorrectionOutcome {
    data class Success(val batch: ReceiptCorrectionBatch) : ReceiptCorrectionOutcome
    data class Failure(
        val reason: ReceiptCorrectionFailureReason,
        val safeDetail: String? = null,
    ) : ReceiptCorrectionOutcome
}

interface ReceiptCorrectionSuggester {
    val provider: ReceiptCorrectionProvider
    suspend fun suggest(request: ReceiptCorrectionRequest): ReceiptCorrectionOutcome
}

class UnavailableReceiptCorrectionSuggester(
    reason: String = "Gemini API 키를 먼저 저장하세요.",
) : ReceiptCorrectionSuggester {
    override val provider = ReceiptCorrectionProvider(
        id = "gemini-api-direct",
        displayName = "Gemini API 직접 연결",
        model = "gemini-3.5-flash-lite",
        isAvailable = false,
        unavailableReason = reason,
    )

    override suspend fun suggest(request: ReceiptCorrectionRequest): ReceiptCorrectionOutcome =
        ReceiptCorrectionOutcome.Failure(ReceiptCorrectionFailureReason.NOT_CONFIGURED)
}

object ReceiptCorrectionRequestFactory {
    const val MAX_TARGETS = 12

    fun create(receipt: ReceiptV2, ocrDocument: OcrDocument): ReceiptCorrectionRequest {
        val knownLines = ocrDocument.lines
            .asSequence()
            .filterNot { SensitiveReceiptEvidenceFilter.isSensitive(it.text) }
            .associateBy { it.id }
        val targets = receipt.lineItems
            .asSequence()
            .filterNot { it.isUserEntered() }
            .filter { it.type == ReceiptLineType.PRODUCT }
            .filterNot { it.confidence == ConfidenceLevel.USER_VERIFIED }
            .filterNot { item -> item.description?.let(SensitiveReceiptEvidenceFilter::isSensitive) == true }
            .filter { item -> item.sourceLineReferences.any(knownLines::containsKey) }
            .sortedBy { item ->
                when (item.confidence) {
                    ConfidenceLevel.LOW -> 0
                    ConfidenceLevel.MEDIUM -> 1
                    ConfidenceLevel.HIGH -> 2
                    ConfidenceLevel.USER_VERIFIED -> 3
                }
            }
            .take(MAX_TARGETS)
            .map { item ->
                ReceiptCorrectionTarget(
                    lineItemId = item.id,
                    description = item.description,
                    quantity = item.quantity?.value,
                    unitPriceAmountMinor = item.unitPriceAmountMinor,
                    netAmountMinor = item.netAmountMinor,
                    sourceLineIds = item.sourceLineReferences.filter(knownLines::containsKey).distinct(),
                )
            }
            .toList()
        val evidenceIds = targets.flatMap(ReceiptCorrectionTarget::sourceLineIds).toSet()
        val evidenceLines = ocrDocument.lines
            .asSequence()
            .filter { it.id in evidenceIds }
            .sortedWith(compareBy({ it.pageIndex }, { it.recognitionOrder }))
            .map { line ->
                ReceiptCorrectionEvidenceLine(
                    id = line.id,
                    pageIndex = line.pageIndex,
                    text = line.text,
                )
            }
            .toList()
        return ReceiptCorrectionRequest(
            documentId = receipt.document.id,
            targets = targets,
            evidenceLines = evidenceLines,
        )
    }
}

private object SensitiveReceiptEvidenceFilter {
    private val sensitiveLabel = Regex(
        pattern = """(?:사업자\s*(?:등록)?\s*번호|대표자|주소|소재지|전화|TEL|FAX|카드\s*(?:번호|결제|승인)|승인\s*번호|거래\s*번호|영수증\s*번호|회원\s*(?:번호|ID|아이디)|고객\s*(?:번호|ID|아이디)|현금영수증|주민\s*(?:등록)?\s*번호|이메일|E-MAIL)""",
        option = RegexOption.IGNORE_CASE,
    )
    private val businessNumber = Regex("""(?<!\d)\d{3}[- ]\d{2}[- ]\d{5}(?!\d)""")
    private val phoneNumber = Regex("""(?<!\d)0\d{1,2}[- )]?\d{3,4}[- ]?\d{4}(?!\d)""")
    private val email = Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
    private val cardNumber = Regex("""(?<!\d)(?:[\d*]{4}[- *]){2,3}[\d*]{2,4}(?!\d)""")
    private val koreanAddress = Regex(
        """(?:특별시|광역시|특별자치시|특별자치도|[가-힣]{2,4}도)\s+[가-힣0-9]+(?:시|군|구)(?:\s+[가-힣0-9]+(?:구|읍|면|동))?\s+[가-힣0-9·.-]+(?:로|길)\s*\d+""",
    )

    fun isSensitive(text: String): Boolean = sensitiveLabel.containsMatchIn(text) ||
        businessNumber.containsMatchIn(text) ||
        phoneNumber.containsMatchIn(text) ||
        email.containsMatchIn(text) ||
        cardNumber.containsMatchIn(text) ||
        koreanAddress.containsMatchIn(text)
}
