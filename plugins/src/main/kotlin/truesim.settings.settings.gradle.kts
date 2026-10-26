// Settings plugin: apply in settings.gradle.kts. Enables JDK auto-provisioning, registers the
// shared `truesim` version catalog (including the global dependency bundles) and installs the
// shared lombok.config in the root directory.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

fun sharedResource(name: String) = object {}.javaClass.getResource("/truesim/$name")!!.readText()

fun writeIfChanged(file: File, content: String) {
    if (!file.isFile || file.readText() != content) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}

val catalogFile = settingsDir.resolve(".gradle/truesim/libs.versions.toml")
writeIfChanged(catalogFile, sharedResource("libs.versions.toml"))

// Only overwrite a lombok.config this plugin manages (it starts with the managed header).
// Disable with `truesim.lombok-config=false`.
if (providers.gradleProperty("truesim.lombok-config").map(String::toBoolean).getOrElse(true)) {
    val lombokConfig = sharedResource("lombok.config")
    val lombokConfigFile = settingsDir.resolve("lombok.config")
    val managedHeader = lombokConfig.lineSequence().first()
    if (!lombokConfigFile.exists() || lombokConfigFile.readText().startsWith(managedHeader)) {
        writeIfChanged(lombokConfigFile, lombokConfig)
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("truesim") {
            from(files(catalogFile))
        }
    }
}
