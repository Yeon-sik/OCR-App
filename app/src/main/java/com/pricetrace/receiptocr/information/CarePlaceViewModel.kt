package com.pricetrace.receiptocr.information

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import com.pricetrace.receiptscanner.information.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.YearMonth
import java.util.UUID

class CarePlaceViewModel @JvmOverloads constructor(application: Application, draftName: String = "care_place_draft") : AndroidViewModel(application) {
    private val credentials = InformationDeliveryGateway(informationConnections(application))
    private val gateway = CarePlaceGateway(credentials)
    private val drafts = application.getSharedPreferences(draftName, 0)
    var category by mutableStateOf(drafts.getString("category", "hospital")!!); private set
    var name by mutableStateOf(drafts.getString("name", "")!!); private set
    var address by mutableStateOf(drafts.getString("address", "")!!); private set
    var phone by mutableStateOf(drafts.getString("phone", "")!!); private set
    private var requestId = drafts.getString("request_id", null) ?: UUID.randomUUID().toString()
    var confirmed by mutableStateOf(false)
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var places by mutableStateOf<List<CarePlace>>(emptyList()); private set
    var selected by mutableStateOf<CarePlace?>(null); private set
    var expenses by mutableStateOf<List<CareExpense>>(emptyList()); private set
    var expense by mutableStateOf<CareExpense?>(null); private set
    var paymentConfirmed by mutableStateOf(false)
    var month by mutableStateOf(YearMonth.now().toString()); private set
    var searchCompleted by mutableStateOf(false); private set
    private var loadedCashOwner: String? = null

    fun prefill(sourceName: String?, sourceAddress: String?, sourcePhone: String?) {
        // A pending draft wins after process recreation; OCR values always require review.
        if (name.isNotBlank() || address.isNotBlank()) return
        edit("name", sourceName.orEmpty()); edit("address", sourceAddress.orEmpty()); edit("phone", sourcePhone.orEmpty())
    }
    fun edit(field: String, value: String) {
        when (field) {
            "category" -> { require(value in setOf("hospital", "pharmacy")); category = value }
            "name" -> name = value.take(200)
            "address" -> address = value.take(500)
            "phone" -> phone = value.take(60)
        }
        requestId = UUID.randomUUID().toString(); confirmed = false; selected = null; expense = null; paymentConfirmed = false
        notice = null; error = null; places = emptyList(); searchCompleted = false
        drafts.edit().putString("request_id", requestId).putString("category", category).putString("name", name).putString("address", address).putString("phone", phone).apply()
    }
    fun updateMonth(value: String) { month = value.take(7); expenses = emptyList(); expense = null; paymentConfirmed = false; loadedCashOwner = null }
    fun newPlace() { edit("name", ""); edit("address", ""); edit("phone", ""); expenses = emptyList() }
    fun select(place: CarePlace) { selected = place; expense = null; paymentConfirmed = false; notice = "${place.input.name}을 선택했습니다. CashOS 결제나 HealthTrace 처방에서 같은 장소를 선택할 수 있습니다." }
    fun selectExpense(value: CareExpense) { expense = value; paymentConfirmed = false }
    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; notice = null
        viewModelScope.launch { try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "처리하지 못했습니다. 다시 시도하세요." } finally { busy = false } }
    }
    fun search() = run { val result = withContext(Dispatchers.IO) { gateway.search(name, category) }; places = result; searchCompleted = true }
    fun register() = run {
        val result = withContext(Dispatchers.IO) { gateway.register(requestId, CarePlaceInput(category, name, address, phone), confirmed) }
        selected = result.place; confirmed = false
        notice = if (result.duplicate) "이미 등록된 장소를 연결했습니다. 중복 등록하지 않았습니다." else "PriceTrace 홈에 병원·약국을 등록했습니다."
    }
    fun loadExpenses() = run {
        val owner = credentials.connection(InformationOwner.CASHOS).userId
        expenses = withContext(Dispatchers.IO) { gateway.expenses(month) }; loadedCashOwner = owner; expense = null; paymentConfirmed = false
        if (expenses.isEmpty()) notice = "선택한 달에 확정된 결제가 없습니다. CashOS에서 결제를 먼저 등록하세요."
    }
    fun link() = run {
        require(loadedCashOwner == credentials.connection(InformationOwner.CASHOS).userId) { "CashOS 계정이 변경됐습니다. 결제를 다시 조회하세요." }
        val duplicate = withContext(Dispatchers.IO) { gateway.link(requireNotNull(expense), requireNotNull(selected), paymentConfirmed) }
        paymentConfirmed = false
        notice = if (duplicate) "이미 연결한 결제입니다." else "CashOS 결제에 연결했습니다. HealthTrace 처방에서도 같은 장소를 선택하세요."
    }
}
