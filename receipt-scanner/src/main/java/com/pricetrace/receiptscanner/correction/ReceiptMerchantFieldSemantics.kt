package com.pricetrace.receiptscanner.correction

import com.pricetrace.receiptscanner.domain.ReceiptV2
import com.pricetrace.receiptscanner.ocr.OcrLine
import java.util.Locale

object ReceiptMerchantFieldSemantics {
    const val MERCHANT_NAME = "merchant.name"
    const val BRANCH_NAME = "merchant.branch_name"
    const val BUSINESS_REGISTRATION_NUMBER = "merchant.business_registration_number"
    const val ADDRESS = "merchant.address"
    const val PHONE = "merchant.phone"

    val FIELD_PATHS: List<String> = listOf(
        MERCHANT_NAME,
        BRANCH_NAME,
        BUSINESS_REGISTRATION_NUMBER,
        ADDRESS,
        PHONE,
    )

    private val phoneNumber = Regex(
        """(?<!\d)0\d{1,2}[- )]?\d{3,4}[- ]?\d{4}(?!\d)""",
    )
    private val businessNumber = Regex(
        """(?<!\d)\d{3}[- ]?\d{2}[- ]?\d{5}(?!\d)""",
    )
    private val email = Regex(
        """[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""",
        RegexOption.IGNORE_CASE,
    )
    private val merchantLabel = Regex(
        """(?:상호명?|가게명|가맹점명?|판매처|판매점|매장명?|상점명?|merchant|store)""",
        RegexOption.IGNORE_CASE,
    )
    private val branchLabel = Regex(
        """(?:지점명?|점포명?|branch)""",
        RegexOption.IGNORE_CASE,
    )
    private val businessLabel = Regex(
        """사업자\s*(?:등록)?\s*번호""",
        RegexOption.IGNORE_CASE,
    )
    private val addressLabel = Regex(
        """(?:주소|소재지|도로명주소|address)""",
        RegexOption.IGNORE_CASE,
    )
    private val phoneLabel = Regex(
        """(?:전화|연락처|휴대전화|휴대폰|전화번호|TEL|FAX|phone)""",
        RegexOption.IGNORE_CASE,
    )
    private val personLabel = Regex(
        """(?:대표자|담당자|성명|이름|고객명|수령인|받는\s*분|person)""",
        RegexOption.IGNORE_CASE,
    )
    private val blockedEvidence = Regex(
        """(?:카드|승인\s*번호|거래\s*번호|영수증\s*번호|회원\s*(?:번호|ID|아이디)|고객\s*(?:번호|ID|아이디)|현금영수증|주민\s*(?:등록)?\s*번호|이메일|E-MAIL|결제\s*금액|합계\s*금액|부가세|쿠폰|할인|거스름돈|잔액)""",
        RegexOption.IGNORE_CASE,
    )
    private val addressMarker = Regex(
        """(?:특별시|광역시|특별자치시|특별자치도|[가-힣]{2,4}도)\s+|(?:[가-힣0-9]+(?:시|군|구|읍|면|동))\s+|(?:로|길)\s*\d+""",
    )
    private val normalizedSeparators = Regex("""[^\p{L}\p{N}]""")

    fun currentValue(receipt: ReceiptV2, fieldPath: String): String? = when (fieldPath) {
        MERCHANT_NAME -> receipt.merchant.name
        BRANCH_NAME -> receipt.merchant.branchName
        BUSINESS_REGISTRATION_NUMBER -> receipt.merchant.businessRegistrationNumber
        ADDRESS -> receipt.merchant.address
        PHONE -> receipt.merchant.phone
        else -> null
    }

    fun sourceLineIds(
        fieldPath: String,
        currentValue: String?,
        lines: List<OcrLine>,
    ): List<String> {
        val sortedLines = lines.sortedWith(compareBy({ it.pageIndex }, { it.recognitionOrder }))
        val selected = linkedSetOf<String>()
        sortedLines.forEachIndexed { index, line ->
            if (!isPotentialEvidence(line.text)) return@forEachIndexed
            val labelMatch = isFieldLabel(fieldPath, line.text)
            val valueMatch = containsNormalized(line.text, currentValue)
            val patternMatch = matchesFieldPattern(fieldPath, line.text)
            if (labelMatch || valueMatch || patternMatch) {
                selected += line.id
                if (labelMatch && !patternMatch && !valueMatch) {
                    sortedLines.getOrNull(index + 1)
                        ?.takeIf { it.pageIndex == line.pageIndex && isPotentialEvidence(it.text) }
                        ?.let { selected += it.id }
                }
            }
        }
        return selected.toList()
    }

