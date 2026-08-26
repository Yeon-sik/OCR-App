package com.pricetrace.receiptscanner.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptMerchantFieldSemanticsTest {
    @Test
    fun addressRejectsStoreNamePersonLabelPhoneAndBusinessNumber() {
        assertTrue(
            ReceiptMerchantFieldSemantics.isValidProposedValue(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "서울특별시 강남구 테헤란로 1",
            ),
        )
        assertFalse(
            ReceiptMerchantFieldSemantics.isValidProposedValue(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "가상마트 서울점",
            ),
        )
        assertFalse(
            ReceiptMerchantFieldSemantics.isValidProposedValue(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "대표자 홍길동",
            ),
        )
        assertFalse(
            ReceiptMerchantFieldSemantics.isValidProposedValue(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "010-1234-5678",
            ),
        )
        assertFalse(
            ReceiptMerchantFieldSemantics.isValidProposedValue(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "123-45-67890",
            ),
        )
    }

    @Test
    fun sourceLineMustHaveExpectedFieldType() {
        assertTrue(
            ReceiptMerchantFieldSemantics.isRelevantSourceLine(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "서울특별시 강남구 테헤란로 1",
                currentValue = null,
                proposedValue = null,
            ),
        )
        assertFalse(
            ReceiptMerchantFieldSemantics.isRelevantSourceLine(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "가상마트 서울점",
                currentValue = null,
                proposedValue = null,
            ),
        )
        assertFalse(
            ReceiptMerchantFieldSemantics.isRelevantSourceLine(
                ReceiptMerchantFieldSemantics.ADDRESS,
                "전화 010-1234-5678",
                currentValue = null,
                proposedValue = null,
            ),
        )
    }
}
