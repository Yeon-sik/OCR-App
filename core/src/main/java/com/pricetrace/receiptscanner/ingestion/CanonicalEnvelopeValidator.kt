package com.pricetrace.receiptscanner.ingestion

/** Core-owned domain validation shared by Android, Desktop, and direct orchestrator callers. */
object CanonicalEnvelopeValidator {
    fun validate(envelope: YeonsikOcrEnvelope): List<String> = when (envelope.schemaVersion) {
        YEONSIK_OCR_V3_SCHEMA -> runCatching { YeonsikOcrV3Json.validate(envelope) }
            .exceptionOrNull()
            ?.let { listOf("canonical_domain_invalid: ${it.message ?: "invalid v3 envelope"}") }
            .orEmpty()
        else -> emptyList()
    }
}
