package com.pricetrace.receiptscanner.information

import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime

enum class InformationOwner(val wire: String, val label: String, val scope: String) {
    FITNESS("fitness", "Fitness", "활동·운동·식사·체중"),
    CASHOS("cashos", "CashOS", "결제·자산·금융 자료"),
    PRICETRACE("pricetrace", "PriceTrace", "상품·가게·서비스·가격"),
    HEALTHTRACE("healthtrace", "HealthTrace", "수면·건강·검사·복용"),
}

/** Validated immutable source payload. Receiver review state deliberately lives elsewhere. */
class InformationRecord internal constructor(val payload: JsonObject) {
    val id: String get() = payload.getValue("record_id").jsonPrimitive.content
    val owner: InformationOwner get() = InformationOwner.entries.single { it.wire == payload.getValue("owner_app").jsonPrimitive.content }
    val title: String get() = payload.getValue("title").jsonPrimitive.content
    val canonical: String get() = InformationRecordCodec.canonical(payload)
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object InformationRecordCodec {
    const val MAX_BYTES = 1_048_576
    private val json = Json { prettyPrint = true }
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val decimal = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
    private val instant = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})")
    private val rootKeys = setOf("schema_version", "record_id", "owner_app", "record_type", "title", "captured_at", "occurred_at", "supersedes_record_id", "sources", "fields", "references")

    /** Draft editing must reject ambiguous keys before any DOM operation can discard them. */
    fun parseValue(text: String): JsonElement {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "기록은 UTF-8 1 MiB 이하여야 합니다." }
        validateStructureBeforeParsing(text)
        return json.parseToJsonElement(text).also { validateJsonValues(it) }
    }

    fun parseObject(text: String): JsonObject = parseValue(text) as? JsonObject ?: error("기록은 JSON 객체여야 합니다.")

    fun decode(text: String, expectedOwner: InformationOwner? = null): InformationRecord {
        val root = parseObject(text)
        root.keysOnly(rootKeys)
        require(root.text("schema_version") == "information-record.v1") { "지원하지 않는 기록 형식입니다." }
        val id = root.text("record_id").also { require(uuid.matches(it)) { "record_id는 소문자 UUID여야 합니다." } }
        val owner = InformationOwner.entries.singleOrNull { it.wire == root.text("owner_app") }
            ?: error("소유 앱을 선택하세요.")
        require(expectedOwner == null || owner == expectedOwner) { "이 앱이 소유하는 정보만 가져올 수 있습니다." }
        val type = root.text("record_type", 100)
        require(type.startsWith(owner.wire + ".") && Regex("(fitness|cashos|pricetrace|healthtrace)\\.[a-z][a-z0-9_.-]*").matches(type)) { "기록 종류와 소유 앱이 다릅니다." }
        root.text("title", 200)
        timestamp(root.text("captured_at"))
        root.optionalText("occurred_at")?.let(::timestamp)
        root.optionalText("supersedes_record_id")?.let { require(uuid.matches(it) && it != id) { "정정 대상 ID가 올바르지 않습니다." } }

        val sources = root.array("sources", 1, 32).map { it.obj("source") }
        val sourceIds = sources.map { source ->
            source.keysOnly(setOf("source_id", "kind", "text", "uri", "sha256", "name", "media_type"))
            val sourceId = source.text("source_id", 128)
            val kind = source.text("kind")
            require(kind in setOf("manual", "text", "file", "url", "device")) { "지원하지 않는 출처 종류입니다." }
            source.optionalText("text", 100000, allowEmpty = true)
            source.optionalText("uri", 2048)?.let(::httpUri)
            source.optionalText("sha256")?.let { require(Regex("[0-9a-f]{64}").matches(it)) { "출처 SHA-256 형식이 올바르지 않습니다." } }
            source.optionalText("name", 255)
            source.optionalText("media_type", 127)
            when (kind) {
                "text" -> source.text("text", 100000)
                "url" -> httpUri(source.text("uri", 2048))
                "file" -> { source.text("sha256"); source.text("name", 255) }
            }
            sourceId
        }
        require(sourceIds.distinct().size == sourceIds.size) { "출처 ID가 중복되었습니다." }
        val keys = root.array("fields", 1, 128).map { value ->
            val field = value.obj("field")
            field.keysOnly(setOf("key", "value_type", "value", "label", "unit", "confidence", "source_ids"))
            val key = field.text("key", 64)
            require(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,63}").matches(key)) { "필드 key 형식을 확인하세요." }
            require(InformationOwner.entries.none { it != owner && key.startsWith(it.wire + ".") }) { "다른 앱 소유 정보는 참조로 연결하세요." }
            field.optionalText("label", 120)
            val valueType = field.text("value_type")
            require(field.containsKey("value")) { "$key 값이 없습니다." }
            when (valueType) {
                "text" -> field.text("value", 100000, allowEmpty = true)
                "decimal" -> require(decimal.matches(field.text("value", 128))) { "$key 숫자는 지수 없는 십진 문자열이어야 합니다." }
                "boolean" -> require((field["value"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true) { "$key 값은 true 또는 false여야 합니다." }
                "date" -> {
                    val date = field.text("value")
                    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) { "$key 날짜 형식을 확인하세요." }
                    LocalDate.parse(date)
                }
                "datetime" -> timestamp(field.text("value"))
                "json" -> Unit
                else -> error("지원하지 않는 값 종류: $valueType")
            }
            field.optionalText("unit", 64)?.let { require(valueType == "decimal") { "단위는 숫자 필드에만 입력할 수 있습니다." } }
            field["confidence"]?.let {
                val number = it as? JsonPrimitive
                require(number != null && !number.isString && number.doubleOrNull?.let { d -> d.isFinite() && d in 0.0..1.0 } == true) { "신뢰도는 0~1 숫자여야 합니다." }
            }
            if (field.containsKey("source_ids")) {
                val refs = field.array("source_ids", 0, 32).map { it.stringValue("source_id") }
                require(refs.distinct().size == refs.size && refs.all { it in sourceIds }) { "필드 출처가 없거나 중복되었습니다." }
            }
            key
        }
        require(keys.distinct().size == keys.size) { "필드 key가 중복되었습니다." }
        root.array("references", 0, 64).forEach { value ->
            val ref = value.obj("reference")
            ref.keysOnly(setOf("owner_app", "record_id", "relation", "record_type", "project", "uri"))
            require(InformationOwner.entries.any { it.wire == ref.text("owner_app") }) { "참조 소유 앱이 올바르지 않습니다." }
            ref.text("record_id", 200); ref.text("relation", 80)
            ref.optionalText("record_type", 100); ref.optionalText("project", 200)
            ref.optionalText("uri", 2048)?.let(::httpUri)
        }
        return InformationRecord(root)
    }

    // JsonElement's default serializer can coerce an arbitrary JSON numeric token to Double.
    // Emit scalar tokens directly so both precision and trailing zeros survive export.
    fun pretty(record: InformationRecord): String = pretty(record.payload).let {
        if (it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) it else record.canonical
    }
    fun pretty(value: JsonElement, depth: Int = 0): String {
        val prefix = "  ".repeat(depth + 1)
        val closing = "  ".repeat(depth)
        return when (value) {
            is JsonObject -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$closing}") { "$prefix${JsonPrimitive(it.key)}: ${pretty(it.value, depth + 1)}" }
            is JsonArray -> if (value.isEmpty()) "[]" else value.joinToString(",\n", "[\n", "\n$closing]") { "$prefix${pretty(it, depth + 1)}" }
            else -> value.toString()
        }
    }
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${canonical(value.getValue(it))}" }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

    private fun timestamp(value: String) {
        require(instant.matches(value)) { "시각에는 날짜·초·시간대(Z 또는 +09:00 등)가 필요합니다." }
        OffsetDateTime.parse(value)
    }
    private fun validateJsonValues(value: JsonElement, depth: Int = 0) {
        require(depth < 64 || value is JsonPrimitive) { "중첩 JSON은 64단계까지 지원합니다." }
        when (value) {
            is JsonObject -> value.values.forEach { validateJsonValues(it, depth + 1) }
            is JsonArray -> value.forEach { validateJsonValues(it, depth + 1) }
            is JsonPrimitive -> require(value.isString || value === JsonNull || value.content in setOf("true", "false") ||
                Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(value.content)) { "표준 JSON 값만 사용할 수 있습니다." }
        }
    }
    private class JsonContainer(val kind: Char, val keys: MutableSet<String> = mutableSetOf(), var expectingKey: Boolean = true)

    private fun validateStructureBeforeParsing(text: String) {
        val containers = mutableListOf<JsonContainer>()
        var inString = false
        var escaped = false
        var stringStart = 0
        for ((index, character) in text.withIndex()) {
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') {
                    inString = false
                    containers.lastOrNull()?.takeIf { it.kind == '{' && it.expectingKey }?.let { container ->
                        val key = json.parseToJsonElement(text.substring(stringStart, index + 1)).jsonPrimitive.content
                        require(container.keys.add(key)) { "중복 JSON 객체 키는 사용할 수 없습니다: $key" }
                        container.expectingKey = false
                    }
                }
            } else when (character) {
                '"' -> { inString = true; stringStart = index }
                '{', '[' -> {
                    containers.add(JsonContainer(character))
                    require(containers.size <= 64) { "중첩 JSON은 64단계까지 지원합니다." }
                }
                '}', ']' -> {
                    require(containers.isNotEmpty() && containers.last().kind == if (character == '}') '{' else '[') { "JSON 괄호가 올바르지 않습니다." }
                    containers.removeAt(containers.lastIndex)
                }
                ',' -> containers.lastOrNull()?.let { if (it.kind == '{') it.expectingKey = true }
            }
        }
        require(containers.isEmpty() && !inString) { "JSON 문자열 또는 괄호가 닫히지 않았습니다." }
    }
    private fun httpUri(value: String) {
        val uri = URI(value)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "출처 주소는 인증정보 없는 http/https URL이어야 합니다." }
    }
    private fun JsonElement.obj(label: String) = this as? JsonObject ?: error("$label 항목은 객체여야 합니다.")
    private fun JsonElement.stringValue(label: String): String {
        val p = this as? JsonPrimitive
        require(p != null && p.isString) { "$label 항목은 문자열이어야 합니다." }
        return p.content
    }
    private fun JsonObject.text(key: String, max: Int = 256, allowEmpty: Boolean = false): String {
        val value = get(key)?.stringValue(key) ?: error("$key 항목이 필요합니다.")
        require(value.length <= max && (allowEmpty || value.isNotBlank())) { "$key 길이와 빈 값을 확인하세요." }
        return value
    }
    private fun JsonObject.optionalText(key: String, max: Int = 256, allowEmpty: Boolean = false): String? = if (containsKey(key)) text(key, max, allowEmpty) else null
    private fun JsonObject.array(key: String, min: Int, max: Int): JsonArray {
        val values = get(key) as? JsonArray ?: error("$key 항목은 배열이어야 합니다.")
        require(values.size in min..max) { "$key 항목 수를 확인하세요 ($min~$max)." }
        return values
    }
    private fun JsonObject.keysOnly(allowed: Set<String>) {
        require(keys.all { it in allowed }) { "알 수 없는 속성: ${keys.minus(allowed).joinToString()}. 추가 정보는 fields에 넣으세요." }
    }
}
