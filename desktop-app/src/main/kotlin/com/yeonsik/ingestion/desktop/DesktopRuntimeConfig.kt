package com.yeonsik.ingestion.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Runtime-only configuration. Secrets are never copied into a session record or build artifact. */
class DesktopServiceConfig(
    val url: String,
    val publishableKey: String,
    val email: String,
    val password: String,
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
) {
    override fun toString(): String = "DesktopServiceConfig(url=$url, email=$email, userId=$userId, configured=${url.isNotBlank()})"
}

class DesktopRuntimeConfig(
    val evidence: DesktopServiceConfig,
    val priceTrace: DesktopServiceConfig,
    val nutrition: DesktopServiceConfig,
    val cashOs: DesktopServiceConfig,
    val envFile: Path,
) {
    override fun toString(): String = "DesktopRuntimeConfig(envFile=$envFile)"

    companion object {
        fun load(
            environment: Map<String, String> = System.getenv(),
            envFile: Path = defaultEnvFile(environment),
        ): DesktopRuntimeConfig {
            val fileValues = readEnvFile(envFile)
            fun value(name: String): String = environment[name] ?: fileValues[name].orEmpty()

            return DesktopRuntimeConfig(
                evidence = DesktopServiceConfig(
                    url = value("EVIDENCE_SUPABASE_URL"),
                    publishableKey = value("EVIDENCE_SUPABASE_PUBLISHABLE_KEY"),
                    email = value("EVIDENCE_EMAIL"),
                    password = value("EVIDENCE_PASSWORD"),
                    userId = value("EVIDENCE_USER_ID"),
                    accessToken = value("EVIDENCE_ACCESS_TOKEN"),
                    refreshToken = value("EVIDENCE_REFRESH_TOKEN"),
                ),
                priceTrace = DesktopServiceConfig(
                    url = value("PRICETRACE_SUPABASE_URL"),
                    publishableKey = value("PRICETRACE_SUPABASE_PUBLISHABLE_KEY"),
                    email = value("PRICETRACE_EMAIL"),
                    password = value("PRICETRACE_PASSWORD"),
                    userId = value("PRICETRACE_USER_ID"),
                    accessToken = value("PRICETRACE_ACCESS_TOKEN"),
                    refreshToken = value("PRICETRACE_REFRESH_TOKEN"),
                ),
                nutrition = DesktopServiceConfig(
                    url = value("NUTRITION_SUPABASE_URL"),
                    publishableKey = value("NUTRITION_SUPABASE_PUBLISHABLE_KEY")
                        .ifBlank { value("NUTRITION_SUPABASE_ANON_KEY") },
                    email = value("NUTRITION_EMAIL"),
                    password = value("NUTRITION_PASSWORD"),
                    userId = value("NUTRITION_USER_ID"),
                    accessToken = value("NUTRITION_ACCESS_TOKEN"),
                    refreshToken = value("NUTRITION_REFRESH_TOKEN"),
                ),
                cashOs = DesktopServiceConfig(
                    url = value("CASHOS_SUPABASE_URL"),
                    publishableKey = value("CASHOS_SUPABASE_PUBLISHABLE_KEY"),
                    email = value("CASHOS_EMAIL"),
                    password = value("CASHOS_PASSWORD"),
                    userId = value("CASHOS_USER_ID"),
                    accessToken = value("CASHOS_ACCESS_TOKEN"),
                    refreshToken = value("CASHOS_REFRESH_TOKEN"),
                ),
                envFile = envFile,
            )
        }

        fun defaultEnvFile(environment: Map<String, String> = System.getenv()): Path {
            val explicit = environment[ENV_FILE_VARIABLE]?.trim().orEmpty()
            if (explicit.isNotEmpty()) return Paths.get(explicit)
            val localAppData = environment["LOCALAPPDATA"]?.trim().orEmpty()
            val base = localAppData.takeIf(String::isNotBlank)?.let(Paths::get)
                ?.resolve("YeonsikIngestionConsole")
                ?: Paths.get(System.getProperty("user.home"), ".yeonsik-ingestion-console")
            return base.resolve(".env")
        }

        private fun readEnvFile(path: Path): Map<String, String> {
            if (!Files.isRegularFile(path)) return emptyMap()
            return runCatching {
                Files.readAllLines(path).mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val separator = trimmed.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val key = trimmed.substring(0, separator).trim()
                    if (!KEY_PATTERN.matches(key)) return@mapNotNull null
                    key to unquote(trimmed.substring(separator + 1).trim())
                }.toMap()
            }.getOrDefault(emptyMap())
        }

        private fun unquote(value: String): String = when {
            value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.substring(1, value.length - 1)
            value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.substring(1, value.length - 1)
            else -> value
        }

        private const val ENV_FILE_VARIABLE = "YEONSIK_INGESTION_ENV_FILE"
        private val KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
