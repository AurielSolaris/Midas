plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm("desktop")
    androidLibrary {
        namespace = "app.auriel.midas.ui"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":player-core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(libs.material.icons.core)
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.common)
            implementation(libs.jna.platform) // keep the screen awake on Windows
        }
        getByName("androidMain").dependencies {
            implementation(libs.activity.compose)
            implementation(libs.media3.ui)
            implementation(project(":player-android"))
        }
    }
}
