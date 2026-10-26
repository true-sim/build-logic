// Base conventions for every Java project: toolchain, compiler flags, JUnit, global dependencies.
plugins {
    java
}

// Override per project with `truesim.java.version=<n>` in gradle.properties.
val javaVersion = providers.gradleProperty("truesim.java.version").map(String::toInt).orElse(27)

java {
    toolchain {
        languageVersion = javaVersion.map(JavaLanguageVersion::of)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-parameters"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val catalogs = extensions.getByType<VersionCatalogsExtension>()

testing {
    suites {
        named<JvmTestSuite>("test") {
            val junitVersion = catalogs.find("truesim").flatMap { it.findVersion("junit") }
            if (junitVersion.isPresent) useJUnitJupiter(junitVersion.get().requiredVersion) else useJUnitJupiter()
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Adds every `global-<configuration>` bundle from every version catalog to that configuration.
// Disable for a project with `truesim.global-dependencies=false`.
val globalBundles = mapOf(
    "global-implementation" to "implementation",
    "global-api" to "api",
    "global-compile-only" to "compileOnly",
    "global-annotation-processor" to "annotationProcessor",
    "global-runtime-only" to "runtimeOnly",
    "global-test-implementation" to "testImplementation",
    "global-test-compile-only" to "testCompileOnly",
    "global-test-annotation-processor" to "testAnnotationProcessor",
    "global-test-runtime-only" to "testRuntimeOnly",
)

if (providers.gradleProperty("truesim.global-dependencies").map(String::toBoolean).getOrElse(true)) {
    catalogs.forEach { catalog ->
        globalBundles.forEach { (bundle, configuration) ->
            catalog.findBundle(bundle).ifPresent { deps ->
                // `api` only exists once java-library is applied, so match lazily.
                configurations.matching { it.name == configuration }.configureEach {
                    dependencies.addAllLater(deps.map { it.map { dep -> project.dependencies.create(dep) } })
                }
            }
        }
    }
}
