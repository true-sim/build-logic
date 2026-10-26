plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
}

// Usable from any project on Java 17 or newer.
tasks.compileJava {
    options.release = 17
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
