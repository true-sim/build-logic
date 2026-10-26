package com.truesim.build.strict

import org.gradle.api.tasks.CacheableTask
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode

/**
 * Fails on allocations the JIT practically never removes: string building, autoboxing, capturing
 * lambdas, `Enum.values()` and calls to known allocating APIs. A plain `new` is not flagged, because
 * escape analysis may remove it; tests verify that with `AllocationAssertions`.
 *
 * Static initializers, `toString()`, code that unconditionally ends in `throw` and code annotated
 * with `@AllowAllocation` are exempt.
 */
@CacheableTask
abstract class AllocationCheck : BytecodeCheck() {
    override val annotation = "com/truesim/annotations/AllowAllocation"
    override val ruleName = "Allocation"

    override fun isExemptByDefault(method: MethodNode) =
        method.name == "<clinit>" || (method.name == "toString" && method.desc == "()Ljava/lang/String;")

    override fun check(insn: AbstractInsnNode, annotated: Boolean, apis: ApiMatcher, report: (String) -> Unit) {
        if (annotated) return
        val problem = when (insn) {
            is InvokeDynamicInsnNode -> when (insn.bsm.owner) {
                "java/lang/invoke/StringConcatFactory" -> "string concatenation allocates"
                "java/lang/invoke/LambdaMetafactory" ->
                    if (Type.getArgumentTypes(insn.desc).isNotEmpty()) "capturing lambda or method reference allocates" else null
                else -> null
            }
            is MethodInsnNode -> when {
                insn.isBoxing() -> "autoboxing to ${insn.owner.substringAfterLast('/')} allocates"
                insn.isEnumValues() -> "${insn.owner.substringAfterLast('/')}.values() copies its array on every call"
                apis.isForbidden(insn.owner, insn.name) -> "calls ${insn.owner.replace('/', '.')}.${insn.name}, which allocates"
                else -> null
            }
            else -> null
        }
        if (problem != null && !endsInThrow(insn)) report(problem)
    }

    private fun MethodInsnNode.isBoxing() =
        opcode == Opcodes.INVOKESTATIC && name == "valueOf" && owner in BOXES &&
            Type.getArgumentTypes(desc).singleOrNull()?.sort?.let { it in Type.BOOLEAN..Type.DOUBLE } == true

    private fun MethodInsnNode.isEnumValues() =
        opcode == Opcodes.INVOKESTATIC && name == "values" && desc == "()[L$owner;"

    /** True if execution from [insn] reaches a `throw` without branching, e.g. building an exception message. */
    private fun endsInThrow(insn: AbstractInsnNode): Boolean {
        var next = insn.next
        while (next != null) {
            when {
                next.opcode == Opcodes.ATHROW -> return true
                next is JumpInsnNode || next is TableSwitchInsnNode || next is LookupSwitchInsnNode -> return false
                next.opcode in Opcodes.IRETURN..Opcodes.RETURN -> return false
            }
            next = next.next
        }
        return false
    }

    private companion object {
        val BOXES = setOf(
            "java/lang/Boolean", "java/lang/Byte", "java/lang/Character", "java/lang/Short",
            "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double",
        )
    }
}
