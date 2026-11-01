import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    alias(libs.plugins.vanniktech.maven.publish)
}

description = "Core DebugBundle Android client, transport, queue, redaction, and probe runtime."

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val generatedKotlinDir = layout.buildDirectory.dir("generated/source/build-info/kotlin")

val generateBuildInfo by tasks.registering {
    val outputDir = generatedKotlinDir.map { it.dir("com/debugbundle/android/internal") }
    inputs.property("sdkVersion", project.version.toString())
    outputs.dir(generatedKotlinDir)

    doLast {
        val targetDir = outputDir.get().asFile
        targetDir.mkdirs()
        targetDir.resolve("DebugBundleBuildInfo.kt").writeText(
            """
            package com.debugbundle.android.internal

            internal object DebugBundleBuildInfo {
                const val SDK_VERSION: String = "${project.version}"
            }
            """.trimIndent() + "\n",
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generatedKotlinDir)
}

tasks.withType(KotlinCompile::class.java).configureEach {
    dependsOn(generateBuildInfo)
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateBuildInfo)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":debugbundle-android-testkit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Standalone HotSpot qualification; no benchmark classes enter the Android artifact.
val evidencePerformance by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[evidencePerformance.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies.add(evidencePerformance.implementationConfigurationName, project(":debugbundle-android-testkit"))
tasks.register<JavaExec>("evidencePerformance") {
    group = "verification"
    classpath = evidencePerformance.runtimeClasspath
    mainClass.set("com.debugbundle.android.perf.EvidenceCaptureKt")
}
