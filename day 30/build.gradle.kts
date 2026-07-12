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
    // Весь HTTP — вручную: сервер на com.sun.net.httpserver из JDK,
    // клиент Ollama на java.net.http. Никаких фреймворков и SDK.
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

// Один самодостаточный jar для VPS: java -jar day30-service.jar serve
tasks.register<Jar>("fatJar") {
    archiveBaseName = "day30-service"
    archiveClassifier = ""
    archiveVersion = ""
    manifest { attributes["Main-Class"] = "MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
