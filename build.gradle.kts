// Top-level build file where you can add configuration options common to all sub-projects/modules.

// This project uses the Gradle version catalog located at gradle/libs.versions.toml.

// Keep this file minimal; module configuration lives under app/build.gradle.kts.

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

