// Slang shaders: src/main/slang/**/*.slang compiled to SPIR-V with a pinned slangc, packaged as resources under
// shaders/, e.g. src/main/slang/mesh/draw.slang -> classpath shaders/mesh/draw.spv. Test shaders in src/test/slang go
// to the test resources the same way, and may import the main shaders' modules.
//
// The Slang sources ship in the jar too, under slang/, and the shaders of a project may import the modules of every
// dependency on its runtime classpath that uses this plugin: a game's shader can `import meshlets.common;`.
//
// Material records (engine-render's `materials` module: `[MaterialParameters]`, `[MaterialFeature]` and
// `[MaterialType]` structs) become Java in slang.javaPackage, generated before the Java compiles: the Slang struct is
// their one source of truth.
//
// Push-constant blocks become Java too: a class per struct of a `[[vk::push_constant]] ConstantBuffer<S>`, named S, in
// slang.pushPackage (or slang.javaPackage), holding the bytes the block takes and each field's offset as slangc lays it
// out, so host code writes push constants by name instead of by hand-counted offsets. With slang.pushBase, each class
// extends it and is a writer too: a typed setter per field. The `static const`s of the modules in
// slang.constantModules become Java constants the same way.
//
// Particle modules (`[ParticleModule("name")]` structs, engine-render's particles) of a project and its libraries are
// collected into one interpreting kernel: their opcodes and dispatch, generated from the templates engine-render ships,
// and a Java class listing them, slang.particleModules (the test sources' the same name prefixed with Test).
//
// Shading hooks (`[ShadingHook("name")]` structs: indirect light, media along the view ray, ...) of a project and its
// libraries are merged into one dispatch, generated from the templates engine-render ships and included by its
// shading. With slang.shadingHooks (a Java class; the test sources' prefixed with Test) the project also gets that
// listing and variants of its libraries' shaders that shade (found by their dependencies when each library compiled
// them), rebuilt with every hook. A project whose own shaders declare hooks gets a listing without asking:
// <javaPackage>.<Project>ShadingHooks; with the templates' `service` the listing is a service (META-INF/services).
import com.truesim.build.slang.CollectSlangLibraries
import com.truesim.build.slang.CompileSlang
import com.truesim.build.slang.GenerateSlangRecords
import com.truesim.build.slang.SlangToolchain

plugins {
    java
}

interface SlangExtension {
    /** The Slang release; another version than the pinned one needs its [checksum]. */
    val version: Property<String>

    /** SHA-256 of this platform's release zip of [version]. */
    val checksum: Property<String>

    /** Extra slangc arguments. */
    val arguments: ListProperty<String>

    /** The package of the Java generated from material records; needed once there are any. */
    val javaPackage: Property<String>

    /**
     * The package of the push-constant layouts: a Java class per struct of a `[[vk::push_constant]]` block, with its size
     * and field offsets. [javaPackage] if unset; without either, none are generated.
     */
    val pushPackage: Property<String>

    /**
     * The class push-constant layouts extend, e.g. `com.truesim.engine.vulkan.PushConstants`: with it each layout is a
     * writer too, with a typed setter per field putting the value at its offset. Without it, constants only.
     */
    val pushBase: Property<String>

    /**
     * Modules (paths under src/main/slang without `.slang`, e.g. `meshlets/common`) whose scalar `static const`s
     * become a Java class `<Module>Constants` in [pushPackage] (or [javaPackage]).
     */
    val constantModules: ListProperty<String>

    /**
     * The Java class listing the particle modules of the project and its libraries, e.g.
     * `com.example.game.GameModules`; needed once the project's shaders declare any. The test sources' listing is the
     * same name with its simple name prefixed with `Test`.
     */
    val particleModules: Property<String>

    /**
     * The Java class listing the shading hooks of the project and its libraries, e.g. `com.example.game.GameHooks`;
     * needed once the project's shaders declare any. With it, the libraries' shaders that shade are rebuilt with every
     * hook the project sees, as variants the listing names. The test sources' listing is the same name with its
     * simple name prefixed with `Test`. Unset, a project whose own shaders declare hooks gets
     * `<javaPackage>.<Project>ShadingHooks` (the project's name in camel case, e.g. effects-atmosphere:
     * `EffectsAtmosphereShadingHooks`); one without hooks of its own, none.
     */
    val shadingHooks: Property<String>

}

val slang = extensions.create<SlangExtension>("slang").apply {
    version.convention(SlangToolchain.DEFAULT_VERSION)
    checksum.convention(version.map { v ->
        SlangToolchain.CHECKSUMS[v]?.get(SlangToolchain.platform())
            ?: throw GradleException("Slang $v is not pinned by build-logic: set slang.checksum for it")
    })
    arguments.convention(emptyList())
    constantModules.convention(emptyList())
}

fun registerLibraries(name: String, classpath: String) = tasks.register<CollectSlangLibraries>(name) {
    description = "Gathers the Slang modules of the dependencies on the $classpath, for the shaders to import."
    this.classpath.from(configurations.named(classpath))
    output = layout.buildDirectory.dir("slang-libraries/$classpath")
}

val mainLibraries = registerLibraries("slangLibraries", "runtimeClasspath")
val testLibraries = registerLibraries("testSlangLibraries", "testRuntimeClasspath")

