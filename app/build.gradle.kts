import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

private val dotenvKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun readDotEnv(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()

    val values = linkedMapOf<String, String>()
    file.useLines { lines ->
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.removePrefix("\uFEFF").trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val assignment = line.removePrefix("export ").trim()
            val separator = assignment.indexOf('=')
            require(separator > 0) {
                "${file.name}:${index + 1} must use KEY=VALUE syntax"
            }
            val key = assignment.substring(0, separator).trim()
            require(dotenvKeyPattern.matches(key)) {
                "${file.name}:${index + 1} has an invalid key"
            }
            check(key !in values) {
                "${file.name}:${index + 1} duplicates $key"
            }

            val rawValue = assignment.substring(separator + 1).trim()
            val value = when {
                rawValue.startsWith("\"") -> {
                    require(rawValue.length >= 2 && rawValue.endsWith("\"")) {
                        "${file.name}:${index + 1} has an unterminated double-quoted value"
                    }
                    rawValue.substring(1, rawValue.length - 1)
                        .replace("\\\\", "\\")
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                }
                rawValue.startsWith("'") -> {
                    require(rawValue.length >= 2 && rawValue.endsWith("'")) {
                        "${file.name}:${index + 1} has an unterminated single-quoted value"
                    }
                    rawValue.substring(1, rawValue.length - 1)
                }
                else -> rawValue.replace(Regex("\\s+#.*$"), "").trimEnd()
            }
            values[key] = value
        }
    }
    return values
}

private fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""

private fun looksLikeServiceRoleKey(key: String): Boolean {
    if (key.startsWith("sb_secret_", ignoreCase = true)) return true
    val payload = key.split('.').takeIf { it.size == 3 }?.get(1) ?: return false
    return runCatching {
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
            .contains(Regex("\"role\"\\s*:\\s*\"service_role\"", RegexOption.IGNORE_CASE))
    }.getOrDefault(false)
}

private fun validNutritionUrl(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl.trim().trimEnd('/')) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.fragment == null &&
        uri.rawQuery == null
}

private val fallbackGeminiModel = "gemini-3.5-flash-lite"

val dotEnvFile = rootProject.file(".env")
val dotEnv = readDotEnv(dotEnvFile)
val requiredDotEnvKeys = setOf(
    "NUTRITION_SUPABASE_URL",
    "NUTRITION_SUPABASE_ANON_KEY",
    "EMAIL",
    "PASSWORD",
    "GEMINI_API_KEY",
    "GEMINI_MODEL",
)
require(dotEnvFile.isFile) {
    "Root .env is required for this build. Copy .env.example and provide all required values."
}
require(requiredDotEnvKeys.all { it in dotEnv }) {
    "Root .env must define all required keys: ${requiredDotEnvKeys.joinToString()}."
}
require(requiredDotEnvKeys.all { !dotEnv[it].isNullOrEmpty() }) {
    "Root .env required values must not be empty."
}
val configuredNutritionUrl = dotEnv["NUTRITION_SUPABASE_URL"].orEmpty().trim().trimEnd('/')
val configuredNutritionKey = dotEnv["NUTRITION_SUPABASE_ANON_KEY"].orEmpty().trim()
val configuredNutritionEmail = dotEnv["EMAIL"].orEmpty()
val configuredNutritionPassword = dotEnv["PASSWORD"].orEmpty()
val configuredPriceTraceUrl = dotEnv["PRICETRACE_SUPABASE_URL"].orEmpty().trim().trimEnd('/')
val configuredPriceTraceKey = dotEnv["PRICETRACE_SUPABASE_PUBLISHABLE_KEY"].orEmpty().trim()
val configuredGeminiApiKey = dotEnv["GEMINI_API_KEY"].orEmpty().trim()
val configuredGeminiModel = dotEnv["GEMINI_MODEL"].orEmpty().trim()

