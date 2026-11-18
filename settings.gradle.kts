// Configuration des depots utilises pour resoudre les plugins Gradle.
pluginManagement {
    repositories {
        // Depot officiel NeoForge (plugin ModDevGradle).
        maven { url = uri("https://maven.neoforged.net/releases") }
        gradlePluginPortal()
    }
}

rootProject.name = "wakeonbutton"

// Sous-projet autonome (JDK pur) generant les paquets de reveil.
include("wake-packet")
