# build-logic

Shared Gradle convention plugins and version catalog for true-sim projects.

## Plugins

| Plugin ID | Applies to | What it does |
|---|---|---|
| `truesim.settings` | `settings.gradle.kts` | Foojay toolchain resolver (auto-downloads JDKs), Maven Central, and registers the shared `truesim` version catalog |
| `truesim.java-conventions` | any Java project | Java 27 toolchain, UTF-8, `-Xlint:all -parameters`, sources jar, reproducible archives, JUnit, **global dependencies** |
| `truesim.java-library-conventions` | libraries | Above + `java-library`, javadoc jar, publishing |
| `truesim.application-conventions` | apps | Above + `application` |
| `truesim.publishing-conventions` | anything published | `maven-publish` to the project's GitHub Packages registry |

### Settings

| Gradle property | Default | Purpose |
|---|---|---|
| `truesim.java.version` | `27` | Java toolchain version |
| `truesim.global-dependencies` | `true` | Set to `false` to skip global dependencies for a project |
| `truesim.github.repo` | root project name | GitHub repo used for the publishing URL |

## Global dependencies

Every project that applies `truesim.java-conventions` (directly or through the library/application
plugins) gets the `global-<configuration>` bundles from **every** version catalog in the build
added to the matching configuration:

| Bundle | Configuration |
|---|---|
| `global-api` | `api` (libraries only) |
| `global-implementation` | `implementation` |
| `global-compile-only` | `compileOnly` |
| `global-annotation-processor` | `annotationProcessor` |
| `global-runtime-only` | `runtimeOnly` |
| `global-test-implementation` | `testImplementation` |
| `global-test-compile-only` | `testCompileOnly` |
| `global-test-annotation-processor` | `testAnnotationProcessor` |
| `global-test-runtime-only` | `testRuntimeOnly` |

The shared catalog ([catalog/libs.versions.toml](catalog/libs.versions.toml)) defines the
organization-wide defaults:

- **Lombok** for `compileOnly`, `annotationProcessor`, `testCompileOnly` and `testAnnotationProcessor`
- **SLF4J API** for `implementation`: the logging API all code uses
- **Logback** for `testRuntimeOnly`, so test output is logged
- **AssertJ** for `testImplementation`

No logging backend is enforced at runtime. Applications choose one themselves, typically Logback:

```kotlin
dependencies {
    runtimeOnly(truesim.logback.classic)
}
```

To change them for everyone, edit that file and release a new version. To add more for a
single repository, define the same bundle names in that repository's own
`gradle/libs.versions.toml`. Its bundles are added on top of the shared ones:

```toml
[libraries]
commons-lang3 = "org.apache.commons:commons-lang3:3.17.0"

[bundles]
global-implementation = ["commons-lang3"]
```

## Using it

### Option A: composite build (no credentials needed)

Check this repo out next to your project (or as a git submodule) and in `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../build-logic")
}
plugins {
    id("truesim.settings")
}
```

### Option B: from GitHub Packages

GitHub Packages requires authentication even for reads. Put `gpr.user` (your GitHub username) and
`gpr.key` (a token with `read:packages`) in `~/.gradle/gradle.properties`, then:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.pkg.github.com/true-sim/build-logic")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}
plugins {
    id("truesim.settings") version "0.1.0"
}
```

Either way, `truesim.settings` registers the shared catalog as `truesim`. Then in a module:

```kotlin
plugins {
    id("truesim.java-library-conventions")
}

dependencies {
    // Lombok, SLF4J, AssertJ and JUnit are already there.
    implementation(truesim.some.other.lib)
}
```

The catalog is also published on its own as `com.truesim.build:catalog`, for builds that don't
use the settings plugin.

## Releasing

Push a tag `vX.Y.Z`; the Publish workflow publishes version `X.Y.Z` to GitHub Packages.
