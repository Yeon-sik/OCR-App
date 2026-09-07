package com.yeonsik.ingestion.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopRuntimeConfigTest {
    @Test
    fun `external env file is loaded and process environment wins`() {
        val envFile = Files.createTempFile("yeonsik-console", ".env")
        Files.writeString(
            envFile,
            "PRICETRACE_SUPABASE_URL=https://file.example\n" +
                "PRICETRACE_SUPABASE_PUBLISHABLE_KEY=file-key\n" +
                "PRICETRACE_PASSWORD=do-not-log\n" +
                "NUTRITION_SUPABASE_ANON_KEY=nutrition-key\n",
        )

        val config = DesktopRuntimeConfig.load(
            environment = mapOf(
                "PRICETRACE_SUPABASE_URL" to "https://environment.example",
                "CASHOS_SUPABASE_URL" to "https://cashos.example",
            ),
            envFile = envFile,
        )

        assertEquals("https://environment.example", config.priceTrace.url)
        assertEquals("file-key", config.priceTrace.publishableKey)
        assertEquals("nutrition-key", config.nutrition.publishableKey)
        assertEquals("https://cashos.example", config.cashOs.url)
        assertFalse(config.toString().contains("do-not-log"))
        assertTrue(config.envFile == envFile)
    }
}
