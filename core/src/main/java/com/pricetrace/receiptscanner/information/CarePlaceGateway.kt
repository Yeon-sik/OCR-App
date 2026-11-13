package com.pricetrace.receiptscanner.information

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class CarePlaceInput(val category: String, val name: String, val address: String, val phone: String = "") {
    fun json(): JsonObject {
        require(category in setOf("hospital", "pharmacy")) { "병원 또는 약국을 선택하세요." }
        require(name.trim().length in 1..200 && address.trim().length in 1..500 && phone.trim().length <= 60) { "이름과 주소를 확인하세요." }
        return buildJsonObject { put("category", category); put("name", name.trim()); put("address", address.trim()); put("phone", phone.trim()) }
    }
}
data class CarePlace(val id: String, val input: CarePlaceInput) {
    fun json() = JsonObject(input.json() + mapOf("schema_version" to JsonPrimitive("care-place.v1"), "place_id" to JsonPrimitive(id)))
    companion object {
        fun decode(value: JsonElement): CarePlace {
            val j = value.jsonObject
            require(j.keys == setOf("schema_version", "place_id", "category", "name", "address", "phone") && j.text("schema_version") == "care-place.v1") { "장소 응답 형식을 확인할 수 없습니다." }
            val id = j.text("place_id"); require(UUID.fromString(id).toString().equals(id, ignoreCase = true))
            val input = CarePlaceInput(j.text("category"), j.text("name"), j.text("address"), j.text("phone")); input.json()
            return CarePlace(id, input)
        }
    }
}
data class CareExpense(val id: String, val date: String, val title: String, val amountKrw: Long)
data class CareRegistration(val place: CarePlace, val duplicate: Boolean)

class CarePlaceGateway(private val gateway: InformationDeliveryGateway) {
    suspend fun search(query: String, category: String): List<CarePlace> {
        require(category in setOf("hospital", "pharmacy"))
        return gateway.careRpc(InformationOwner.PRICETRACE, "search_care_places_v1", buildJsonObject {
            put("p_query", query.take(200)); put("p_category", category); put("p_limit", 30)
        }, publicRead = true).jsonArray.map(CarePlace::decode).also { places ->
            require(places.size <= 30 && places.all { it.input.category == category } && places.map { it.id }.distinct().size == places.size)
        }
    }
    suspend fun register(requestId: String, input: CarePlaceInput, confirmed: Boolean): CareRegistration {
        require(confirmed) { "공개할 기관 정보를 확인하세요." }; UUID.fromString(requestId)
        val result = gateway.careRpc(InformationOwner.PRICETRACE, "register_care_place_v1", buildJsonObject {
            put("p_request_id", requestId); put("p_place", input.json()); put("p_public_confirmed", true)
        }).jsonObject
        return CareRegistration(CarePlace.decode(result.getValue("place")), result.getValue("duplicate").jsonPrimitive.boolean)
    }
    suspend fun expenses(month: String): List<CareExpense> {
        val range = YearMonth.parse(month)
        return gateway.careRpc(InformationOwner.CASHOS, "finance_care_expenses_v1", buildJsonObject {
            put("p_from", range.atDay(1).toString()); put("p_to", range.atEndOfMonth().toString())
        }).jsonArray.map { value ->
            val j = value.jsonObject
            CareExpense(j.text("ledger_entry_id"), j.text("occurred_on").also { LocalDate.parse(it) }, j.text("title"), j.getValue("amount_krw").jsonPrimitive.long.also { require(it >= 0) })
        }
    }
    suspend fun link(expense: CareExpense, place: CarePlace, confirmed: Boolean): Boolean {
        require(confirmed) { "결제와 장소를 확인하세요." }
        val result = gateway.careRpc(InformationOwner.CASHOS, "finance_link_care_place_v1", buildJsonObject {
            put("p_ledger_entry_id", expense.id); put("p_place", place.json()); put("p_confirmed", true)
        }).jsonObject
        require(result.text("ledger_entry_id") == expense.id && result.text("place_id") == place.id)
        return result.getValue("duplicate").jsonPrimitive.boolean
    }
}
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
