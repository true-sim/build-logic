// Conventions for published Java libraries.
plugins {
    id("truesim.java-conventions")
    `java-library`
    id("truesim.publishing-conventions")
}

java {
    withJavadocJar()
}
