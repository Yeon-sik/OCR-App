package com.pricetrace.receiptscanner.publisher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.util.UUID

/** The only values sent to the PriceTrace observation RPC. */
data class PriceObservationSubmitPayload(
    val idempotencyKey: String,
    val storeId: String,
    val observedOn: String,
    val catalogProductId: String,
    val unitPriceKrw: Int,
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200) {
            "idempotencyKey must contain 1 to 200 characters"
        }
        requireUuid(storeId, "storeId")
        require(runCatching { LocalDate.parse(observedOn) }.isSuccess) {
            "observedOn must be an ISO date"
        }
        requireUuid(catalogProductId, "catalogProductId")
        require(unitPriceKrw >= 0) { "unitPriceKrw must be non-negative" }
    }

    fun toRpcJson(): String = PriceObservationJson.encodeSubmitPayload(this)
}

enum class PriceObservationAppliedAction(val wireValue: String) {
    CREATED("created"),
    DEDUPLICATED("deduplicated"),
    REPLAYED("replayed"),
}

data class PriceObservationSubmitResponse(
    val observationId: String,
    val replayed: Boolean,
    val appliedAction: PriceObservationAppliedAction,
)

data class PriceObservationSource(
    val storeId: String,
    val sourceNamespace: String,
    val sourceStoreCode: String,
    val displayName: String,
    val locationLabel: String?,
)

data class PriceObservationSellerProduct(
    val sellerLabel: String,
    val sourceProductCode: String,
)

data class PriceObservationMarketObservation(
    val observationId: String,
    val sellerLabel: String,
    val listedPriceKrw: Int,
    val shippingFeeKrw: Int,
    val minimumOrderQuantity: Int,
    val checkoutPriceKrw: Long,
    val observedAt: String,
    val productUrl: String?,
    val source: String,
)

/** An exact catalog row returned by the existing product-read.v1 projection. */
data class PriceObservationProduct(
    val standardProductId: String,
    val standardProductName: String,
    val standardBrand: String?,
    val standardUpdatedAt: String,
    val catalogProductId: String,
    val catalogProductName: String,
    val specificationText: String?,
    val contentAmount: Double,
    val contentUnit: String,
    val packageCount: Int,
    val referenceUnit: String,
    val listingReferenceUrl: String?,
    val catalogUpdatedAt: String,
    val sellerProducts: List<PriceObservationSellerProduct>,
    val observations: List<PriceObservationMarketObservation>,
) {
    val displayName: String
        get() = listOfNotNull(standardBrand, standardProductName).joinToString(" · ")

    val exactSelectionLabel: String
        get() = listOfNotNull(displayName, catalogProductName, specificationText).joinToString(" · ")
}

data class PriceObservationProductRead(
    val schemaVersion: String,
    val namespace: String,
    val revision: String,
    val products: List<PriceObservationProduct>,
)

