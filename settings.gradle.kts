import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "debugbundle-android"

include(":debugbundle-android")
include(":debugbundle-android-bom")
include(":debugbundle-android-compose")
include(":debugbundle-android-core")
include(":debugbundle-android-ktor-client")
include(":debugbundle-android-navigation")
include(":debugbundle-android-okhttp")
include(":debugbundle-android-testkit")
include(":debugbundle-android-timber")
