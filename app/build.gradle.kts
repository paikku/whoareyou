plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val gitSha = rootProject.extra["gitSha"] as String

android {
    namespace = "com.carcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carcast"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Short git sha: shown in the app, and passed to the shell server, which refuses a mismatch.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    // A fixed debug key committed to the repo so every CI build carries the same signature
    // and the APK can be installed over a previous build without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").assets.srcDirs("../tools/clips/assets")
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adb"))
    implementation(project(":shell-server"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// Build the web client into assets/web before packaging. Skip with -PskipWeb.
val webDir = rootProject.file("web")
val buildWeb = tasks.register<Exec>("buildWeb") {
    onlyIf { !project.hasProperty("skipWeb") }
    workingDir = rootProject.projectDir
    inputs.dir(webDir.resolve("src"))
    // public/ is copied verbatim into the bundle, so a page added there (drive-check.html) must
    // invalidate this task too — otherwise the APK keeps the old assets and the phone serves 404.
    inputs.dir(webDir.resolve("public"))
    inputs.files(webDir.resolve("package.json"), rootProject.file("package.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/web"))
    val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
    commandLine(npm, "run", "build", "--workspace", "web")
}
tasks.named("preBuild") { dependsOn(buildWeb) }
