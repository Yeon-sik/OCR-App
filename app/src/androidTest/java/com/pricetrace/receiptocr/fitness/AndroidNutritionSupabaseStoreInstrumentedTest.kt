package com.pricetrace.receiptocr.fitness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidNutritionSupabaseStoreInstrumentedTest {
    @Test
    fun buildDefaultsAreVisibleBeforeTheUserSavesAConnection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val connectionPreferences = "nutrition_connection_defaults_test_$suffix"
        val sessionPreferences = "nutrition_session_defaults_test_$suffix"
        val store = AndroidNutritionSupabaseStore(
            context = context,
            preferencesName = connectionPreferences,
            keyAlias = "nutrition_session_defaults_key_test_$suffix",
            sessionPreferencesName = sessionPreferences,
            defaultUrl = "https://nutrition.example.com",
            defaultPublishableKey = "publishable-key-with-safe-length",
        )
        try {
            val restored = store.read()
            assertEquals("https://nutrition.example.com", restored.url)
            assertEquals("publishable-key-with-safe-length", restored.publishableKey)
            assertTrue(restored.isConnectionConfigured)
        } finally {
            store.clearSession()
            context.deleteSharedPreferences(connectionPreferences)
            context.deleteSharedPreferences(sessionPreferences)
        }
    }

    @Test
    fun sessionTokensRoundTripEncryptedAndPasswordIsNeverAcceptedByTheStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val connectionPreferences = "nutrition_connection_test_$suffix"
        val sessionPreferences = "nutrition_session_test_$suffix"
        val store = AndroidNutritionSupabaseStore(
            context = context,
            preferencesName = connectionPreferences,
            keyAlias = "nutrition_session_key_test_$suffix",
            sessionPreferencesName = sessionPreferences,
        )
        try {
            assertTrue(
                store.saveConnection(
                    "https://nutrition.example.com",
                    "publishable-key-with-safe-length",
                ).isSuccess,
            )
            assertTrue(
                store.saveSession(
                    userId = "user-1",
                    email = "fit@example.com",
                    accessToken = "access-token-private",
                    refreshToken = "refresh-token-private",
                ).isSuccess,
            )

            val restored = store.read()
            assertEquals("access-token-private", restored.accessToken)
            assertEquals("refresh-token-private", restored.refreshToken)
            val storedCiphertext = context.getSharedPreferences(sessionPreferences, Context.MODE_PRIVATE)
                .all.values.joinToString("|")
            assertFalse(storedCiphertext.contains("access-token-private"))
            assertFalse(storedCiphertext.contains("refresh-token-private"))
            assertFalse(storedCiphertext.contains("password"))
        } finally {
            store.clearSession()
            context.deleteSharedPreferences(connectionPreferences)
            context.deleteSharedPreferences(sessionPreferences)
        }
    }
}
