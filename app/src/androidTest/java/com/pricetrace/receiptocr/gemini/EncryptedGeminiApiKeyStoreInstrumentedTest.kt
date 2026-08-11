package com.pricetrace.receiptocr.gemini

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedGeminiApiKeyStoreInstrumentedTest {
    @Test
    fun keyCanBeSavedReadAndClearedWithAnIsolatedAlias() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "gemini_api_credentials_test_$suffix"
        val store = EncryptedGeminiApiKeyStore(
            context = context,
            keyAlias = "pricetrace_gemini_api_key_test_$suffix",
            preferencesName = preferencesName,
        )
        try {
            assertFalse(store.save("short"))
            assertTrue(store.save(TEST_API_KEY))
            assertTrue(store.isConfigured())
            assertEquals(TEST_API_KEY, store.read())

            store.clear()

            assertFalse(store.isConfigured())
            assertNull(store.read())
        } finally {
            store.clear()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    private companion object {
        const val TEST_API_KEY = "instrumented-test-key-with-more-than-twenty-characters"
    }
}