fun registerRecords(name: String, sourceSet: String, libraries: TaskProvider<CollectSlangLibraries>) =
    tasks.register<GenerateSlangRecords>(name) {
    description = "Generates Java and entry points from the $sourceSet Slang material records and particle modules."
    sources = layout.projectDirectory.dir("src/$sourceSet/slang")
    includes.from(layout.projectDirectory.dir("src/main/slang"))
    includes.from(libraries.flatMap { it.output })
    version = slang.version
    checksum = slang.checksum
    arguments = slang.arguments
    javaPackage = slang.javaPackage
    pushPackage = slang.pushPackage
    pushBase = slang.pushBase
    constantModules.convention(emptyList())
    allowAllocation = false
    javaOutput = layout.buildDirectory.dir("generated/slang-records/$sourceSet/java")
    slangOutput = layout.buildDirectory.dir("generated/slang-records/$sourceSet/slang")
    indexOutput = layout.buildDirectory.dir("generated/slang-records/$sourceSet/index")
    resourceOutput = layout.buildDirectory.dir("generated/slang-records/$sourceSet/resources")
    cacheDir = layout.dir(provider { gradle.gradleUserHomeDir.resolve("truesim") })
}

/** The listing a project declaring shading hooks gets unasked: `<javaPackage>.<Project>ShadingHooks`. */
val automaticListing: Provider<String> = slang.javaPackage.map { pkg ->
    pkg + "." + project.name.split('-', '_', '.').filter { it.isNotEmpty() }
        .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "ShadingHooks"
}

val mainRecords = registerRecords("generateSlangRecords", "main", mainLibraries)
mainRecords.configure {
    constantModules = slang.constantModules // modules of the main sources
    particleModules = slang.particleModules
    shadingHooks = slang.shadingHooks
    automaticShadingHooks = automaticListing
}
pluginManager.withPlugin("truesim.allocation-free") {
    tasks.withType<GenerateSlangRecords>().configureEach { allowAllocation = true }
}
/** The test sources' listing of [name]: its simple name prefixed with `Test`. */
fun testName(name: String): String {
    val pkg = name.substringBeforeLast('.', "")
    return (if (pkg.isEmpty()) "" else "$pkg.") + "Test" + name.substringAfterLast('.')
}

val testRecords = registerRecords("generateTestSlangRecords", "test", testLibraries)

fun registerSlang(name: String, sourceSet: String, libraries: TaskProvider<CollectSlangLibraries>,
    records: TaskProvider<GenerateSlangRecords>) =
    tasks.register<CompileSlang>(name) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Compiles the $sourceSet Slang shaders to SPIR-V."
    sources = layout.projectDirectory.dir("src/$sourceSet/slang")
    includes.from(layout.projectDirectory.dir("src/main/slang"))
    includes.from(libraries.flatMap { it.output })
    generated.from(records.flatMap { it.slangOutput })
    version = slang.version
    checksum = slang.checksum
    arguments = slang.arguments
    output = layout.buildDirectory.dir("generated/slang/$sourceSet/shaders")
    dependencyState = layout.buildDirectory.dir("generated/slang-state/$sourceSet")
    cacheDir = layout.dir(provider { gradle.gradleUserHomeDir.resolve("truesim") })
    onlyIf("there are Slang sources") { sources.get().asFile.isDirectory }
}

val compileSlang = registerSlang("compileSlang", "main", mainLibraries, mainRecords)
compileSlang.configure {
    shadingIndex = layout.buildDirectory.dir("generated/slang-shading/main") // shaders that shade, for later projects
}
// The test sources see the main sources as a library: their records, hooks and material types (the index) and their
// shaders that shade (compileSlang's), so the test listing rebuilds this project's own shading with the test hooks.
// The testRuntimeClasspath configuration holds the dependencies only, not the main output: without this a project had
// to depend on itself (testRuntimeOnly(project(":x")), or reach itself through its test fixtures) for that.
testRecords.configure {
    includes.from(mainRecords.flatMap { it.indexOutput }) // the main records, for test records to nest
    includes.from(compileSlang.flatMap { it.shadingIndex }) // the main shaders that shade
    particleModules = slang.particleModules.map(::testName)
    shadingHooks = slang.shadingHooks.map(::testName)
    automaticShadingHooks = automaticListing.map(::testName)
}
val compileTestSlang = registerSlang("compileTestSlang", "test", testLibraries, testRecords)

// The sources go into the jar as well, under slang/, for projects depending on this one to import.
tasks.named<ProcessResources>("processResources") {
    from(layout.projectDirectory.dir("src/main/slang")) {
        into("slang")
    }
    from(mainRecords.flatMap { it.indexOutput }) {
        into("slang")
    }
    from(compileSlang.flatMap { it.shadingIndex }) {
        into("slang")
    }
}

sourceSets.main {
    resources.srcDir(compileSlang.map { it.output.get().asFile.parentFile })
    resources.srcDir(mainRecords.flatMap { it.resourceOutput })
    java.srcDir(mainRecords.flatMap { it.javaOutput })
}
sourceSets.test {
    resources.srcDir(compileTestSlang.map { it.output.get().asFile.parentFile })
    resources.srcDir(testRecords.flatMap { it.resourceOutput })
    java.srcDir(testRecords.flatMap { it.javaOutput })
}
