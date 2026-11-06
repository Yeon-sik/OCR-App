package com.pricetrace.receiptscanner.information

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.Instant

enum class InformationDeliveryState { SENDING, DELIVERED, RETRY_REQUIRED }
data class InformationDeliveryAttempt(
    val recordId: String, val fingerprint: String, val target: InformationDeliveryTarget,
    val state: InformationDeliveryState, val attemptedAt: String, val message: String,
)

/** Durable, credential-free receipts. A restarted SENDING attempt remains visibly unresolved. */
class InformationDeliveryJournal(private val directory: File) {
    init { check(directory.mkdirs() || directory.isDirectory) }

    fun attempts(recordId: String): List<InformationDeliveryAttempt> = locked {
        validId(recordId)
        directory.listFiles { file -> file.name.startsWith("$recordId-") && file.extension == "json" }.orEmpty()
            .map { decode(it.readText(Charsets.UTF_8)) }.sortedByDescending { it.attemptedAt }
    }

    suspend fun deliver(record: InformationRecord, prepared: PreparedInformationDelivery, gateway: InformationDeliveryGateway): InformationDeliveryReceipt {
        require(record.id == prepared.recordId && record.fingerprint == prepared.fingerprint && record.owner == prepared.target.owner)
        val started = InformationDeliveryAttempt(record.id, record.fingerprint, prepared.target, InformationDeliveryState.SENDING,
            Instant.now().toString(), "응답 확인 전입니다. 중단됐다면 같은 기록으로 재시도하세요.")
        write(started)
        return try {
            val receipt = gateway.deliver(record, prepared)
            write(started.copy(state = InformationDeliveryState.DELIVERED, message = "검토함 도착 확인. 실제 기록 반영은 수신 앱에서 승인하세요."))
            receipt
        } catch (error: Exception) {
            write(started.copy(state = InformationDeliveryState.RETRY_REQUIRED, message = "완료를 확인하지 못했습니다. 수신 계정을 확인하고 같은 기록으로 재시도하세요."))
            if (error is CancellationException) throw error
            throw error
        }
    }

    private fun write(attempt: InformationDeliveryAttempt) = locked {
        validId(attempt.recordId)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(
            "${attempt.target.owner.wire}\n${attempt.target.origin}\n${attempt.target.userId}".toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        val target = File(directory, "${attempt.recordId}-$key.json").toPath()
        val temporary = Files.createTempFile(directory.toPath(), ".delivery-", ".tmp")
        try {
            Files.write(temporary, encode(attempt).toByteArray(Charsets.UTF_8))
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun encode(value: InformationDeliveryAttempt): String = buildJsonObject {
        put("record_id", value.recordId); put("fingerprint", value.fingerprint)
        put("owner_app", value.target.owner.wire); put("origin", value.target.origin)
        put("user_id", value.target.userId); put("email", value.target.email)
        put("state", value.state.name); put("attempted_at", value.attemptedAt); put("message", value.message)
    }.toString()
    private fun decode(text: String): InformationDeliveryAttempt {
        val root = InformationRecordCodec.parseObject(text)
        fun value(key: String) = root.getValue(key).jsonPrimitive.content
        return InformationDeliveryAttempt(value("record_id"), value("fingerprint"), InformationDeliveryTarget(
            InformationOwner.entries.single { it.wire == value("owner_app") }, value("origin"), value("user_id"), value("email")),
            InformationDeliveryState.valueOf(value("state")), value("attempted_at"), value("message"))
    }
    private fun validId(id: String) = require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id))
    @Synchronized private fun <T> locked(action: () -> T): T = RandomAccessFile(File(directory, ".lock"), "rw").use { file ->
        file.channel.lock().use { action() }
    }
}
