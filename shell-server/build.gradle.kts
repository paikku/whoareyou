plugins {
    alias(libs.plugins.android.library)
}

// Runs under app_process as the shell user. No Context, no resources, public SDK stubs + reflection only.
android {
    namespace = "com.carcast.server"
    compileSdk = 36
    defaultConfig { minSdk = 34 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
