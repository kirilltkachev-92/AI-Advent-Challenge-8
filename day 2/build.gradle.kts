import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "advent"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
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

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "day2-deepseek-chat"
            packageVersion = "1.0.0"
        }
    }
}

tasks.register<JavaExec>("runCompare") {
    group = "application"
    description = "Run Day 2 baseline vs restricted API comparison (CLI)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("CompareExperimentKt")
    standardInput = System.`in`
}
