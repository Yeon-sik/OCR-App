package com.pricetrace.receiptscanner.information

import com.pricetrace.receiptocr.fitness.*
import com.pricetrace.receiptocr.pricetrace.PriceTraceSupabaseConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Credentials remain in the platform's existing credential store, never in delivery receipts. */
class InformationConnection(
    val url: String, val publishableKey: String, val userId: String = "", val email: String = "",
    val accessToken: String = "", val refreshToken: String = "",
) {
    val origin: String get() = url.trim().trimEnd('/')
    override fun toString() = "InformationConnection(configured=${url.isNotBlank()})"
}

interface InformationConnectionStore {
    fun read(owner: InformationOwner): InformationConnection
    fun saveConnection(owner: InformationOwner, url: String, publishableKey: String)
    fun saveSession(owner: InformationOwner, userId: String, email: String, accessToken: String, refreshToken: String)
    fun clearSession(owner: InformationOwner)
}

data class InformationDeliveryTarget(val owner: InformationOwner, val origin: String, val userId: String, val email: String)
class PreparedInformationDelivery internal constructor(val recordId: String, val fingerprint: String, val target: InformationDeliveryTarget)
data class InformationDeliveryReceipt(val recordId: String, val duplicate: Boolean, val remoteFingerprint: String)

