import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(project(":player-ui"))
    implementation(project(":player-ffmpeg"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
}


val videoExtensions = listOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "wmv", "flv", "ogv")
val subtitleExtensions = listOf("srt", "ass", "vtt")

compose.desktop {
    application {
        mainClass = "app.auriel.midas.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g", "-XX:+UseG1GC")
        nativeDistributions {
            // libass and its DLLs (Windows; see tools/fetch_libass.py). Found at runtime through the
            // compose.application.resources.dir system property.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Midas"
            // MSI versions must be numeric; the app and release version is alpha-0.1.
            packageVersion = "1.0.4"
            description = "Midas video player"
            vendor = "Auriel"
            modules("java.instrument", "java.management", "java.net.http", "jdk.unsupported", "jdk.httpserver", "java.naming", "java.desktop")

            for (ext in videoExtensions) {
                fileAssociation(mimeType = if (ext == "mkv") "video/x-matroska" else "video/$ext", extension = ext, description = "Video file")
            }
            for (ext in subtitleExtensions) {
                fileAssociation(mimeType = "text/x-$ext", extension = ext, description = "Subtitle file")
            }

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Midas"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "3b7e9a52-6c1d-4f0e-9a8b-5d2c7e1f4a60"
            }
            macOS {
                bundleID = "app.auriel.midas"
                iconFile.set(project.file("icons/icon.icns"))
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}
