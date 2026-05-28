plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.maven.publish)
}

description = "Version-aligned BOM for the DebugBundle Android SDK package family."

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":debugbundle-android"))
        api(project(":debugbundle-android-compose"))
        api(project(":debugbundle-android-core"))
        api(project(":debugbundle-android-ktor-client"))
        api(project(":debugbundle-android-navigation"))
        api(project(":debugbundle-android-okhttp"))
        api(project(":debugbundle-android-testkit"))
        api(project(":debugbundle-android-timber"))
    }
}
