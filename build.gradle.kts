plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.minestrator.wakeonbutton"
version = "2.0.0"

// Nom du jar produit : wakeupbutton-2.0.0.jar
base {
    archivesName = "wakeupbutton"
}

repositories {
    mavenCentral()
    // Depot des artefacts NeoForge.
    maven { url = uri("https://maven.neoforged.net/releases") }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

neoForge {
    // Version NeoForge stable pour Minecraft 1.21.1.
    version = "21.1.267"
}

// Configuration dediee aux dependances a embarquer dans le jar (le reste est fourni par le jeu).
val shade: Configuration by configurations.creating

dependencies {
    // Guava est fournie par NeoForge (version verrouillee par Minecraft) : pas de BOM ici.
    implementation("com.google.code.gson:gson:2.10.1")
    // Seules ces dependances sont incluses dans le jar shadow (pas le runtime NeoForge).
    shade("com.google.code.gson:gson:2.10.1")
}

tasks.shadowJar {
    // Le jar shadow remplace le jar standard (pas de suffixe "-all").
    archiveClassifier.set("")
    // N'embarque que les dependances dediees (NeoForge/Minecraft sont fournis a l'execution).
    configurations = listOf(shade)
    // Fusionne les fichiers de service (META-INF/services) des dependances.
    mergeServiceFiles()
    // Deplace les librairies embarquees pour eviter tout conflit avec le jeu.
    relocate("com.google.gson", "com.minestrator.wakeonbutton.shaded.gson")
    relocate("com.google.common", "com.minestrator.wakeonbutton.shaded.guava")
    manifest {
        attributes("Main-Class" to "com.minestrator.wakeonbutton.WakeOnButtonMod")
    }
}

// Le jar standard porterait le meme nom que le jar shadow : on le desactive.
tasks.jar {
    enabled = false
}
