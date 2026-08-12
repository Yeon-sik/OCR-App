package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.publisher.PriceObservationFailureKind
import com.pricetrace.receiptscanner.publisher.PriceObservationJson
import com.pricetrace.receiptscanner.publisher.PriceObservationProduct
import com.pricetrace.receiptscanner.publisher.PriceObservationSource
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitPayload
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitResult
import com.pricetrace.receiptscanner.publisher.PriceObservationSubmitter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.SocketTimeoutException

sealed interface PriceTraceAuthOutcome {
    data class Success(val email: String) : PriceTraceAuthOutcome
    data class Failure(val kind: PriceObservationFailureKind, val message: String? = null) : PriceTraceAuthOutcome
}

sealed interface PriceObservationReadOutcome<out T> {
    data class Success<T>(val value: T) : PriceObservationReadOutcome<T>
    data class Failure(
        val kind: PriceObservationFailureKind,
        val message: String? = null,
    ) : PriceObservationReadOutcome<Nothing>
}

internal class PriceObservationGateway(
    private val store: PriceTraceSupabaseStore,
    private val transport: PriceObservationHttpTransport = HttpsPriceObservationHttpTransport(),
) : PriceObservationSubmitter {
    suspend fun signIn(email: String, password: String): PriceTraceAuthOutcome {
        val config = store.read()
        if (!config.isConnectionConfigured || email.isBlank() || password.isBlank()) {
            return PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
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
                    PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.AUTHENTICATION)
                response.statusCode in 500..599 ->
                    PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.SERVER)
                else -> PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
        } catch (_: IOException) {
            PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.NETWORK)
        } catch (_: Exception) {
            PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        }
    }

    suspend fun fetchSources(): PriceObservationReadOutcome<List<PriceObservationSource>> =
        readAuthenticated(
            body = PriceObservationJson.encodeEmptyRpcRequest(),
            path = "/rest/v1/rpc/get_price_observation_sources_v1",
            decode = PriceObservationJson::decodeSources,
        )

    suspend fun searchProducts(query: String): PriceObservationReadOutcome<List<PriceObservationProduct>> =
        readAuthenticated(
            body = PriceObservationJson.encodeProductReadRequest(query),
            path = "/rest/v1/rpc/get_product_read_v1",
            decode = { body -> PriceObservationJson.decodeProductRead(body).products },
        )

    override suspend fun submit(payload: PriceObservationSubmitPayload): PriceObservationSubmitResult {
        val initial = store.read()
        if (!initial.isSignedIn) {
            return PriceObservationSubmitResult.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        }
        return submitOnce(payload, initial)
    }

    private suspend fun <T> readAuthenticated(
        body: String,
        path: String,
        decode: (String) -> T,
    ): PriceObservationReadOutcome<T> {
        val config = store.read()
        if (!config.isSignedIn) {
            return PriceObservationReadOutcome.Failure(PriceObservationFailureKind.NOT_CONFIGURED)
        }
        return try {
            val response = transport.execute(request(config, "POST", path, body = body))
            if (response.statusCode !in 200..299) {
                return PriceObservationReadOutcome.Failure(
                    kind = classifyFailure(response),
                    message = response.body.takeIf(String::isNotBlank),
                )
            }
            PriceObservationReadOutcome.Success(decode(response.body))
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

    private suspend fun submitOnce(
        payload: PriceObservationSubmitPayload,
        config: PriceTraceSupabaseConfig,
    ): PriceObservationSubmitResult = try {
        val response = transport.execute(
            request(
                config = config,
                method = "POST",
                path = "/rest/v1/rpc/submit_price_observation_v1",
                body = payload.toRpcJson(),
            ),
        )
        if (response.statusCode !in 200..299) {
            return PriceObservationSubmitResult.Failure(
                kind = classifyFailure(response),
                message = response.body.takeIf(String::isNotBlank),
            )
        }
        PriceObservationSubmitResult.Success(PriceObservationJson.decodeSubmitResponse(response.body))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        PriceObservationSubmitResult.Failure(PriceObservationFailureKind.NETWORK_TIMEOUT)
    } catch (_: IOException) {
        PriceObservationSubmitResult.Failure(PriceObservationFailureKind.NETWORK)
    } catch (_: Exception) {
        PriceObservationSubmitResult.Failure(PriceObservationFailureKind.CONTRACT)
    }

    private fun classifyFailure(response: PriceObservationHttpResponse): PriceObservationFailureKind {
        if (response.statusCode == 401 || response.statusCode == 403) {
            return PriceObservationFailureKind.AUTHENTICATION
        }
        if (response.statusCode == 408) return PriceObservationFailureKind.NETWORK_TIMEOUT
        if (response.statusCode in 500..599) return PriceObservationFailureKind.SERVER
        val error = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val code = error?.stringOrNull("code")
        val message = error?.stringOrNull("message").orEmpty().lowercase()
        return when {
            code == "23505" || message.contains("idempotency key") ->
                PriceObservationFailureKind.IDEMPOTENCY_MISMATCH
            code == "22023" || message.contains("approved public observation source") ||
                message.contains("active retail product") -> PriceObservationFailureKind.INVALID_SELECTION
            else -> PriceObservationFailureKind.CONTRACT
        }
    }

    private fun saveAuthResponse(body: String, fallbackEmail: String): PriceTraceAuthOutcome {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        val user = root["user"]?.let { it as? JsonObject }
            ?: return PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        val userId = user.stringOrNull("id").orEmpty()
        val email = user.stringOrNull("email") ?: fallbackEmail
        val saved = store.saveSession(
            userId = userId,
            email = email,
            accessToken = root.stringOrNull("access_token").orEmpty(),
            refreshToken = root.stringOrNull("refresh_token").orEmpty(),
        )
        return if (saved.isSuccess) {
            PriceTraceAuthOutcome.Success(email)
        } else {
            PriceTraceAuthOutcome.Failure(PriceObservationFailureKind.CONTRACT)
        }
    }

    private fun request(
        config: PriceTraceSupabaseConfig,
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

    private fun jsonString(value: String): String =
        Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
