import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    jacoco
}

val publishGroup = providers.gradleProperty("GROUP").orElse("com.debugbundle").get()
val versionName = providers.gradleProperty("VERSION_NAME").orElse("1.3.0-SNAPSHOT").get()

allprojects {
    group = publishGroup
    version = versionName
}

subprojects {
    pluginManager.apply("jacoco")
    extensions.configure(JacocoPluginExtension::class.java) {
        toolVersion = "0.8.13"
    }
    tasks.withType(Test::class.java).configureEach {
        extensions.configure(JacocoTaskExtension::class.java) {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure(PublishingExtension::class.java) {
            val publishRepository = providers.gradleProperty("debugbundlePublishRepo").orNull
            if (!publishRepository.isNullOrBlank()) {
                repositories.maven {
                    name = "smoke"
                    url = uri(publishRepository)
                }
            }

            publications.withType(MavenPublication::class.java).configureEach {
                pom {
                    name.set(project.name)
                    description.set(project.description ?: providers.gradleProperty("POM_DESCRIPTION").get())
                    inceptionYear.set("2026")
                    url.set("https://github.com/debugbundle/debugbundle-android")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("debugbundle")
                            name.set("DebugBundle")
                            url.set("https://github.com/debugbundle")
                        }
                    }
                    scm {
                        url.set("https://github.com/debugbundle/debugbundle-android")
                        connection.set("scm:git:git://github.com/debugbundle/debugbundle-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/debugbundle/debugbundle-android.git")
                    }
                }
            }
        }
    }
}

val firstPartySourceDirectories = subprojects.map {
    it.layout.projectDirectory.dir("src/main/kotlin")
}
val kotlinProductionProjects = subprojects.filter {
    it.layout.projectDirectory.dir("src/main/kotlin").asFile.isDirectory
}

val firstPartyClassDirectories = subprojects.flatMap { subproject ->
    listOf(
        subproject.layout.buildDirectory.dir("classes/kotlin/main").map {
            fileTree(it) {
                // Generated version metadata is validated by package smokes and
                // has no committed source file to measure.
                exclude("**/DebugBundleBuildInfo*")
            }
        },
        subproject.layout.buildDirectory.dir("tmp/kotlin-classes/debug").map {
            fileTree(it) {
                exclude("**/DebugBundleBuildInfo*")
            }
        },
        subproject.layout.buildDirectory.dir(
            "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        ).map {
            fileTree(it) {
                exclude("**/DebugBundleBuildInfo*")
            }
        },
    )
}

tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Merges unit-test coverage for every publishable Android SDK module."
    dependsOn(subprojects.map { it.tasks.withType(Test::class.java) })

    executionData.setFrom(
        fileTree(rootDir) {
            include("**/build/jacoco/*.exec")
            include("**/build/outputs/unit_test_code_coverage/**/*.exec")
        },
    )
    sourceDirectories.setFrom(firstPartySourceDirectories)
    classDirectories.setFrom(firstPartyClassDirectories)

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.register("verifyKotlinMetadataCompatibility") {
    group = "verification"
    description = "Verifies published Kotlin bytecode remains consumable by the supported Android/R8 matrix."
    dependsOn(
        kotlinProductionProjects.flatMap { subproject ->
            subproject.tasks.matching {
                it.name == "compileKotlin" || it.name == "compileDebugKotlin"
            }
        },
    )

    doLast {
        val javap = file("${System.getProperty("java.home")}/bin/javap")
        if (!javap.isFile) {
            throw GradleException("Unable to locate javap at $javap")
        }

        kotlinProductionProjects.forEach { subproject ->
            val classRoots = listOf(
                subproject.layout.buildDirectory.dir("classes/kotlin/main").get().asFile,
                subproject.layout.buildDirectory
                    .dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
                    .get()
                    .asFile,
                subproject.layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile,
            )
            val representativeClass = classRoots.asSequence()
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().asSequence() }
                .firstOrNull {
                    it.isFile &&
                        it.extension == "class" &&
                        !it.name.startsWith("DebugBundleBuildInfo")
                }
                ?: throw GradleException(
                    "No production Kotlin class was produced for ${subproject.path}",
                )

            val process = ProcessBuilder(javap.absolutePath, "-v", representativeClass.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException(
                    "javap failed for ${subproject.path}: $output",
                )
            }

            val metadataVersion = Regex("""mv=\[\s*(\d+),\s*(\d+),\s*(\d+)]""")
                .find(output)
                ?.destructured
                ?.let { (major, minor, patch) ->
                    Triple(major.toInt(), minor.toInt(), patch.toInt())
                }
                ?: throw GradleException(
                    "No Kotlin metadata version found in ${representativeClass.absolutePath}",
                )
            if (
                metadataVersion.first > 2 ||
                (metadataVersion.first == 2 && metadataVersion.second > 1)
            ) {
                throw GradleException(
                    "${subproject.path} emitted Kotlin metadata " +
                        "${metadataVersion.first}.${metadataVersion.second}.${metadataVersion.third}; " +
                        "the supported consumer matrix requires 2.1 or older.",
                )
            }
        }
    }
}

tasks.register("printVersion") {
    group = "help"
    description = "Prints the resolved SDK version."
    doLast {
        println(versionName)
    }
}
