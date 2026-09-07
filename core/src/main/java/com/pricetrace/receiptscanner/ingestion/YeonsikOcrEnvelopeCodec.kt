package com.pricetrace.receiptscanner.ingestion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Version-dispatching codec. Existing v1 documents are decoded and encoded by the v1 codec unchanged. */
object YeonsikOcrEnvelopeCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun decode(
        value: String,
        localDocumentId: String,
        preservePersistedVerification: Boolean = false,
    ): YeonsikOcrEnvelope {
        val schema = json.parseToJsonElement(value).jsonObject["schema_version"]
            ?.jsonPrimitive?.content
            ?: error("schema_version is required")
        return when (schema) {
            YEONSIK_OCR_SCHEMA -> YeonsikOcrEnvelopeJson.decode(
                value = value,
                localDocumentId = localDocumentId,
                preservePersistedVerification = preservePersistedVerification,
            )
            YEONSIK_OCR_V2_SCHEMA -> YeonsikOcrV2Json.decode(
                value = value,
                localDocumentId = localDocumentId,
                preservePersistedVerification = preservePersistedVerification,
            )
            else -> error("Unsupported yeonsik OCR schema: $schema")
        }
    }

    fun encode(envelope: YeonsikOcrEnvelope, canonicalIds: Boolean = false): String = when (envelope.schemaVersion) {
        YEONSIK_OCR_SCHEMA -> YeonsikOcrEnvelopeJson.encode(envelope, canonicalIds)
        YEONSIK_OCR_V2_SCHEMA -> YeonsikOcrV2Json.encode(envelope, canonicalIds)
        else -> error("Unsupported yeonsik OCR schema: ${envelope.schemaVersion}")
    }

    fun canonicalize(envelope: YeonsikOcrEnvelope): String = when (envelope.schemaVersion) {
        YEONSIK_OCR_SCHEMA -> YeonsikOcrEnvelopeJson.canonicalize(envelope)
        YEONSIK_OCR_V2_SCHEMA -> YeonsikOcrV2Json.canonicalize(envelope)
        else -> error("Unsupported yeonsik OCR schema: ${envelope.schemaVersion}")
    }
}
