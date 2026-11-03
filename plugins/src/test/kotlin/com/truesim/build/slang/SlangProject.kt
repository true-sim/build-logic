package com.truesim.build.slang

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * A small Gradle build applying `truesim.slang`, under build/testkit-projects, run with Gradle TestKit: its projects'
 * Slang sources written by the test, the Slang compiler the one the Gradle user home has (installed there once).
 */
class SlangProject(name: String) {

    val dir: File = File(System.getProperty("truesim.testkit.projects"), name).apply {
        deleteRecursively()
        mkdirs()
    }

    private val projects = mutableListOf<String>()

    init {
        write("settings.gradle", "rootProject.name = '$name'\n")
    }

    /** A project of the build ([path] "" for the root) applying `truesim.slang`, configured by [configuration]. */
    fun project(path: String = "", configuration: String = "slang { javaPackage = 'com.example.materials' }") =
        apply {
            if (path.isNotEmpty()) {
                projects += path
                write("settings.gradle", "rootProject.name = '${dir.name}'\n" +
                    projects.joinToString("") { "include '$it'\n" })
            }
            val cache = System.getProperty("truesim.slang.cache").replace('\\', '/')
            write(file(path, "build.gradle"), """
                |plugins {
                |    id 'truesim.slang'
                |}
                |
                |$configuration
                |
                |tasks.withType(com.truesim.build.slang.GenerateSlangRecords).configureEach {
                |    cacheDir.set(file('$cache'))
                |}
                |""".trimMargin())
        }

    /** Writes [text] to [path] of the build. */
    fun write(path: String, text: String) = apply {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
    }

    /** Writes the Slang module [path] (e.g. `glow.slang`) of [project]'s main sources. */
    fun slang(path: String, text: String, project: String = "") = write(file(project, "src/main/slang/$path"), text)

    /** Writes the attributes of engine-render's `materials` module, as `materials.slang` of [project]. */
    fun attributes(project: String = "") = slang("materials.slang", ATTRIBUTES, project)

    fun build(vararg tasks: String): BuildResult = runner(*tasks).build()

    fun fail(vararg tasks: String): BuildResult = runner(*tasks).buildAndFail()

    /** The file [path] of the root project's build directory. */
    fun output(path: String, project: String = ""): File = File(dir, file(project, "build/$path"))

    /** [project]'s generated Java class [name] of the main sources, in [pkg]. */
    fun java(name: String, pkg: String = "com.example.materials", project: String = ""): String =
        output("generated/slang-records/main/java/${pkg.replace('.', '/')}/$name.java", project).readText()

    private fun runner(vararg tasks: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*tasks, "--stacktrace")
        .forwardOutput()

    private fun file(project: String, path: String) =
        if (project.isEmpty()) path else project.removePrefix(":").replace(':', '/') + "/" + path

    companion object {
        /** The attributes engine-render's `materials` module declares for material records. */
        val ATTRIBUTES = """
            module materials;

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct MaterialParametersAttribute { int features; };

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct MaterialFeatureAttribute { int feature; };

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct MaterialTypeAttribute { string name; };

            [__AttributeUsage(_AttributeTargets.Var)]
            public struct RangeAttribute { float low; float high; };

            [__AttributeUsage(_AttributeTargets.Var)]
            public struct ColorAttribute {};
        """
    }
}
