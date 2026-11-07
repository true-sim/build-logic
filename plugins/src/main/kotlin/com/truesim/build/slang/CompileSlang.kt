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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import groovy.json.JsonOutput
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Compiles Slang shaders to SPIR-V with the pinned compiler: every `.slang` file under [sources] that declares entry
 * points (`[shader("...")]`) becomes one SPIR-V module with all of them, named by entry point, at the same relative
 * path under [output] with `.spv`. Other `.slang` files are modules the shaders import; [sources] is on the include
 * path. Warnings fail the build. Shaders compile in parallel, a process each, and only those whose dependencies (as
 * slangc lists them, in [dependencyState]) or arguments changed since they last compiled.
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

    /**
     * Each shader's dependencies as slangc found them (its depfile) and a digest of their contents and the arguments:
     * a shader whose digest is unchanged is not compiled again.
     */
    @get:OutputDirectory
    abstract val dependencyState: DirectoryProperty

    /**
     * Where to write `shading-shaders.index`, the shaders of [sources] that shade (whose dependencies include the
     * shading hooks' generated dispatch, [ShadingHooks]), for projects using this one to rebuild with their hooks; none
     * when unset. Material types' entry points are left out: their variants come from the types' templates.
     */
    @get:OutputDirectory
    @get:Optional
    abstract val shadingIndex: DirectoryProperty

    /** Where the compiler is installed: the Gradle user home, outside the task's inputs. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val slangc = SlangToolchain.install(cacheDir.get().asFile, version.get(), checksum.get())
        val root = sources.get().asFile
        val out = output.get().asFile.also { it.mkdirs() }
        val stateDir = dependencyState.get().asFile.also { it.mkdirs() }
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
        val wanted = shaders.map { (dir, shader) -> shader.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".slang") }
            .toSet()
        // What is no longer a shader goes: its SPIR-V and its state
        out.walkTopDown().filter { it.isFile && it.extension == "spv" }
            .filter { it.relativeTo(out).invariantSeparatorsPath.removeSuffix(".spv") !in wanted }
            .forEach { it.delete() }
        stateDir.walkTopDown().filter { it.isFile }
            .filter { it.relativeTo(stateDir).invariantSeparatorsPath.substringBeforeLast('.') !in wanted }
            .forEach { it.delete() }
        // Each shader is a process of its own: as many at once as there are processors, failures reported in order
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 16))
        try {
            val runs = shaders.mapNotNull { (dir, shader) ->
                val relative = shader.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".slang")
                val target = File(out, "$relative.spv")
                val depfile = File(stateDir, "$relative.d")
                val digestFile = File(stateDir, "$relative.digest")
                val arguments = listOf("-target", "spirv", "-profile", "spirv_1_6", "-fvk-use-entrypoint-name",
                    "-matrix-layout-column-major", "-warnings-as-errors", "all", "-O2", "-I", root.absolutePath) + common
                if (target.isFile && digestFile.isFile && depfile.isFile &&
                    digestFile.readText() == digest(arguments, dependencies(depfile))) {
                    return@mapNotNull null // nothing it depends on changed
                }
                target.parentFile.mkdirs()
                depfile.parentFile.mkdirs()
                relative to pool.submit(Callable {
                    val log = ByteArrayOutputStream()
                    val result = exec.exec {
                        commandLine(listOf(slangc.absolutePath, shader.absolutePath) + arguments +
                            listOf("-o", target.absolutePath, "-depfile", depfile.absolutePath))
                        standardOutput = log
                        errorOutput = log
                        isIgnoreExitValue = true
                    }
                    if (result.exitValue == 0) {
                        digestFile.writeText(digest(arguments, dependencies(depfile)))
                    } else {
                        digestFile.delete()
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
        shadingIndex.orNull?.asFile?.let { dir ->
            dir.deleteRecursively()
            dir.mkdirs()
            val shading = shaders.filter { (dir2, _) -> dir2 == root }
                .map { (_, shader) -> shader.relativeTo(root).invariantSeparatorsPath.removeSuffix(".slang") }
                .filter { relative ->
                    dependencies(File(stateDir, "$relative.d")).any {
                        it.invariantSeparatorsPath.endsWith("/" + ShadingHooks.DISPATCH)
                    }
                }
                .sorted()
            File(dir, SHADING_INDEX).writeText(shading.joinToString("") {
                JsonOutput.toJson(mapOf("kind" to "shader", "path" to it)) + "\n"
            })
        }
    }

    /** The files a depfile names (make's syntax, as slangc writes it: `target: a b`, escapes `\ `, `\:`, `\\`). */
    private fun dependencies(depfile: File): List<File> {
        if (!depfile.isFile) {
            return emptyList()
        }
        val text = depfile.readText().replace("\\\n", " ")
        val files = mutableListOf<File>()
        val current = StringBuilder()
        var i = 0
        var afterTarget = false
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] in " :\\#") {
                current.append(text[i + 1])
                i += 2
                continue
            }
            if (!afterTarget) {
                if (c == ':' && (i + 1 >= text.length || text[i + 1].isWhitespace())) {
                    afterTarget = true
                    current.clear()
                } else {
                    current.append(c)
                }
            } else if (c.isWhitespace()) {
                if (current.isNotEmpty()) {
                    files += File(current.toString())
                    current.clear()
                }
            } else {
                current.append(c)
            }
            i++
        }
        if (afterTarget && current.isNotEmpty()) {
            files += File(current.toString())
        }
        return files
    }

    /** A digest of [arguments] and the contents of [files] (a missing one as such). */
    private fun digest(arguments: List<String>, files: List<File>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        arguments.forEach { sha.update(it.toByteArray()); sha.update(0) }
        for (f in files) {
            sha.update(f.path.toByteArray())
            sha.update(if (f.isFile) f.readBytes() else byteArrayOf(-1))
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The file of arguments a directory's modules need, beside them. */
        const val ARGUMENTS = "slangc.arguments"

        /** A project's shaders that shade (their dependencies include the shading hooks' dispatch): JSON per line. */
        const val SHADING_INDEX = "shading-shaders.index"
    }
}
