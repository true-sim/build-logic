package com.truesim.build.strict

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode

/** Scans compiled classes and fails the build on rule violations. */
@CacheableTask
abstract class BytecodeCheck : DefaultTask() {

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    /** API patterns that are not allowed, see [ApiMatcher]. */
    @get:Input
    abstract val forbiddenApis: ListProperty<String>

    /** API patterns that are allowed even if they match [forbiddenApis]. */
    @get:Input
    abstract val allowedApis: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    /** Internal name of the annotation that relaxes the rule for a class or method. */
    @get:Internal
    protected abstract val annotation: String

    @get:Internal
    protected abstract val ruleName: String

    /**
     * Reports violations for one instruction.
     * [annotated] is true when the method or an enclosing class carries [annotation].
     */
    protected abstract fun check(insn: AbstractInsnNode, annotated: Boolean, apis: ApiMatcher, report: (String) -> Unit)

    protected open fun isExemptByDefault(method: MethodNode) = false

    @TaskAction
    fun run() {
        val classNodes = classes.asFileTree.matching { include("**/*.class") }.files.map { file ->
            ClassNode().also { ClassReader(file.readBytes()).accept(it, ClassReader.SKIP_FRAMES) }
        }.associateBy { it.name }
        val apis = ApiMatcher(forbiddenApis.get(), allowedApis.get())
        val violations = mutableListOf<String>()

        for (type in classNodes.values.sortedBy { it.name }) {
            val classAnnotated = isAnnotated(type, classNodes)
            val packagePath = type.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val source = packagePath + (type.sourceFile ?: (type.name.substringAfterLast('/') + ".class"))
            for (method in type.methods) {
                if (isExemptByDefault(method)) continue
                val annotated = classAnnotated || isAnnotated(type, method)
                var line = 0
                for (insn in method.instructions) {
                    if (insn is LineNumberNode) line = insn.line
                    check(insn, annotated, apis) { message ->
                        violations += "$source:$line (${type.name.substringAfterLast('/')}.${displayName(method)}): $message"
                    }
                }
            }
        }

        val reportFile = report.get().asFile
        reportFile.writeText(violations.joinToString("") { "$it\n" })
        if (violations.isNotEmpty()) {
            val shown = violations.take(MAX_SHOWN).joinToString("\n") { "  $it" }
            val more = if (violations.size > MAX_SHOWN) "\n  ... and ${violations.size - MAX_SHOWN} more" else ""
            throw GradleException("$ruleName check found ${violations.size} violation(s):\n$shown$more\nFull report: $reportFile")
        }
    }

    private fun hasAnnotation(vararg lists: List<AnnotationNode>?) =
        lists.any { list -> list.orEmpty().any { it.desc == "L$annotation;" } }

    /** A class counts as annotated if it or any enclosing class carries the annotation. */
    private fun isAnnotated(type: ClassNode, all: Map<String, ClassNode>): Boolean {
        if (hasAnnotation(type.invisibleAnnotations, type.visibleAnnotations)) return true
        val enclosing = type.outerClass ?: type.nestHostClass ?: return false
        return all[enclosing]?.let { isAnnotated(it, all) } ?: false
    }

    /** A lambda body counts as annotated if the method it was written in is. */
    private fun isAnnotated(type: ClassNode, method: MethodNode): Boolean {
        if (hasAnnotation(method.invisibleAnnotations, method.visibleAnnotations)) return true
        val enclosing = enclosingMethodOfLambda(method) ?: return false
        return type.methods.any { it.name == enclosing && hasAnnotation(it.invisibleAnnotations, it.visibleAnnotations) }
    }

    private fun displayName(method: MethodNode) = when (method.name) {
        "<init>", "<clinit>" -> method.name
        else -> enclosingMethodOfLambda(method)?.let { "$it (lambda)" } ?: "${method.name}()"
    }

    private companion object {
        const val MAX_SHOWN = 50

        /** javac names lambda bodies `lambda$<method>$<n>`, with `new` and `static` for constructors and initializers. */
        fun enclosingMethodOfLambda(method: MethodNode): String? {
            if (!method.name.startsWith("lambda$")) return null
            return when (val name = method.name.split('$').getOrNull(1)) {
                "new" -> "<init>"
                "static" -> "<clinit>"
                else -> name
            }
        }
    }
}
