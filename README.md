# build-logic

Shared Gradle convention plugins and version catalog for true-sim projects.

## Plugins

| Plugin ID | Applies to | What it does |
|---|---|---|
| `truesim.settings` | `settings.gradle.kts` | Foojay toolchain resolver (auto-downloads JDKs), Maven Central, registers the shared `truesim` version catalog and installs the shared `lombok.config` |
| `truesim.java-conventions` | any Java project | Java 27 toolchain, UTF-8, `-Xlint:all -parameters`, sources jar, reproducible archives, JUnit, **global dependencies**, **platform-independence check**, **coverage rule** |
| `truesim.java-library-conventions` | libraries | Above + `java-library`, javadoc jar, publishing |
| `truesim.application-conventions` | apps | Above + `application` |
| `truesim.allocation-free` | strict modules | **Allocation-free check** at build time, plus `AllocationAssertions` for tests |
| `truesim.publishing-conventions` | anything published | `maven-publish` to the project's GitHub Packages registry |

The `annotations` (`com.truesim.build:annotations`) and `testing` (`com.truesim.build:testing`) libraries
are added automatically where needed.

### Settings

| Gradle property | Default | Purpose |
|---|---|---|
| `truesim.java.version` | `27` | Java toolchain version |
| `truesim.global-dependencies` | `true` | Set to `false` to skip global dependencies for a project |
| `truesim.lombok-config` | `true` | Set to `false` to stop installing the shared `lombok.config` |
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

## Test coverage (all modules)

Every module records JaCoCo coverage when its tests run (HTML and XML reports under
`build/reports/jacoco`), and `check` fails when line or branch coverage is below the minimum.
The default is 80%; raise or lower it per module:

```kotlin
coverage {
    minimum = 1.0   // 100% of lines and branches
}
```

## Platform independence (all modules)

Every module using `truesim.java-conventions` runs `checkPlatformIndependence` as part of `check`.
It scans the compiled production classes (not tests) and fails the build on:

- **`java.lang.Math`**: its results may differ between platforms (intrinsics may be off by an ulp).
- **`java.lang.StrictMath`**, except in code annotated with `@AllowStrictMath`. Use that to compare
  your own math functions against it.
- Any APIs added with `deterministic { forbiddenApis.add("java.lang.System#nanoTime") }`.

Methods whose results are exactly specified, and so identical on every platform, stay allowed on
both classes: `abs`, `min`, `max`, `clamp`, `signum`, `copySign`, `sqrt`, `fma`, `IEEEremainder`,
`floor`, `ceil`, `rint`, `round`, `ulp`, `nextUp`/`nextDown`/`nextAfter`, `scalb`, `getExponent`,
the `*Exact` methods and `floorDiv`/`floorMod`/`ceilDiv`/`ceilMod`.

There is no opt-out. Plain `float`/`double` arithmetic is already identical on every platform since
Java 17 (JEP 306).

```java
@AllowStrictMath("reference for FastTrig tests")
static double reference(double x) { return StrictMath.sin(x); }
```

## Allocation free (opt-in)

```kotlin
plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}
```

Production code must not allocate on the heap once the JIT has compiled it. A plain `new` is fine
if the object never escapes, because escape analysis removes it. This is enforced twice:

1. **At build time**, `checkAllocations` (part of `check`) fails on allocations the JIT practically
   never removes: string concatenation, autoboxing, capturing lambdas and method references,
   `Enum.values()`, and calls to allocating APIs (`String.format`, `String.valueOf`, streams, ...).
   Extend or trim that list with `allocationFree { forbiddenApis.add(...) }` / `allowedApis.add(...)`.
   Exempt: static initializers, `toString()`, code that unconditionally ends in `throw` (e.g.
   building an exception message) and anything annotated `@AllowAllocation`.
2. **In tests**, `AllocationAssertions.assertAllocationFree` runs code until the JIT has compiled
   it and then measures real heap allocations of the current thread: (test JVMs compile synchronously,
   `-XX:-BackgroundCompilation`, so the measurement reflects the optimizing compiler, not a timing race)

```java
import static com.truesim.testing.AllocationAssertions.assertAllocationFree;

@Test
void stepIsAllocationFree() {
    Simulation sim = new Simulation();
    assertAllocationFree(() -> sim.step(1 / 60.0));
}
```

## Lombok configuration

`truesim.settings` writes [config/lombok.config](config/lombok.config) to the root of every
consuming build. Commit it: IDE Lombok plugins read it too. Highlights:

- **Accessors follow the JavaBeans convention:** `getName()`, `setName(…)`, `isActive()`. No fluent
  (`name()`) or chained setters, because Jackson, JPA/Hibernate, Spring and bean introspection
  expect JavaBeans accessors. For fluent construction use records, `@Builder` or `@With`.
- Generated code is marked `@lombok.Generated`, so coverage tools ignore it.
- `@Slf4j` loggers are named `log`.
- Warnings for `val` (use Java's `var`), experimental features and `@SneakyThrows`; `lombok.var`
  is an error.

The file is kept in sync with this repo while it starts with the "Managed by" header. To
customize it for a whole repository, delete the header and the plugin leaves it alone. To
override settings for one module, put a `lombok.config` in that module; nearer files win.

## Using it

### Option A: composite build (no credentials needed)

Check this repo out next to your project (or as a git submodule) and in `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../build-logic")   // the plugins
}
plugins {
    id("truesim.settings")
}
includeBuild("../build-logic")       // the annotations and testing libraries
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

## Vulkan on CI

Repositories whose tests need Vulkan (the engine) use the composite action
`.github/actions/setup-vulkan` on a pinned runner image. It installs the loader, Mesa's lavapipe (a CPU Vulkan
device) and the Khronos validation layer from cached packages. While the cache key stays the same, every run uses
the same versions, and the install takes seconds instead of a minute and a half. It also:

- fails when there is no Vulkan 1.3 device or no validation layer;
- writes the versions to the job summary;
- sets `SDL_VIDEO_DRIVER=offscreen` for headless windows;
- sets `TRUESIM_REQUIRE_VALIDATION=true`, so tests fail instead of silently running unvalidated.

```yaml
jobs:
  build:
    runs-on: ubuntu-26.04
    steps:
      - uses: actions/checkout@v7
      - uses: true-sim/build-logic/.github/actions/setup-vulkan@<commit>
      - uses: actions/setup-java@v6
      ...
```

Reference the action by commit, so a change here cannot break other repositories' builds unannounced. To move to
newer Mesa or validation layers, bump the `cache-version` input (default `"1"`). The cache also refreshes by itself
when unused for 7 days.

## Releasing

Push a tag `vX.Y.Z`; the Publish workflow publishes version `X.Y.Z` to GitHub Packages.
