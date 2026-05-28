plugins {
    id("com.android.application") version "9.2.1"
}

val debugbundleVersion = providers.gradleProperty("debugbundleVersion").orNull
    ?: error("Missing -PdebugbundleVersion for smoke test.")

android {
    namespace = "com.debugbundle.smoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.debugbundle.smoke"
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform("com.debugbundle:debugbundle-android-bom:$debugbundleVersion"))
    implementation("com.debugbundle:debugbundle-android")
    implementation("com.debugbundle:debugbundle-android-okhttp")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
