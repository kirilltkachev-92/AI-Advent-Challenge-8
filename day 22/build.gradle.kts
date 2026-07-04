import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "advent"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Протоколы (Ollama /api/embed, DeepSeek /chat/completions) написаны вручную
    // поверх java.net.http.HttpClient — никаких LLM-SDK, чтобы весь обмен был виден.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Единственная «тяжёлая» зависимость — извлечение текста из PDF (шаг pdf → text).
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = "MainKt"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
