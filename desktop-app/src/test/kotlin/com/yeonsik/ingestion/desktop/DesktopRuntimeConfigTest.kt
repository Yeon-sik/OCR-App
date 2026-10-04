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

    @Test
    fun evidencePasswordBootstrapIgnoresIndependentlyConfiguredSessionFields() {
        val config = DesktopRuntimeConfig.load(
            environment = mapOf(
                "EVIDENCE_SUPABASE_URL" to "https://evidence.example",
                "EVIDENCE_SUPABASE_PUBLISHABLE_KEY" to "sb_publishable_12345678901234567890",
                "EVIDENCE_EMAIL" to "owner@example.com",
                "EVIDENCE_PASSWORD" to "correct-horse-battery",
                "EVIDENCE_USER_ID" to "stale-user",
                "EVIDENCE_ACCESS_TOKEN" to "stale-access-token",
                "EVIDENCE_REFRESH_TOKEN" to "stale-refresh-token",
            ),
            envFile = Files.createTempDirectory("yeonsik-evidence-config").resolve("empty.env"),
        )

        assertEquals("owner@example.com", config.evidence.email)
        assertEquals("correct-horse-battery", config.evidence.password)
        assertEquals("", config.evidence.userId)
        assertEquals("", config.evidence.accessToken)
        assertEquals("", config.evidence.refreshToken)
    }

    @Test
    fun legacyEvidenceTokenBootstrapRemainsAvailableWithoutEmailAndPassword() {
        val config = DesktopRuntimeConfig.load(
            environment = mapOf(
                "EVIDENCE_USER_ID" to "legacy-user",
                "EVIDENCE_ACCESS_TOKEN" to "legacy-access-token",
                "EVIDENCE_REFRESH_TOKEN" to "legacy-refresh-token",
            ),
            envFile = Files.createTempDirectory("yeonsik-evidence-legacy").resolve("empty.env"),
        )

        assertEquals("legacy-user", config.evidence.userId)
        assertEquals("legacy-access-token", config.evidence.accessToken)
        assertEquals("legacy-refresh-token", config.evidence.refreshToken)
    }
}
