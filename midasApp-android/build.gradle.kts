plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "app.auriel.midas.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.auriel.midas"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "alpha-0.2"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":player-ui"))
    implementation(project(":player-android"))
    implementation(libs.activity.compose)
    implementation(libs.ktor.client.cio)
}
