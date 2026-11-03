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
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis) // the purity check follows values to where they came from

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The plugins' tests: Gradle TestKit builds of small projects (their own builds under build/testkit-projects), using
// the Slang compiler this machine's builds installed in the Gradle user home, so it downloads once.
tasks.test {
    useJUnitPlatform()
    systemProperty("truesim.slang.cache", gradle.gradleUserHomeDir.resolve("truesim").path)
    systemProperty("truesim.testkit.projects", layout.buildDirectory.dir("testkit-projects").get().asFile.path)
}

// Ship the shared catalog and lombok.config inside the plugin jar so `truesim.settings` can install them,
// and record this build's coordinates so the plugins can add the matching support libraries.
tasks.processResources {
    val coordinates = mapOf("group" to project.group.toString(), "version" to project.version.toString())
    inputs.properties(coordinates)
    filesMatching("truesim/build-logic.properties") {
        expand(coordinates)
    }
    from(layout.settingsDirectory.file("catalog/libs.versions.toml")) {
        into("truesim")
    }
    from(layout.settingsDirectory.file("config/lombok.config")) {
        into("truesim")
    }
}
