plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

description = "Jetpack Navigation helpers for DebugBundle Android screen breadcrumbs."

android {
    namespace = "com.debugbundle.android.navigation"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

}

dependencies {
    api(project(":debugbundle-android-core"))
    api(libs.androidx.navigation.runtime.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit4)
}
