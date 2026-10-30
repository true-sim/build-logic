package com.truesim.build.strict

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue

/**
 * The frame graph's purity contract, checked on the bytecode: a technique's `build` (a method named `build` taking one of
 * [builderTypes] first) and a pass body (a lambda, method reference or class implementing one of [bodyTypes]) may not
 * store into fields or static fields, and may not store into arrays they did not make (captured, or read from fields):
 * what a build makes is its product, captured by the bodies, which only record. Objects and arrays made in the method
 * itself, and arrays a call returns (per-frame scratch from the pass context), may be written.
 *
 * The check is shallow: it reads the method itself, not what it calls. `@AllowImpure` exempts a class (with its nested
 * classes and lambdas) or a method.
 */
@CacheableTask
abstract class PurityCheck : DefaultTask() {

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    /** Binary names of the pass body interfaces, e.g. `com.truesim.engine.graph.PassBody`. */
    @get:Input
    abstract val bodyTypes: ListProperty<String>

    /** Binary names of the builders a `build` method takes first, e.g. `com.truesim.engine.graph.GraphBuilder`. */
    @get:Input
    abstract val builderTypes: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    private enum class Origin { MADE, OUTSIDE, CALL }

    @TaskAction
    fun run() {
        val bodies = bodyTypes.get().map { it.replace('.', '/') }.toSet()
        val builders = builderTypes.get().map { "L${it.replace('.', '/')};" }.toSet()
        val classNodes = classes.asFileTree.matching { include("**/*.class") }.files.map { file ->
            ClassNode().also { ClassReader(file.readBytes()).accept(it, ClassReader.SKIP_FRAMES) }
        }.associateBy { it.name }
        val violations = mutableListOf<String>()
        for (type in classNodes.values.sortedBy { it.name }) {
            if (isAnnotated(type, classNodes)) continue
            val bodyMethods = lambdaBodies(type, bodies)
            val implementsBody = type.interfaces.any { it in bodies }
            val packagePath = type.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val source = packagePath + (type.sourceFile ?: (type.name.substringAfterLast('/') + ".class"))
            for (method in type.methods) {
                val kind = when {
                    method.name + method.desc in bodyMethods -> "pass body"
                    implementsBody && method.name == "record" -> "pass body"
                    method.name == "build" && Type.getArgumentTypes(method.desc).firstOrNull()?.descriptor in builders ->
                        "build"
                    else -> continue
                }
                if (isAnnotated(type, method)) continue
                check(type, method) { line, message ->
                    violations += "$source:$line (${type.name.substringAfterLast('/')}.${displayName(method)}, " +
                        "$kind): $message"
                }
            }
        }
        val reportFile = report.get().asFile
        reportFile.writeText(violations.joinToString("") { "$it\n" })
        if (violations.isNotEmpty()) {
            val shown = violations.take(MAX_SHOWN).joinToString("\n") { "  $it" }
            val more = if (violations.size > MAX_SHOWN) "\n  ... and ${violations.size - MAX_SHOWN} more" else ""
            throw GradleException("Purity check found ${violations.size} violation(s):\n$shown$more\n" +
                "Builds return their product and pass bodies only record; @AllowImpure exempts. Full report: $reportFile")
        }
    }

    /** The methods of [type] that lambdas or method references of a body type run (`name + desc`). */
    private fun lambdaBodies(type: ClassNode, bodies: Set<String>): Set<String> {
        val found = mutableSetOf<String>()
        for (method in type.methods) {
            for (insn in method.instructions) {
                if (insn !is InvokeDynamicInsnNode || insn.bsm.owner != "java/lang/invoke/LambdaMetafactory") continue
                if (Type.getReturnType(insn.desc).internalName !in bodies) continue
                val handle = insn.bsmArgs.getOrNull(1) as? Handle ?: continue
                if (handle.owner == type.name) {
                    found += handle.name + handle.desc
                }
            }
        }
        return found
    }

    private fun check(type: ClassNode, method: MethodNode, report: (Int, String) -> Unit) {
        val frames = Analyzer(SourceInterpreter()).analyze(type.name, method)
        val insns = method.instructions
        var line = 0
        for ((i, insn) in insns.withIndex()) {
            if (insn is LineNumberNode) line = insn.line
            val frame = frames[i] ?: continue // unreachable
            when (insn.opcode) {
                Opcodes.PUTSTATIC -> report(line, "stores into static field ${field(insn as FieldInsnNode)}")
                Opcodes.PUTFIELD -> {
                    val target = frame.getStack(frame.stackSize - 2)
                    if (origin(target, frames, method, 0) != Origin.MADE) {
                        report(line, "stores into field ${field(insn as FieldInsnNode)} of an object it did not make")
                    }
                }
                in Opcodes.IASTORE..Opcodes.SASTORE -> {
                    val array = frame.getStack(frame.stackSize - 3)
                    if (origin(array, frames, method, 0) == Origin.OUTSIDE) {
                        report(line, "stores into an array it did not make (captured, or read from a field)")
                    }
                }
            }
        }
    }

    /** Where [value] came from: made in the method, from outside it (a parameter, `this`, a field) or a call. */
    private fun origin(value: SourceValue, frames: Array<Frame<SourceValue>?>, method: MethodNode, depth: Int): Origin {
        if (value.insns.isEmpty()) return Origin.OUTSIDE // a parameter, or this
        if (depth > MAX_DEPTH) return Origin.CALL
        var result = Origin.MADE
        for (insn in value.insns) {
            val origin = origin(insn, frames, method, depth)
            if (origin == Origin.OUTSIDE) return Origin.OUTSIDE
            if (origin == Origin.CALL) result = Origin.CALL
        }
        return result
    }

    private fun origin(insn: AbstractInsnNode, frames: Array<Frame<SourceValue>?>, method: MethodNode,
        depth: Int): Origin {
        val frame = frames[method.instructions.indexOf(insn)] ?: return Origin.CALL
        return when (insn.opcode) {
            Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY, Opcodes.ACONST_NULL -> Origin.MADE
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> Origin.OUTSIDE
            Opcodes.ALOAD -> origin(frame.getLocal((insn as VarInsnNode).`var`), frames, method, depth + 1)
            Opcodes.ASTORE, Opcodes.CHECKCAST, Opcodes.DUP ->
                origin(frame.getStack(frame.stackSize - 1), frames, method, depth + 1)
            Opcodes.AALOAD -> origin(frame.getStack(frame.stackSize - 2), frames, method, depth + 1)
            // Copies of the top values (as `a[i]++` makes them): from any of the values copied.
            Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.SWAP -> {
                var result = Origin.MADE
                for (k in 1..minOf(3, frame.stackSize)) {
                    when (origin(frame.getStack(frame.stackSize - k), frames, method, depth + 1)) {
                        Origin.OUTSIDE -> return Origin.OUTSIDE
                        Origin.CALL -> result = Origin.CALL
                        Origin.MADE -> {}
                    }
                }
                result
            }
            else -> Origin.CALL
        }
    }

    private fun field(insn: FieldInsnNode) = "${insn.owner.substringAfterLast('/')}.${insn.name}"

    private fun hasAnnotation(vararg lists: List<AnnotationNode>?) =
        lists.any { list -> list.orEmpty().any { it.desc == ANNOTATION } }

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

    private fun displayName(method: MethodNode) =
        enclosingMethodOfLambda(method)?.let { "$it (lambda)" } ?: "${method.name}()"

    private companion object {
        const val MAX_SHOWN = 50
        const val MAX_DEPTH = 32
        const val ANNOTATION = "Lcom/truesim/annotations/AllowImpure;"

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
