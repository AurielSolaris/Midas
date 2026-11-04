rootProject.name = "midas"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// :player-core      - engine interface, track model, subtitle parsers and sources, settings (KMP, no UI)
// :player-ffmpeg    - desktop playback engine on FFmpeg (JavaCPP)
// :player-ui        - the player screen, controls, menus, subtitle finder, themes (KMP Compose)
// :midasApp-desktop - Midas desktop entry point: window, library, single instance, file associations
include(":player-core", ":player-ffmpeg", ":player-ui", ":midasApp-desktop", ":player-android", ":midasApp-android")
