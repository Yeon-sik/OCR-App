package com.pricetrace.receiptscanner.publisher

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOnlyReceiptPublisherTest {
    @Test
    fun `local publisher never claims a remote stage`() = runTest {
        val status = LocalOnlyReceiptPublisher().getPublicationStatus("receipt_1")
        assertEquals(PublicationState.LOCAL_ONLY, status.state)
    }
}
