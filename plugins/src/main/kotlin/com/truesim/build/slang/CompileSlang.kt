package com.truesim.build.slang

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Compiles Slang shaders to SPIR-V with the pinned compiler: every `.slang` file under [sources] that declares entry
 * points (`[shader("...")]`) becomes one SPIR-V module with all of them, named by entry point, at the same relative
 * path under [output] with `.spv`. Other `.slang` files are modules the shaders import; [sources] is on the include
 * path. Warnings fail the build. Shaders compile in parallel, a process each.
 *
 * A directory of modules (the sources or an include) may hold a `slangc.arguments` file: arguments its modules need
 * from whoever compiles them, whitespace-separated, e.g. `-warnings-disable 39001` for a deliberate binding overlap.
 * They are added for every shader, so importing a library's modules needs nothing more than the import.
 */
@CacheableTask
abstract class CompileSlang @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    /**
     * Directories of generated shaders, compiled as the sources' (at their paths relative to the directory), and on the
     * include path: a generated file may be included, as particle modules' dispatch is.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generated: ConfigurableFileCollection

    /** More directories of modules the shaders import, e.g. the main shaders' for test shaders. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includes: ConfigurableFileCollection

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val checksum: Property<String>

    /** Extra `slangc` arguments, e.g. `-D` defines. */
    @get:Input
    abstract val arguments: ListProperty<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    /** Where the compiler is installed: the Gradle user home, outside the task's inputs. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val slangc = SlangToolchain.install(cacheDir.get().asFile, version.get(), checksum.get())
        val root = sources.get().asFile
        val out = output.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val required = (listOf(root) + includes.files.filter { it.isDirectory })
            .map { File(it, ARGUMENTS) }
            .filter { it.isFile }
            .flatMap { it.readText().split(Regex("\\s+")).filter(String::isNotEmpty) }
        val shaders = (listOf(root) + generated.files.filter { it.isDirectory }).filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension == "slang" && it.readText().contains("[shader(") }
                    .map { dir to it }
            }
            .sortedBy { (dir, file) -> file.relativeTo(dir).invariantSeparatorsPath }
        val common = (includes.files + generated.files).filter { it.isDirectory }
            .flatMap { listOf("-I", it.absolutePath) } + required + arguments.get()
        // Each shader is a process of its own: as many at once as there are processors, failures reported in order
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 16))
        try {
            val runs = shaders.map { (dir, shader) ->
                val relative = shader.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".slang")
                val target = File(out, "$relative.spv")
                target.parentFile.mkdirs()
                relative to pool.submit(Callable {
                    val log = ByteArrayOutputStream()
                    val result = exec.exec {
                        commandLine(
                            listOf(
                                slangc.absolutePath, shader.absolutePath,
                                "-target", "spirv", "-profile", "spirv_1_6",
                                "-fvk-use-entrypoint-name", "-matrix-layout-column-major",
                                "-warnings-as-errors", "all",
                                "-O2", "-I", root.absolutePath,
                                "-o", target.absolutePath,
                            ) + common,
                        )
                        standardOutput = log
                        errorOutput = log
                        isIgnoreExitValue = true
                    }
                    result.exitValue to log.toString()
                })
            }
            for ((relative, run) in runs) {
                val (exit, log) = try {
                    run.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                check(exit == 0) { "Slang could not compile $relative.slang:\n$log" }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        /** The file of arguments a directory's modules need, beside them. */
        const val ARGUMENTS = "slangc.arguments"
    }
}