    fun isMerchantFieldPath(fieldPath: String): Boolean = fieldPath in FIELD_PATHS

    fun isPotentialEvidence(text: String): Boolean = text.isNotBlank() &&
        !blockedEvidence.containsMatchIn(text) &&
        !email.containsMatchIn(text)

    fun isRelevantSourceLine(
        fieldPath: String,
        text: String,
        currentValue: String?,
        proposedValue: String?,
    ): Boolean {
        if (!isPotentialEvidence(text)) return false
        return when (fieldPath) {
            MERCHANT_NAME, BRANCH_NAME ->
                (isFieldLabel(fieldPath, text) ||
                    containsNormalized(text, currentValue) ||
                    containsNormalized(text, proposedValue)) &&
                    !phoneNumber.containsMatchIn(text) &&
                    !businessNumber.containsMatchIn(text) &&
                    !isAddressLike(text) &&
                    !personLabel.containsMatchIn(text)
            BUSINESS_REGISTRATION_NUMBER ->
                businessLabel.containsMatchIn(text) || businessNumber.containsMatchIn(text)
            ADDRESS ->
                isAddressLike(text) &&
                    !phoneNumber.containsMatchIn(text) &&
                    !businessNumber.containsMatchIn(text) &&
                    !personLabel.containsMatchIn(text)
            PHONE ->
                phoneLabel.containsMatchIn(text) || phoneNumber.containsMatchIn(text)
            else -> false
        }
    }

    fun isValidProposedValue(fieldPath: String, proposedValue: String): Boolean {
        val value = proposedValue.trim()
        if (value.isEmpty() || value.length > 160 || '\n' in value || '\r' in value) return false
        return when (fieldPath) {
            MERCHANT_NAME, BRANCH_NAME ->
                value.length >= 2 &&
                    !phoneNumber.containsMatchIn(value) &&
                    !businessNumber.containsMatchIn(value) &&
                    !isAddressLike(value) &&
                    !personLabel.containsMatchIn(value)
            BUSINESS_REGISTRATION_NUMBER -> businessNumber.matches(value)
            ADDRESS ->
                isAddressLike(value) &&
                    !phoneNumber.containsMatchIn(value) &&
                    !businessNumber.containsMatchIn(value) &&
                    !personLabel.containsMatchIn(value)
            PHONE -> phoneNumber.matches(value)
            else -> false
        }
    }

    private fun isFieldLabel(fieldPath: String, text: String): Boolean = when (fieldPath) {
        MERCHANT_NAME -> merchantLabel.containsMatchIn(text)
        BRANCH_NAME -> branchLabel.containsMatchIn(text)
        BUSINESS_REGISTRATION_NUMBER -> businessLabel.containsMatchIn(text)
        ADDRESS -> addressLabel.containsMatchIn(text)
        PHONE -> phoneLabel.containsMatchIn(text)
        else -> false
    }

    private fun matchesFieldPattern(fieldPath: String, text: String): Boolean = when (fieldPath) {
        BUSINESS_REGISTRATION_NUMBER -> businessNumber.containsMatchIn(text)
        ADDRESS -> isAddressLike(text)
        PHONE -> phoneNumber.containsMatchIn(text)
        else -> false
    }

    private fun isAddressLike(text: String): Boolean =
        addressMarker.containsMatchIn(text) &&
            !personLabel.containsMatchIn(text)

    private fun containsNormalized(text: String, value: String?): Boolean {
        val needle = value.normalized()
        return needle.length >= 2 && text.normalized().contains(needle)
    }

    private fun String?.normalized(): String =
        this.orEmpty().lowercase(Locale.ROOT).replace(normalizedSeparators, "")
}
