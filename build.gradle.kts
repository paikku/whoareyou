plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Short git sha of the build, shown in the app and used as the shell server's build id so that
// an APK and the server it launches always match. CI passes GITHUB_SHA; local builds ask git.
val gitSha: String = System.getenv("GITHUB_SHA")?.takeIf { it.length >= 7 }?.substring(0, 7)
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
    }.getOrDefault("dev")
extra["gitSha"] = gitSha
