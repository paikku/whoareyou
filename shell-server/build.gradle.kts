plugins {
    alias(libs.plugins.android.library)
}

// Runs under app_process as the shell user. No Context, no resources, public SDK stubs + reflection only.
// The streaming logic lives in :core (pure JVM); this module adds the Android-only parts
// (later: the scrcpy-derived capture/encoder/injector) and the app_process entry point.
val gitSha = rootProject.extra["gitSha"] as String

android {
    namespace = "com.carcast.server"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
        buildConfigField("String", "SERVER_BUILD_ID", "\"$gitSha\"")
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
}
