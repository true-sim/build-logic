// Settings plugin: apply in settings.gradle.kts. Enables JDK auto-provisioning and
// registers the shared `truesim` version catalog (including the global dependency bundles).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

val sharedCatalog = object {}.javaClass.getResource("/truesim/libs.versions.toml")!!.readBytes()
val catalogFile = settingsDir.resolve(".gradle/truesim/libs.versions.toml")
if (!catalogFile.isFile || !catalogFile.readBytes().contentEquals(sharedCatalog)) {
    catalogFile.parentFile.mkdirs()
    catalogFile.writeBytes(sharedCatalog)
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
