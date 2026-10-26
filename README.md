# build-logic

Shared Gradle convention plugins and version catalog for true-sim projects.

## Contents

| Plugin ID | Applies to | What it does |
|---|---|---|
| `truesim.settings` | `settings.gradle.kts` | Foojay toolchain resolver (auto-downloads JDKs) and Maven Central |
| `truesim.java-conventions` | any Java project | Java toolchain (default 25), UTF-8, `-Xlint:all -parameters`, sources jar, reproducible archives, JUnit 5 |
| `truesim.java-library-conventions` | libraries | Above + `java-library`, javadoc jar, publishing |
| `truesim.application-conventions` | apps | Above + `application` |
| `truesim.publishing-conventions` | anything published | `maven-publish` to the project's GitHub Packages registry |

The `catalog` module publishes a shared version catalog (`com.truesim.build:catalog`).

### Settings

| Gradle property | Default | Purpose |
|---|---|---|
| `truesim.java.version` | `25` | Java toolchain version |
| `truesim.github.repo` | root project name | GitHub repo used for the publishing URL |

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

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/true-sim/build-logic")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
    versionCatalogs {
        create("truesim") {
            from("com.truesim.build:catalog:0.1.0")
        }
    }
}
```

Then in a module:

```kotlin
plugins {
    id("truesim.java-library-conventions")
}

dependencies {
    testImplementation(platform(truesim.junit.bom))
    testImplementation(truesim.bundles.testing)
    testRuntimeOnly(truesim.junit.platform.launcher)
}
```

## Releasing

Push a tag `vX.Y.Z`; the Publish workflow publishes version `X.Y.Z` to GitHub Packages.
