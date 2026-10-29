package com.truesim.build.slang

import org.gradle.api.DefaultTask
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
 * read as their `slang/` subdirectory, jars by their `slang/` entries. Later entries do not overwrite earlier ones,
 * but the libraries' `slangc.arguments` (what their modules need from the compiler) and `slang-records.index` (their
 * material records' Java classes) add up.
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
        for (entry in classpath.files) {
            when {
                entry.isDirectory -> {
                    val root = File(entry, "slang")
                    if (root.isDirectory) {
                        root.walkTopDown().filter { it.isFile }.forEach { file ->
                            val target = File(out, file.relativeTo(root).invariantSeparatorsPath)
                            if (target.name in MERGED && target.parentFile == out) {
                                target.appendText(file.readText() + "\n")
                            } else if (!target.exists()) {
                                target.parentFile.mkdirs()
                                file.copyTo(target)
                            }
                        }
                    }
                }
                entry.isFile && entry.extension == "jar" -> ZipFile(entry).use { zip ->
                    for (zipEntry in zip.entries()) {
                        if (zipEntry.isDirectory || !zipEntry.name.startsWith("slang/")) {
                            continue
                        }
                        val target = File(out, zipEntry.name.removePrefix("slang/"))
                        if (!target.canonicalPath.startsWith(out.canonicalPath + File.separator)) {
                            continue // a path leaving the output: not a module
                        }
                        if (target.name in MERGED && target.parentFile == out) {
                            target.appendText(zip.getInputStream(zipEntry).use { String(it.readBytes()) } + "\n")
                        } else if (!target.exists()) {
                            target.parentFile.mkdirs()
                            zip.getInputStream(zipEntry).use { input -> target.outputStream().use(input::copyTo) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Files of every library adding up, not the first one's. */
        val MERGED = setOf(CompileSlang.ARGUMENTS, GenerateSlangRecords.INDEX)
    }
}
