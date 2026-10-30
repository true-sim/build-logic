package com.truesim.build.slang

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Gathers the Slang modules the dependencies on [classpath] ship (their `src/main/slang`, packaged under `slang/` in
 * their jars by `truesim.slang`) into [output], for this project's shaders to import: a game's shader can
 * `import meshlets.common;` from engine-render, say. Directories on the classpath (projects of the same build) are
 * read as their `slang/` subdirectory, jars by their `slang/` entries. Two libraries shipping different files at one
 * path fail the build (identical copies, one library reached twice, are fine); the libraries' `slangc.arguments`
 * (what their modules need from the compiler) and `slang-records.index` (their material records' Java classes) add up.
 */
@CacheableTask
abstract class CollectSlangLibraries : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun collect() {
        val out = output.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val origins = mutableMapOf<String, String>() // collected path to the library it came from
        for (entry in classpath.files) {
            when {
                entry.isDirectory -> {
                    val root = File(entry, "slang")
                    if (root.isDirectory) {
                        root.walkTopDown().filter { it.isFile }.forEach { file ->
                            place(out, file.relativeTo(root).invariantSeparatorsPath, entry, origins) { file.readBytes() }
                        }
                    }
                }
                entry.isFile && entry.extension == "jar" -> ZipFile(entry).use { zip ->
                    for (zipEntry in zip.entries()) {
                        if (zipEntry.isDirectory || !zipEntry.name.startsWith("slang/")) {
                            continue
                        }
                        place(out, zipEntry.name.removePrefix("slang/"), entry, origins) {
                            zip.getInputStream(zipEntry).use { it.readBytes() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes a library's file at {@code path} of [output]: merged files add up; any other file must be the only one of
     * its path, or the same bytes (one library reached twice).
     */
    private fun place(
        out: File, path: String, library: File, origins: MutableMap<String, String>, bytes: () -> ByteArray,
    ) {
        val target = File(out, path)
        if (!target.canonicalPath.startsWith(out.canonicalPath + File.separator)) {
            return // a path leaving the output: not a module
        }
        val content = bytes()
        if (target.name in MERGED && target.parentFile == out) {
            target.appendBytes(content + "\n".toByteArray())
            return
        }
        val earlier = origins[path]
        if (earlier != null) {
            if (!target.readBytes().contentEquals(content)) {
                throw GradleException("Slang libraries $earlier and ${library.name} both ship $path: namespace the " +
                    "modules of one of them (e.g. acme/${path.substringAfterLast('/')})")
            }
            return
        }
        origins[path] = library.name
        target.parentFile.mkdirs()
        target.writeBytes(content)
    }

    private companion object {
        /** Files of every library adding up, not the first one's. */
        val MERGED = setOf(CompileSlang.ARGUMENTS, GenerateSlangRecords.INDEX)
    }
}
