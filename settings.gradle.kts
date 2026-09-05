pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Kadb's SPAKE2 dependency (com.github.Flyfish233:spake2-java) is published on JitPack only.
        maven("https://jitpack.io") { content { includeGroup("com.github.Flyfish233") } }
    }
}
rootProject.name = "carcast"
include(":app", ":adb", ":shell-server", ":mux", ":core")
