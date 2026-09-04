plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Pure-JVM fMP4 muxer shared by the app and by the test-clip generator.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.carcast.mux.MuxCliKt")
}

dependencies {
    testImplementation(libs.junit)
}
