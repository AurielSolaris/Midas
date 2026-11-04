plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Only the current OS's FFmpeg natives go into each build (and so each installer).
val nativeClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x86_64" }
    when {
        "win" in os -> "windows-$arch"
        "mac" in os || "darwin" in os -> "macosx-$arch"
        else -> "linux-$arch"
    }
}

dependencies {
    api(project(":player-core"))
    implementation(libs.ffmpeg)
    implementation(libs.javacpp)
    implementation(libs.jna.platform) // libass
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(nativeClassifier) })
    runtimeOnly(variantOf(libs.javacpp) { classifier(nativeClassifier) })
    testImplementation(kotlin("test"))
}


tasks.test {
    testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