/** Strict wire mapping for the three existing PriceTrace RPC contracts. */
object PriceObservationJson {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun encodeSubmitPayload(payload: PriceObservationSubmitPayload): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("p_idempotency_key", JsonPrimitive(payload.idempotencyKey))
                put("p_store_id", JsonPrimitive(payload.storeId))
                put("p_observed_on", JsonPrimitive(payload.observedOn))
                put("p_catalog_product_id", JsonPrimitive(payload.catalogProductId))
                put("p_unit_price_krw", JsonPrimitive(payload.unitPriceKrw))
            },
        )

    fun encodeProductReadRequest(query: String?, limit: Int = 50): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("p_catalog_product_id", JsonNull)
                put("p_query", query?.trim()?.takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
                put("p_limit", JsonPrimitive(limit.coerceIn(1, 100)))
            },
        )

    fun encodeEmptyRpcRequest(): String = "{}"

    fun decodeSubmitResponse(raw: String): PriceObservationSubmitResponse {
        val rows = parseArray(raw, "submit_price_observation_v1")
        require(rows.size == 1) { "submit_price_observation_v1 must return exactly one row" }
        val row = rows.single().jsonObject
        row.requireKeys(submitResponseKeys)
        val action = row.requiredString("applied_action")
        return PriceObservationSubmitResponse(
            observationId = requireUuid(row.requiredString("observation_id"), "observation_id"),
            replayed = row.requiredBoolean("replayed"),
            appliedAction = PriceObservationAppliedAction.entries.firstOrNull { it.wireValue == action }
                ?: error("Unsupported applied_action: $action"),
        )
    }

    fun decodeSources(raw: String): List<PriceObservationSource> =
        parseArray(raw, "get_price_observation_sources_v1").map { element ->
            val row = element.jsonObject
            row.requireKeys(sourceKeys)
            PriceObservationSource(
                storeId = requireUuid(row.requiredString("store_id"), "store_id"),
                sourceNamespace = row.requiredString("source_namespace"),
                sourceStoreCode = row.requiredString("source_store_code"),
                displayName = row.requiredString("display_name"),
                locationLabel = row.optionalString("location_label"),
            )
        }

    fun decodeProductRead(raw: String): PriceObservationProductRead {
        val root = parseObject(raw, "get_product_read_v1")
        root.requireKeys(productReadKeys)
        val products = root["products"]!!.jsonArray.map { productElement ->
            val product = productElement.jsonObject
            product.requireKeys(productKeys)
            val standard = product["standardProduct"]!!.jsonObject
            standard.requireKeys(standardProductKeys)
            val catalog = product["catalogProduct"]!!.jsonObject
            catalog.requireKeys(catalogProductKeys)
            val sellers = product["sellerProducts"]!!.jsonArray.map { sellerElement ->
                val seller = sellerElement.jsonObject
                seller.requireKeys(sellerProductKeys)
                PriceObservationSellerProduct(
                    sellerLabel = seller.requiredString("sellerLabel"),
                    sourceProductCode = seller.requiredString("sourceProductCode"),
                )
            }
            val observations = product["observations"]!!.jsonArray.map { observationElement ->
                val observation = observationElement.jsonObject
                observation.requireKeys(observationKeys)
                PriceObservationMarketObservation(
                    observationId = requireUuid(observation.requiredString("observationId"), "observationId"),
                    sellerLabel = observation.requiredString("sellerLabel"),
                    listedPriceKrw = observation.requiredInt("listedPriceKrw"),
                    shippingFeeKrw = observation.requiredInt("shippingFeeKrw"),
                    minimumOrderQuantity = observation.requiredInt("minimumOrderQuantity"),
                    checkoutPriceKrw = observation.requiredLong("checkoutPriceKrw"),
                    observedAt = observation.requiredString("observedAt"),
                    productUrl = observation.optionalString("productUrl"),
                    source = observation.requiredString("source"),
                )
            }
            PriceObservationProduct(
                standardProductId = requireUuid(standard.requiredString("id"), "standardProduct.id"),
                standardProductName = standard.requiredString("name"),
                standardBrand = standard.optionalString("brand"),
                standardUpdatedAt = standard.requiredString("updatedAt"),
                catalogProductId = requireUuid(catalog.requiredString("id"), "catalogProduct.id"),
                catalogProductName = catalog.requiredString("name"),
                specificationText = catalog.optionalString("specificationText"),
                contentAmount = catalog.requiredDouble("contentAmount"),
                contentUnit = catalog.requiredString("contentUnit"),
                packageCount = catalog.requiredInt("packageCount"),
                referenceUnit = catalog.requiredString("referenceUnit"),
                listingReferenceUrl = catalog.optionalString("listingReferenceUrl"),
                catalogUpdatedAt = catalog.requiredString("updatedAt"),
                sellerProducts = sellers,
                observations = observations,
            )
        }
        return PriceObservationProductRead(
            schemaVersion = root.requiredString("schemaVersion"),
            namespace = root.requiredString("namespace"),
            revision = root.requiredString("revision"),
            products = products,
        ).also {
            require(it.schemaVersion == "product-read.v1") { "Unsupported product-read schema" }
            require(it.namespace == "pricetrace") { "Unexpected product-read namespace" }
        }
    }

    private fun parseArray(raw: String, contract: String): JsonArray = runCatching {
        json.parseToJsonElement(raw).jsonArray
    }.getOrElse { error("Invalid $contract JSON response") }

    private fun parseObject(raw: String, contract: String): JsonObject = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrElse { error("Invalid $contract JSON response") }

    private fun JsonObject.requireKeys(expected: Set<String>) {
        require(keys == expected) {
            "Unexpected JSON keys. expected=${expected.sorted()} actual=${keys.sorted()}"
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("$key must be a non-empty JSON string")

    private fun JsonObject.optionalString(key: String): String? = when (val value = get(key)) {
        null, JsonNull -> null
        else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("$key must be a JSON string or null")
    }

    private fun JsonObject.requiredBoolean(key: String): Boolean =
        (get(key) as? JsonPrimitive)?.booleanOrNull ?: error("$key must be a JSON boolean")

    private fun JsonObject.requiredInt(key: String): Int =
        (get(key) as? JsonPrimitive)?.intOrNull ?: error("$key must be a JSON integer")

    private fun JsonObject.requiredLong(key: String): Long =
        (get(key) as? JsonPrimitive)?.longOrNull ?: error("$key must be a JSON integer")

    private fun JsonObject.requiredDouble(key: String): Double =
        (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
            ?: error("$key must be a finite JSON number")

    private val submitResponseKeys = setOf("observation_id", "replayed", "applied_action")
    private val sourceKeys = setOf(
        "store_id",
        "source_namespace",
        "source_store_code",
        "display_name",
        "location_label",
    )
    private val productReadKeys = setOf("schemaVersion", "namespace", "revision", "products")
    private val productKeys = setOf("standardProduct", "catalogProduct", "sellerProducts", "observations")
    private val standardProductKeys = setOf("id", "name", "brand", "updatedAt")
    private val catalogProductKeys = setOf(
        "id",
        "name",
        "specificationText",
        "contentAmount",
        "contentUnit",
        "packageCount",
        "referenceUnit",
        "listingReferenceUrl",
        "updatedAt",
    )
    private val sellerProductKeys = setOf("sellerLabel", "sourceProductCode")
    private val observationKeys = setOf(
            "observationId",
            "sellerLabel",
            "listedPriceKrw",
            "shippingFeeKrw",
            "minimumOrderQuantity",
            "checkoutPriceKrw",
            "observedAt",
            "productUrl",
            "source",
        )
}

enum class PriceObservationFailureKind(val retryable: Boolean) {
    NOT_CONFIGURED(false),
    AUTHENTICATION(false),
    INVALID_SELECTION(false),
    IDEMPOTENCY_MISMATCH(false),
    CONTRACT(false),
    NETWORK(false),
    NETWORK_TIMEOUT(true),
    SERVER(true),
}

sealed interface PriceObservationSubmitResult {
    data class Success(val response: PriceObservationSubmitResponse) : PriceObservationSubmitResult
    data class Failure(
        val kind: PriceObservationFailureKind,
        val message: String? = null,
    ) : PriceObservationSubmitResult {
        val retryable: Boolean get() = kind.retryable
    }
}

private fun requireUuid(value: String, label: String): String = runCatching {
    UUID.fromString(value.trim()).toString()
}.getOrElse { error("$label must be a UUID") }
