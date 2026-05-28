import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.vanniktech.maven.publish)
}

description = "Ktor client plugin for DebugBundle Android trace propagation and request capture."

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    api(project(":debugbundle-android-core"))
    api(libs.ktor.client.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.client.mock)
    testImplementation(project(":debugbundle-android-testkit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
