// Base conventions for every Java project: toolchain, compiler flags, JUnit, global dependencies
// and the platform-independence check.
import com.truesim.build.strict.BuildLogic
import com.truesim.build.strict.CoverageExtension
import com.truesim.build.strict.DeterminismCheck
import com.truesim.build.strict.DeterministicExtension

plugins {
    java
    jacoco
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

// Platform independence: production code may not use java.lang.Math or java.lang.StrictMath, except their
// exactly specified methods (abs, min, max, sqrt, floor, ...). StrictMath is allowed in @AllowStrictMath code.
// Tests are not checked. Forbid more APIs with `deterministic { forbiddenApis.add("java.lang.System#nanoTime") }`.
val deterministic = extensions.create<DeterministicExtension>("deterministic")

dependencies {
    "compileOnly"(BuildLogic.library("annotations"))
}

val checkPlatformIndependence = tasks.register<DeterminismCheck>("checkPlatformIndependence") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that production code uses only platform-independent APIs."
    classes.from(sourceSets.main.map { it.output.classesDirs })
    forbiddenApis.addAll("java.lang.Math", "java.lang.StrictMath")
    forbiddenApis.addAll(deterministic.forbiddenApis)
    allowedApis = DeterminismCheck.EXACT_MATH
    report = layout.buildDirectory.file("reports/truesim/platform-independence.txt")
}

tasks.named("check") {
    dependsOn(checkPlatformIndependence)
}

// Test coverage: JaCoCo reports for every test run, and `check` fails below the minimum line and branch coverage.
// Raise it per module with `coverage { minimum = 1.0 }`.
val coverage = extensions.create<CoverageExtension>("coverage").apply {
    minimum.convention(0.8)
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = coverage.minimum.get().toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = coverage.minimum.get().toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
