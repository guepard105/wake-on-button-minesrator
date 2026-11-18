plugins {
    `java-library`
    // Version declaree dans le build.gradle.kts racine.
    id("com.gradleup.shadow")
}

group = "com.minestrator.wakeonbutton"
version = "2.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Aucune dependance externe : uniquement le JDK.
dependencies {
}

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes("Main-Class" to "com.minestrator.wakeonbutton.wakepacket.WakePacketGenerator")
    }
}
