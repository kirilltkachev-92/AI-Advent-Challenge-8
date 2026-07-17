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
    // Все протоколы вручную, как весь марафон: MCP-сервер на com.sun.net.httpserver
    // из JDK, MCP-клиент и клиент DeepSeek на java.net.http. Без SDK.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
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
