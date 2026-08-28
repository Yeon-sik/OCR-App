package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitPayload
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitResult
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitResponse
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitter
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate

data class CashOsLedgerCandidate(
    val id: String,
    val occurredOn: String,
    val amountKrw: Long,
    val title: String?,
    val merchantOrCounterparty: String?,
    val status: String?,
)
sealed interface CashOsAuthOutcome {
    data class Success(val email: String) : CashOsAuthOutcome
    data class Failure(val kind: PriceObservationFailureKind, val message: String? = null) : CashOsAuthOutcome
}

/** CashOS is an independent authenticated target; it never shares PriceTrace credentials. */
internal class CashOsReceiptGateway(
    private val store: CashOsSupabaseStore,
    private val transport: PriceObservationHttpTransport = HttpsPriceObservationHttpTransport(),
) : CashOsReceiptSubmitter {
    suspend fun signIn(email: String, password: String): CashOsAuthOutcome {
        val config = store.read()
        if (!config.isConnectionConfigured || email.isBlank() || password.isBlank()) {
            return CashOsAuthOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        }
        return try {
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = "/auth/v1/token?grant_type=password",
                    authenticated = false,
                    body = "{\"email\":${jsonString(email.trim())},\"password\":${jsonString(password)}}",
                ),
            )
            when {
                response.statusCode in 200..299 -> saveAuthResponse(response.body, email.trim())
                response.statusCode == 401 || response.statusCode == 403 ->
                    CashOsAuthOutcome.Failure(PriceObservationFailureKind.AUTHENTICATION)
                response.statusCode in 500..599 ->
                    CashOsAuthOutcome.Failure(PriceObservationFailureKind.SERVER)
                else -> CashOsAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            CashOsAuthOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
        } catch (_: IOException) {
            CashOsAuthOutcome.Failure(PriceObservationFailureKind.NETWORK)
        } catch (_: Exception) {
            CashOsAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        }
    }

    /** Read-only candidate resolution. No ledger entry is selected implicitly. */
    suspend fun fetchLedgerCandidates(
        purchaseLocalDate: String,
        totalAmountKrw: Long,
    ): PriceObservationReadOutcome<List<CashOsLedgerCandidate>> {
        val config = store.read()
        if (!config.isSignedIn) return PriceObservationReadOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        val normalizedDate = runCatching { LocalDate.parse(purchaseLocalDate).toString() }.getOrNull()
        if (normalizedDate == null || totalAmountKrw < 0) {
            return PriceObservationReadOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        }
        return try {
            val response = transport.execute(
                request(
                    config = config,
                    method = "GET",
                    path = "/rest/v1/finance_ledger_entries?select=id,occurred_on,amount_krw,title,merchant_or_counterparty,status&amount_krw=eq.$totalAmountKrw&occurred_on=eq.$normalizedDate&entry_type=in.(EXPENSE,FIXED_EXPENSE)&deleted_at=is.null&order=occurred_on.desc&limit=50",
                ),
            )
            if (response.statusCode !in 200..299) {
                PriceObservationReadOutcome.Failure(classify(response), response.body.takeIf(String::isNotBlank))
            } else {
                PriceObservationReadOutcome.Success(decodeLedgerCandidates(response.body))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            PriceObservationReadOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
        } catch (_: IOException) {
            PriceObservationReadOutcome.Failure(PriceObservationFailureKind.NETWORK)
        } catch (_: Exception) {
            PriceObservationReadOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        }
    }
    override suspend fun submit(payload: CashOsReceiptSubmitPayload): CashOsReceiptSubmitResult {
        val config = store.read()
        if (!config.isSignedIn) {
            return CashOsReceiptSubmitResult.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        }
        return try {
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = "/rest/v1/rpc/finance_attach_verified_receipt_v2",
                    body = payload.toRpcJson(),
                ),
            )
            if (response.statusCode !in 200..299) {
                return CashOsReceiptSubmitResult.Failure(classify(response), response.body.takeIf(String::isNotBlank))
            }
            CashOsReceiptSubmitResult.Success(decode(response.body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            CashOsReceiptSubmitResult.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
        } catch (_: IOException) {
            CashOsReceiptSubmitResult.Failure(PriceObservationFailureKind.NETWORK)
        } catch (_: Exception) {
            CashOsReceiptSubmitResult.Failure(PriceObservationFailureKind.CONTRACT)
        }
    }

    private fun decode(body: String): CashOsReceiptSubmitResponse {
        val row = Json.parseToJsonElement(body).jsonArray.single().jsonObject
        fun string(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
            ?: error("Missing CashOS response field: $key")
        fun bool(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrict()
            ?: error("Missing CashOS response field: $key")
        fun int(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: error("Missing CashOS response field: $key")
        return CashOsReceiptSubmitResponse(
            receiptId = string("receipt_id"),
            replayed = bool("replayed"),
            itemCount = int("item_count"),
        )
    }
    private fun decodeLedgerCandidates(body: String): List<CashOsLedgerCandidate> =
        Json.parseToJsonElement(body).jsonArray.map { element ->
            val row = element.jsonObject
            fun requiredString(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: error("Missing CashOS ledger field: $key")
            fun optionalString(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
            CashOsLedgerCandidate(
                id = requiredString("id"),
                occurredOn = requiredString("occurred_on"),
                amountKrw = (row["amount_krw"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                    ?: error("Missing CashOS ledger field: amount_krw"),
                title = optionalString("title"),
                merchantOrCounterparty = optionalString("merchant_or_counterparty"),
                status = optionalString("status"),
            )
        }

    private fun classify(response: PriceObservationHttpResponse): PriceObservationFailureKind = when {
        response.statusCode == 401 || response.statusCode == 403 -> PriceObservationFailureKind.AUTHENTICATION
        response.statusCode == 408 -> PriceObservationFailureKind.NETWORK_TIMEOUT
        response.statusCode in 500..599 -> PriceObservationFailureKind.SERVER
        response.body.contains("idempotency key", ignoreCase = true) ||
            response.body.contains("source document identity", ignoreCase = true) ->
            PriceObservationFailureKind.IDEMPOTENCY_MISMATCH
        else -> PriceObservationFailureKind.CONTRACT
    }

    private fun saveAuthResponse(body: String, fallbackEmail: String): CashOsAuthOutcome {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return CashOsAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        val user = root["user"] as? JsonObject
            ?: return CashOsAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        val saved = store.saveSession(
            userId = user.stringOrNull("id").orEmpty(),
            email = user.stringOrNull("email") ?: fallbackEmail,
            accessToken = root.stringOrNull("access_token").orEmpty(),
            refreshToken = root.stringOrNull("refresh_token").orEmpty(),
        )
        return if (saved.isSuccess) CashOsAuthOutcome.Success(user.stringOrNull("email") ?: fallbackEmail)
        else CashOsAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
    }

    private fun request(
        config: CashOsSupabaseConfig,
        method: String,
        path: String,
        authenticated: Boolean = true,
        body: String? = null,
    ): PriceObservationHttpRequest = PriceObservationHttpRequest(
        method = method,
        url = config.url.trimEnd('/') + path,
        headers = buildMap {
            put("apikey", config.publishableKey)
            put("Accept", "application/json")
            if (authenticated) put("Authorization", "Bearer ${config.accessToken}")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
        },
        body = body,
    )

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun jsonString(value: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}