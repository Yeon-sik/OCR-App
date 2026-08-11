package com.pricetrace.receiptscanner.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReceiptFileStoreInstrumentedTest {
    @Test
    fun failedOverwritePreservesPreviousArtifactAndCleansTemps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileStore = ReceiptFileStore(context)
        val documentId = "atomic-${UUID.randomUUID()}"
        val storageKey = "$documentId/draft/receipt.json"
        val destination = fileStore.resolveStorageKey(storageKey)
        val parent = requireNotNull(destination.parentFile)

        try {
            fileStore.writeText(storageKey, """{"version":1}""")

            val failedWrite = runCatching {
                fileStore.writeTextForTesting(storageKey, """{"version":2}""") {
                    error("simulated commit failure")
                }
            }

            assertTrue(failedWrite.exceptionOrNull() is IllegalStateException)
            assertEquals("""{"version":1}""", fileStore.readBytes(storageKey).toString(Charsets.UTF_8))
            assertFalse(java.io.File(parent, "${destination.name}.new").exists())
            assertFalse(java.io.File(parent, "${destination.name}.bak").exists())
            assertFalse(java.io.File(parent, ".${destination.name}.tmp").exists())
        } finally {
            fileStore.deleteDocumentFiles(documentId)
        }
    }
}
