// Slang shaders: src/main/slang/**/*.slang compiled to SPIR-V with a pinned slangc, packaged as resources under
// shaders/, e.g. src/main/slang/mesh/draw.slang -> classpath shaders/mesh/draw.spv. Test shaders in src/test/slang go
// to the test resources the same way, and may import the main shaders' modules.
//
// The Slang sources ship in the jar too, under slang/, and the shaders of a project may import the modules of every
// dependency on its runtime classpath that uses this plugin: a game's shader can `import meshlets.common;`.
import com.truesim.build.slang.CollectSlangLibraries
import com.truesim.build.slang.CompileSlang
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

fun registerSlang(name: String, sourceSet: String, libraries: TaskProvider<CollectSlangLibraries>) =
    tasks.register<CompileSlang>(name) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Compiles the $sourceSet Slang shaders to SPIR-V."
    sources = layout.projectDirectory.dir("src/$sourceSet/slang")
    includes.from(layout.projectDirectory.dir("src/main/slang"))
    includes.from(libraries.flatMap { it.output })
    version = slang.version
    checksum = slang.checksum
    arguments = slang.arguments
    output = layout.buildDirectory.dir("generated/slang/$sourceSet/shaders")
    cacheDir = layout.dir(provider { gradle.gradleUserHomeDir.resolve("truesim") })
    onlyIf("there are Slang sources") { sources.get().asFile.isDirectory }
}

val compileSlang = registerSlang("compileSlang", "main", mainLibraries)
val compileTestSlang = registerSlang("compileTestSlang", "test", testLibraries)

// The sources go into the jar as well, under slang/, for projects depending on this one to import.
tasks.named<ProcessResources>("processResources") {
    from(layout.projectDirectory.dir("src/main/slang")) {
        into("slang")
    }
}

sourceSets.main {
    resources.srcDir(compileSlang.map { it.output.get().asFile.parentFile })
}
sourceSets.test {
    resources.srcDir(compileTestSlang.map { it.output.get().asFile.parentFile })
}
