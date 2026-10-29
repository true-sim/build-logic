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
import javax.inject.Inject

/**
 * Compiles Slang shaders to SPIR-V with the pinned compiler: every `.slang` file under [sources] that declares entry
 * points (`[shader("...")]`) becomes one SPIR-V module with all of them, named by entry point, at the same relative
 * path under [output] with `.spv`. Other `.slang` files are modules the shaders import; [sources] is on the include
 * path. Warnings fail the build.
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
        val required = (listOf(root) + includes.files.filter { it.isDirectory })
            .map { File(it, ARGUMENTS) }
            .filter { it.isFile }
            .flatMap { it.readText().split(Regex("\\s+")).filter(String::isNotEmpty) }
        val shaders = root.walkTopDown()
            .filter { it.isFile && it.extension == "slang" && it.readText().contains("[shader(") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        for (shader in shaders) {
            val relative = shader.relativeTo(root).invariantSeparatorsPath.removeSuffix(".slang")
            val target = File(out, "$relative.spv")
            target.parentFile.mkdirs()
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
                    ) + includes.files.filter { it.isDirectory }.flatMap { listOf("-I", it.absolutePath) } +
                        required + arguments.get(),
                )
                standardOutput = log
                errorOutput = log
                isIgnoreExitValue = true
            }
            check(result.exitValue == 0) { "Slang could not compile $relative.slang:\n$log" }
        }
    }

    companion object {
        /** The file of arguments a directory's modules need, beside them. */
        const val ARGUMENTS = "slangc.arguments"
    }
}
