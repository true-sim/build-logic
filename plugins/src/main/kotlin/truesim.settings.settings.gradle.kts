// Settings plugin: apply in settings.gradle.kts so JDK toolchains can be auto-provisioned.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