/** Sends only to a private owner inbox. Native projection is deliberately absent from this client. */
class InformationDeliveryGateway(
    private val connections: InformationConnectionStore,
    private val transport: NutritionHttpTransport = HttpsNutritionHttpTransport(4 * InformationRecordCodec.MAX_BYTES),
) {
    fun connection(owner: InformationOwner): InformationConnection = connections.read(owner)
    fun configure(owner: InformationOwner, url: String, key: String) {
        require(PriceTraceSupabaseConfig.validateConnection(url, key) == null) { "HTTPS 서버 주소와 공개 키를 확인하세요." }
        connections.saveConnection(owner, url.trim().trimEnd('/'), key.trim())
    }
    fun signOut(owner: InformationOwner) = connections.clearSession(owner)

    suspend fun signIn(owner: InformationOwner, email: String, password: String) {
        val config = checkedConnection(owner)
        require(email.isNotBlank() && password.isNotBlank()) { "이메일과 비밀번호를 입력하세요." }
        val result = request(config, "POST", "/auth/v1/token?grant_type=password", buildJsonObject {
            put("email", email.trim()); put("password", password)
        }.toString(), authenticated = false)
        checkStatus(result, "로그인")
        saveAuth(owner, config, result.body, allowNewAccount = config.userId.isBlank())
    }

    suspend fun prepare(record: InformationRecord): PreparedInformationDelivery {
        val (config, user) = authenticate(record.owner)
        return PreparedInformationDelivery(record.id, record.fingerprint,
            InformationDeliveryTarget(record.owner, config.origin, user.text("id"), user["email"]?.jsonPrimitive?.content.orEmpty()))
    }

    suspend fun deliver(record: InformationRecord, prepared: PreparedInformationDelivery): InformationDeliveryReceipt {
        require(record.id == prepared.recordId && record.fingerprint == prepared.fingerprint && record.owner == prepared.target.owner) {
            "확인한 기록이 변경됐습니다. 수신 계정부터 다시 확인하세요."
        }
        val (config, user) = authenticate(record.owner)
        require(config.origin == prepared.target.origin && user.text("id") == prepared.target.userId) {
            "수신 프로젝트 또는 계정이 변경됐습니다. 다시 확인하세요."
        }
        val (rpc, table) = routes(record.owner)
        val response = request(config, "POST", "/rest/v1/rpc/$rpc", buildJsonObject {
            put("p_payload_text", record.canonical); put("p_expected_user_id", prepared.target.userId)
        }.toString())
        checkStatus(response, "전송")
        val receipt = InformationRecordCodec.parseObject(response.body)
        require(receipt.text("record_id") == record.id && receipt["duplicate"]?.jsonPrimitive?.booleanOrNull != null &&
            Regex("[0-9a-f]{64}").matches(receipt.text("content_fingerprint"))) { "수신 응답을 확인하지 못했습니다. 같은 기록으로 재시도하세요." }
        // Verify stored bytes semantically as well. SQL and JVM canonical scalar escaping may differ.
        val stored = request(config, "GET", "/rest/v1/$table?select=payload_text&record_id=eq.${record.id}&user_id=eq.${prepared.target.userId}&limit=1")
        checkStatus(stored, "수신 확인")
        val rows = InformationRecordCodec.parseValue(stored.body, 4 * InformationRecordCodec.MAX_BYTES) as? JsonArray ?: error("수신 확인 형식이 올바르지 않습니다.")
        require(rows.size == 1 && InformationRecordCodec.decode(rows.single().jsonObject.text("payload_text"), record.owner).canonical == record.canonical) {
            "수신 원문이 다릅니다. 전송 완료로 표시하지 않았습니다."
        }
        checkUnchanged(record.owner, config)
        return InformationDeliveryReceipt(record.id, receipt.getValue("duplicate").jsonPrimitive.boolean, receipt.text("content_fingerprint"))
    }

    private suspend fun authenticate(owner: InformationOwner): Pair<InformationConnection, JsonObject> {
        var config = checkedConnection(owner)
        require(config.userId.isNotBlank() && config.accessToken.isNotBlank()) { "${owner.label} 수신 계정으로 먼저 로그인하세요." }
        var response = request(config, "GET", "/auth/v1/user")
        if (response.statusCode == 401 && config.refreshToken.isNotBlank()) {
            val refreshed = request(config, "POST", "/auth/v1/token?grant_type=refresh_token", buildJsonObject {
                put("refresh_token", config.refreshToken)
            }.toString(), authenticated = false)
            checkStatus(refreshed, "로그인 갱신")
            saveAuth(owner, config, refreshed.body, allowNewAccount = false)
            config = checkedConnection(owner)
            response = request(config, "GET", "/auth/v1/user")
        }
        checkStatus(response, "계정 확인")
        val user = InformationRecordCodec.parseObject(response.body)
        require(user.text("id") == config.userId) { "저장된 계정과 서버 계정이 다릅니다. 다시 로그인하세요." }
        checkUnchanged(owner, config)
        return config to user
    }

    private fun saveAuth(owner: InformationOwner, previous: InformationConnection, body: String, allowNewAccount: Boolean) {
        val root = InformationRecordCodec.parseObject(body)
        val user = root.getValue("user").jsonObject
        val id = user.text("id")
        require(Regex("[0-9a-f-]{36}").matches(id) && (allowNewAccount || id == previous.userId)) { "다른 계정입니다. 로그아웃 후 수신 계정을 다시 확인하세요." }
        checkUnchanged(owner, previous)
        connections.saveSession(owner, id, user["email"]?.jsonPrimitive?.content.orEmpty(), root.text("access_token"), root.text("refresh_token"))
    }

    private fun checkedConnection(owner: InformationOwner): InformationConnection = connections.read(owner).also {
        require(PriceTraceSupabaseConfig.validateConnection(it.url, it.publishableKey) == null) { "${owner.label} 서버 주소와 공개 키를 설정하세요." }
    }
    private fun checkUnchanged(owner: InformationOwner, previous: InformationConnection) {
        val current = connections.read(owner)
        require(current.origin == previous.origin && current.publishableKey == previous.publishableKey && current.userId == previous.userId && current.accessToken == previous.accessToken) {
            "처리 중 연결 계정이 변경됐습니다. 다시 확인하세요."
        }
    }
    private suspend fun request(config: InformationConnection, method: String, path: String, body: String? = null, authenticated: Boolean = true): NutritionHttpResponse {
        return try {
            transport.execute(NutritionHttpRequest(method, config.origin + path, buildMap {
                put("apikey", config.publishableKey); put("Content-Type", "application/json")
                if (authenticated) put("Authorization", "Bearer ${config.accessToken}")
            }, body))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("서버 응답을 확인하지 못했습니다. 원문은 남아 있으며 같은 기록으로 재시도할 수 있습니다.") }
    }
    private fun checkStatus(response: NutritionHttpResponse, operation: String) {
        when (response.statusCode) {
            in 200..299 -> Unit
            401, 403 -> error("$operation 권한이 없습니다. 수신 계정과 로그인을 확인하세요.")
            409 -> error("같은 ID의 다른 내용이 수신함에 있습니다. 정정 기록을 새 ID로 만드세요.")
            400, 422 -> error("수신 앱이 기록 형식을 거절했습니다. 원문과 지원 형식을 확인하세요.")
            else -> error("$operation 응답을 확인하지 못했습니다 (HTTP ${response.statusCode}). 같은 기록으로 재시도하세요.")
        }
    }
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() } ?: error("필수 응답 값이 없습니다: $key")
    private fun routes(owner: InformationOwner): Pair<String, String> = when (owner) {
        InformationOwner.FITNESS -> "fitness_ingest_information_record_v1" to "fitness_information_inbox"
        InformationOwner.CASHOS -> "finance_ingest_information_record_v1" to "finance_information_inbox"
        InformationOwner.PRICETRACE -> "ingest_pricetrace_information_record_v1" to "pricetrace_information_inbox"
        InformationOwner.HEALTHTRACE -> "health_ingest_information_record_v1" to "health_information_inbox"
    }
}
