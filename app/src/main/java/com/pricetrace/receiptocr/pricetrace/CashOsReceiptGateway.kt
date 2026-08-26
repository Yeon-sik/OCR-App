package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitPayload
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitResult
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitResponse
import com.pricetrace.receiptscanner.publisher.CashOsReceiptSubmitter
import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

data class CashOsSupabaseConfig(
    val url: String = "",
    val publishableKey: String = "",
    val accessToken: String = "",
) {
    val isSignedIn: Boolean get() = url.isNotBlank() && publishableKey.isNotBlank() && accessToken.isNotBlank()
}

/** Authenticated CashOS adapter. The provider keeps credentials outside the receipt model. */
internal class CashOsReceiptGateway(
    private val configProvider: () -> CashOsSupabaseConfig,
    private val transport: PriceObservationHttpTransport = HttpsPriceObservationHttpTransport(),
) : CashOsReceiptSubmitter {
    override suspend fun submit(payload: CashOsReceiptSubmitPayload): CashOsReceiptSubmitResult {
        val config = configProvider()
        if (!config.isSignedIn) {
            return CashOsReceiptSubmitResult.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        }
        return try {
            val response = transport.execute(
                PriceObservationHttpRequest(
                    method = "POST",
                    url = config.url.trimEnd('/') + "/rest/v1/rpc/finance_attach_verified_receipt_v2",
                    headers = mapOf(
                        "apikey" to config.publishableKey,
                        "Authorization" to "Bearer ${config.accessToken}",
                        "Accept" to "application/json",
                        "Content-Type" to "application/json; charset=utf-8",
                    ),
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
        return CashOsReceiptSubmitResponse(string("receipt_id").also { UUID.fromString(it) }, bool("replayed"), int("item_count"))
    }

    private fun classify(response: PriceObservationHttpResponse): PriceObservationFailureKind = when {
        response.statusCode == 401 || response.statusCode == 403 -> PriceObservationFailureKind.AUTHENTICATION
        response.statusCode == 408 -> PriceObservationFailureKind.NETWORK_TIMEOUT
        response.statusCode in 500..599 -> PriceObservationFailureKind.SERVER
        response.body.contains("idempotency key", ignoreCase = true) -> PriceObservationFailureKind.IDEMPOTENCY_MISMATCH
        else -> PriceObservationFailureKind.CONTRACT
    }
}
