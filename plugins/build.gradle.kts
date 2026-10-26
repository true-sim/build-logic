import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `maven-publish`
}

// Target the oldest JVM Gradle runs on, so consumers can use any supported JDK.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.foojay.resolver)
}

// Ship the shared catalog and lombok.config inside the plugin jar so `truesim.settings` can install them.
tasks.processResources {
    from(layout.settingsDirectory.file("catalog/libs.versions.toml")) {
        into("truesim")
    }
    from(layout.settingsDirectory.file("config/lombok.config")) {
        into("truesim")
    }
}
