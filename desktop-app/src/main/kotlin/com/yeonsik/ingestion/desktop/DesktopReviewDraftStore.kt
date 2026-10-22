package com.yeonsik.ingestion.desktop

import kotlinx.serialization.json.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.UUID

internal data class SavedReviewDraft(val fingerprint: String, val values: Map<String, String>)

/** Sidecar drafts never change a session's canonical bytes, verification or delivery state. */
internal open class DesktopReviewDraftStore(private val directory: Path) {
    open fun load(id: String): SavedReviewDraft? {
        val file = file(id)
        if (!Files.exists(file)) return null
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        require(json.getValue("version").jsonPrimitive.int == 1) { "Unsupported draft version" }
        return SavedReviewDraft(json.getValue("fingerprint").jsonPrimitive.content,
            json.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content })
    }

    open fun save(id: String, fingerprint: String, values: Map<String, String>) {
        val target = file(id)
        if (values.isEmpty()) { Files.deleteIfExists(target); return }
        Files.createDirectories(directory)
        val temp = directory.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temp, buildJsonObject {
                put("version", 1); put("fingerprint", fingerprint)
                put("values", buildJsonObject { values.forEach { (key, value) -> put(key, value) } })
            }.toString())
            try { Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

    fun latestSessionId(): String? {
        if (!Files.isDirectory(directory)) return null
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }
                .max(compareBy { Files.getLastModifiedTime(it).toMillis() }).orElse(null)
                ?.fileName?.toString()?.removeSuffix(".json")
        }
    }

    private fun file(id: String): Path {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,160}")) && id != "." && id != "..")
        return directory.resolve("$id.json")
    }
}
