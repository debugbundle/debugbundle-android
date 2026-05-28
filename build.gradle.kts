import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

val publishGroup = providers.gradleProperty("GROUP").orElse("com.debugbundle").get()
val versionName = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

allprojects {
    group = publishGroup
    version = versionName
}

subprojects {
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
                            name.set("GNU Affero General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
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

tasks.register("printVersion") {
    group = "help"
    description = "Prints the resolved SDK version."
    doLast {
        println(versionName)
    }
}
