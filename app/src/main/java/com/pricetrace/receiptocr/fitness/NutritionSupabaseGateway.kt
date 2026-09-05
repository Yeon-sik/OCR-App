package com.pricetrace.receiptocr.fitness

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

enum class NutritionGatewayFailure {
    NOT_CONFIGURED,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    CONTRACT,
    CONFLICT,
    SERVER,
}

sealed interface NutritionAuthOutcome {
    data class Success(val email: String) : NutritionAuthOutcome
    data class Failure(val reason: NutritionGatewayFailure) : NutritionAuthOutcome
}

internal class NutritionSupabaseGateway(
    private val store: NutritionSupabaseStore,
    private val transport: NutritionHttpTransport = HttpsNutritionHttpTransport(),
) {
    suspend fun signIn(email: String, password: String): NutritionAuthOutcome {
        val config = store.read()
        if (!config.isConnectionConfigured || email.isBlank() || password.isBlank()) {
            return NutritionAuthOutcome.Failure(NutritionGatewayFailure.NOT_CONFIGURED)
        }
        return try {
            val response = transport.execute(
                request(
                    config = config,
                    method = "POST",
                    path = "/auth/v1/token?grant_type=password",
                    authenticated = false,
                    body = JsonObject(
                        mapOf(
                            "email" to JsonPrimitive(email.trim()),
                            "password" to JsonPrimitive(password),
                        ),
                    ).encode(),
                ),
            )
            when (response.statusCode) {
                in 200..299 -> saveAuthResponse(response.body, email.trim())
                400, 401, 403 -> NutritionAuthOutcome.Failure(NutritionGatewayFailure.AUTHENTICATION)
                429 -> NutritionAuthOutcome.Failure(NutritionGatewayFailure.RATE_LIMITED)
                in 500..599 -> NutritionAuthOutcome.Failure(NutritionGatewayFailure.SERVER)
                else -> NutritionAuthOutcome.Failure(NutritionGatewayFailure.CONTRACT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            NutritionAuthOutcome.Failure(NutritionGatewayFailure.NETWORK)
        } catch (_: Exception) {
            NutritionAuthOutcome.Failure(NutritionGatewayFailure.CONTRACT)
        }
    }

    suspend fun importCanonical(payload: CanonicalNutritionImportPayload): NutritionCanonicalImportOutcome {
        val initial = store.read()
        if (!initial.isSignedIn) {
            return NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.NOT_CONFIGURED)
        }
        val first = importCanonicalOnce(payload, initial)
        if (first !is NutritionCanonicalImportOutcome.Failure ||
            first.reason != NutritionGatewayFailure.AUTHENTICATION
        ) {
            return first
        }
        val refreshed = refresh(initial) ?: return first
        return importCanonicalOnce(payload, refreshed)
    }

    private suspend fun importCanonicalOnce(
        payload: CanonicalNutritionImportPayload,
        config: NutritionSupabaseConfig,
    ): NutritionCanonicalImportOutcome = try {
        val response = transport.execute(
            request(
                config = config,
                method = "POST",
                path = "/rest/v1/rpc/import_canonical_nutrition_v2",
                body = payload.toRpcJson(),
            ),
        )
        when {
            response.statusCode == 401 || response.statusCode == 403 ->
                NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.AUTHENTICATION)
            response.statusCode == 409 ->
                NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.CONFLICT, response.body.takeIf(String::isNotBlank))
            response.statusCode == 429 ->
                NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.RATE_LIMITED, response.body.takeIf(String::isNotBlank))
            response.statusCode in 500..599 ->
                NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.SERVER, response.body.takeIf(String::isNotBlank))
            response.statusCode !in 200..299 ->
                NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.CONTRACT, response.body.takeIf(String::isNotBlank))
            else -> NutritionCanonicalImportOutcome.Success(
                response = CanonicalNutritionImportJson.decodeResponse(response.body),
                rawResponse = response.body,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.NETWORK)
    } catch (error: Exception) {
        NutritionCanonicalImportOutcome.Failure(NutritionGatewayFailure.CONTRACT, error.message)
    }

    private suspend fun refresh(config: NutritionSupabaseConfig): NutritionSupabaseConfig? {
        if (config.refreshToken.isBlank()) return null
        return try {
            val response = transport.execute(
                request(
                    config,
                    "POST",
                    "/auth/v1/token?grant_type=refresh_token",
                    authenticated = false,
                    body = JsonObject(
                        mapOf("refresh_token" to JsonPrimitive(config.refreshToken)),
                    ).encode(),
                ),
            )
            if (response.statusCode !in 200..299) return null
            val root = json.parseToJsonElement(response.body) as? JsonObject ?: return null
            val user = root["user"] as? JsonObject
            store.saveSession(
                userId = user?.stringOrNull("id") ?: config.userId,
                email = user?.stringOrNull("email") ?: config.email,
                accessToken = root.stringOrNull("access_token").orEmpty(),
                refreshToken = root.stringOrNull("refresh_token").orEmpty(),
            ).getOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun saveAuthResponse(body: String, fallbackEmail: String): NutritionAuthOutcome {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: return NutritionAuthOutcome.Failure(NutritionGatewayFailure.CONTRACT)
        val user = root["user"] as? JsonObject
            ?: return NutritionAuthOutcome.Failure(NutritionGatewayFailure.CONTRACT)
        val userId = user.stringOrNull("id").orEmpty()
        val email = user.stringOrNull("email") ?: fallbackEmail
        val saved = store.saveSession(
            userId = userId,
            email = email,
            accessToken = root.stringOrNull("access_token").orEmpty(),
            refreshToken = root.stringOrNull("refresh_token").orEmpty(),
        )
        return if (saved.isSuccess) {
            NutritionAuthOutcome.Success(email)
        } else {
            NutritionAuthOutcome.Failure(NutritionGatewayFailure.CONTRACT)
        }
    }

    private fun request(
        config: NutritionSupabaseConfig,
        method: String,
        path: String,
        authenticated: Boolean = true,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): NutritionHttpRequest = NutritionHttpRequest(
        method = method,
        url = config.url.trimEnd('/') + path,
        headers = buildMap {
            put("apikey", config.publishableKey)
            put("Accept", "application/json")
            if (authenticated) put("Authorization", "Bearer ${config.accessToken}")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
            putAll(extraHeaders)
        },
        body = body,
    )

    private fun JsonObject.stringOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.encode(): String = json.encodeToString(JsonElement.serializer(), this)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
