plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.carcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carcast"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Passed to the shell server on launch; the server refuses a mismatched id.
        buildConfigField("String", "SERVER_BUILD_ID", "\"${versionName}-${System.currentTimeMillis() / 1000}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
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
    implementation(project(":adb"))
    implementation(project(":shell-server"))
    implementation(project(":mux"))
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
    inputs.files(webDir.resolve("package.json"), rootProject.file("package.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/web"))
    val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
    commandLine(npm, "run", "build", "--workspace", "web")
}
tasks.named("preBuild") { dependsOn(buildWeb) }
