plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Pure-JVM streaming core: HTTP/WebSocket server, media fan-out, clip replay, session logic.
// Shared by the app (optional in-process server) and by the shell-uid server started through
// app_process. No Android dependencies, so it runs and is tested on a plain JVM:
//   ./gradlew :core:run --args="dev port=3333 apk=app/build/outputs/apk/debug/app-debug.apk"
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.carcast.core.ServerMain")
}

dependencies {
    api(project(":mux"))   // fMP4 muxer for the live encoder output (EncodedH264Sink)
    testImplementation(libs.junit)
}
