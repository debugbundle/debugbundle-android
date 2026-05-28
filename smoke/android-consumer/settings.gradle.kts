pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(org.gradle.api.initialization.resolve.RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        providers.gradleProperty("debugbundleRepoUrl").orNull?.let { debugbundleRepoUrl ->
            maven(url = uri(debugbundleRepoUrl))
        }
    }
}

rootProject.name = "debugbundle-android-smoke"