val hasNutritionPair = configuredNutritionUrl.isNotBlank() && configuredNutritionKey.isNotBlank()
val defaultNutritionUrl = if (!hasNutritionPair) {
    if (configuredNutritionUrl.isNotBlank() || configuredNutritionKey.isNotBlank()) {
        logger.warn("PriceTrace: NUTRITION_SUPABASE_URL and NUTRITION_SUPABASE_ANON_KEY must be provided together; ignoring both")
    }
    ""
} else {
    require(validNutritionUrl(configuredNutritionUrl)) {
        "NUTRITION_SUPABASE_URL must be an HTTPS URL without query, fragment, or user info"
    }
    require(configuredNutritionKey.length in 20..4096 && configuredNutritionKey.none(Char::isWhitespace)) {
        "NUTRITION_SUPABASE_ANON_KEY must be a non-whitespace publishable/anon key"
    }
    require(!looksLikeServiceRoleKey(configuredNutritionKey)) {
        "NUTRITION_SUPABASE_ANON_KEY must not be a service_role/secret key"
    }
    configuredNutritionUrl
}
val defaultNutritionKey = if (defaultNutritionUrl.isBlank()) "" else configuredNutritionKey
val hasPriceTracePair = configuredPriceTraceUrl.isNotBlank() && configuredPriceTraceKey.isNotBlank()
val defaultPriceTraceUrl = if (!hasPriceTracePair) {
    if (configuredPriceTraceUrl.isNotBlank() || configuredPriceTraceKey.isNotBlank()) {
        logger.warn(
            "PriceTrace: PRICETRACE_SUPABASE_URL and PRICETRACE_SUPABASE_PUBLISHABLE_KEY " +
                "must be provided together; ignoring both",
        )
    }
    ""
} else {
    require(validNutritionUrl(configuredPriceTraceUrl)) {
        "PRICETRACE_SUPABASE_URL must be an HTTPS URL without query, fragment, or user info"
    }
    require(configuredPriceTraceKey.length in 20..4096 && configuredPriceTraceKey.none(Char::isWhitespace)) {
        "PRICETRACE_SUPABASE_PUBLISHABLE_KEY must be a non-whitespace publishable/anon key"
    }
    require(!looksLikeServiceRoleKey(configuredPriceTraceKey)) {
        "PRICETRACE_SUPABASE_PUBLISHABLE_KEY must not be a service_role/secret key"
    }
    configuredPriceTraceUrl
}
val defaultPriceTraceKey = if (defaultPriceTraceUrl.isBlank()) "" else configuredPriceTraceKey
val defaultGeminiModel = configuredGeminiModel
    .takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
    ?: fallbackGeminiModel

if (dotEnvFile.isFile) {
    logger.lifecycle(
        "PriceTrace: loaded all ${dotEnv.size} .env values into the application build. " +
            "Nutrition=${defaultNutritionUrl.isNotBlank()}, Gemini=${configuredGeminiApiKey.isNotBlank()}, " +
            "account=${configuredNutritionEmail.isNotBlank() && configuredNutritionPassword.isNotBlank()}, model=$defaultGeminiModel.",
    )
} else {
    logger.lifecycle("PriceTrace: .env not found")
}

android {
    namespace = "com.pricetrace.receiptocr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pricetrace.receiptocr"
        minSdk = 24
        targetSdk = 37
        versionCode = 18
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_NUTRITION_SUPABASE_URL", buildConfigString(defaultNutritionUrl))
        buildConfigField("String", "DEFAULT_NUTRITION_SUPABASE_PUBLISHABLE_KEY", buildConfigString(defaultNutritionKey))
        buildConfigField("String", "DEFAULT_PRICETRACE_SUPABASE_URL", buildConfigString(defaultPriceTraceUrl))
        buildConfigField("String", "DEFAULT_PRICETRACE_SUPABASE_PUBLISHABLE_KEY", buildConfigString(defaultPriceTraceKey))
        buildConfigField("String", "DEFAULT_GEMINI_MODEL", buildConfigString(defaultGeminiModel))
        buildConfigField("String", "DEFAULT_GEMINI_API_KEY", buildConfigString(configuredGeminiApiKey))
        buildConfigField("String", "DEFAULT_NUTRITION_EMAIL", buildConfigString(configuredNutritionEmail))
        buildConfigField("String", "DEFAULT_NUTRITION_PASSWORD", buildConfigString(configuredNutritionPassword))
        dotEnv.forEach { (key, value) ->
            buildConfigField("String", "ENV_$key", buildConfigString(value))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":receipt-scanner"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
