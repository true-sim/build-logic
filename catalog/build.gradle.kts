// Shared version catalog consumed by true-sim projects as `truesim`.
plugins {
    `version-catalog`
    `maven-publish`
}

catalog {
    versionCatalog {
        from(files("libs.versions.toml"))
    }
}

publishing {
    publications {
        register<MavenPublication>("catalog") {
            artifactId = "catalog"
            from(components["versionCatalog"])
        }
    }
}
