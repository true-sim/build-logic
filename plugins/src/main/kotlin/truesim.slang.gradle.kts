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
// out, so host code writes push constants by name instead of by hand-counted offsets.
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
}

val slang = extensions.create<SlangExtension>("slang").apply {
    version.convention(SlangToolchain.DEFAULT_VERSION)
    checksum.convention(version.map { v ->
        SlangToolchain.CHECKSUMS[v]?.get(SlangToolchain.platform())
            ?: throw GradleException("Slang $v is not pinned by build-logic: set slang.checksum for it")
    })
    arguments.convention(emptyList())
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
    description = "Generates Java and entry points from the $sourceSet Slang material records."
    sources = layout.projectDirectory.dir("src/$sourceSet/slang")
    includes.from(layout.projectDirectory.dir("src/main/slang"))
    includes.from(libraries.flatMap { it.output })
    version = slang.version
    checksum = slang.checksum
    arguments = slang.arguments
    javaPackage = slang.javaPackage
    pushPackage = slang.pushPackage
    allowAllocation = false
    javaOutput = layout.buildDirectory.dir("generated/slang-records/$sourceSet/java")
    slangOutput = layout.buildDirectory.dir("generated/slang-records/$sourceSet/slang")
    indexOutput = layout.buildDirectory.dir("generated/slang-records/$sourceSet/index")
    cacheDir = layout.dir(provider { gradle.gradleUserHomeDir.resolve("truesim") })
}

val mainRecords = registerRecords("generateSlangRecords", "main", mainLibraries)
pluginManager.withPlugin("truesim.allocation-free") {
    tasks.withType<GenerateSlangRecords>().configureEach { allowAllocation = true }
}
val testRecords = registerRecords("generateTestSlangRecords", "test", testLibraries)
testRecords.configure {
    includes.from(mainRecords.flatMap { it.indexOutput }) // the main records, for test records to nest
}

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
    cacheDir = layout.dir(provider { gradle.gradleUserHomeDir.resolve("truesim") })
    onlyIf("there are Slang sources") { sources.get().asFile.isDirectory }
}

val compileSlang = registerSlang("compileSlang", "main", mainLibraries, mainRecords)
val compileTestSlang = registerSlang("compileTestSlang", "test", testLibraries, testRecords)

// The sources go into the jar as well, under slang/, for projects depending on this one to import.
tasks.named<ProcessResources>("processResources") {
    from(layout.projectDirectory.dir("src/main/slang")) {
        into("slang")
    }
    from(mainRecords.flatMap { it.indexOutput }) {
        into("slang")
    }
}

sourceSets.main {
    resources.srcDir(compileSlang.map { it.output.get().asFile.parentFile })
    java.srcDir(mainRecords.flatMap { it.javaOutput })
}
sourceSets.test {
    resources.srcDir(compileTestSlang.map { it.output.get().asFile.parentFile })
    java.srcDir(testRecords.flatMap { it.javaOutput })
}
