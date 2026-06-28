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
    // Никаких MCP/HTTP SDK: и сам MCP-сервер, и клиент написаны вручную поверх
    // встроенных в JDK java.net.http.HttpClient и com.sun.net.httpserver.HttpServer,
    // чтобы протокол (JSON-RPC 2.0 + Streamable HTTP) был виден целиком.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
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
    // По умолчанию `gradle run` запускает приложение-агента.
    mainClass = "MainKt"
}

// REPL агента читает stdin — пробрасываем его в gradle run.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// Отдельная задача — поднять ТОЛЬКО MCP-сервер (для деплоя на VPS и проверки
// через MCP Inspector). Запуск: ./gradlew runServer  (или ./run-server.sh)
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Запустить только MCP-сервер (без агента)"
    mainClass.set("McpServerMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
