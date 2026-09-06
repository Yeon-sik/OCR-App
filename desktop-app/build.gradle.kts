plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.yeonsik.ingestion.desktop.MainKt"
        nativeDistributions {
            packageName = "Yeonsik Ingestion Console"
            packageVersion = "0.1.0"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
