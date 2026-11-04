plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    androidLibrary {
        namespace = "app.auriel.midas.android.player"
        compileSdk = 36
        minSdk = 26
    }
    sourceSets {
        commonMain.dependencies { api(project(":player-core")) }
        getByName("androidMain").dependencies {
            api(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
        }
    }
}
