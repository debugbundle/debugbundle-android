plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

description = "Android runtime bootstrap, lifecycle capture, ANR replay, and WorkManager flushing for DebugBundle."

android {
    namespace = "com.debugbundle.android.runtime"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit4)
    testImplementation(project(":debugbundle-android-testkit"))
}
