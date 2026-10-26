package com.truesim.build.strict

import org.gradle.api.tasks.CacheableTask
import org.objectweb.asm.Handle
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Fails on references to APIs whose results may differ between platforms.
 *
 * java.lang.Math and java.lang.StrictMath are forbidden except for their exactly specified methods
 * ([EXACT_MATH]). StrictMath is allowed in code annotated with `@AllowStrictMath`.
 */
@CacheableTask
abstract class DeterminismCheck : BytecodeCheck() {
    override val annotation = "com/truesim/annotations/AllowStrictMath"
    override val ruleName = "Platform independence"

    override fun check(insn: AbstractInsnNode, annotated: Boolean, apis: ApiMatcher, report: (String) -> Unit) {
        fun checkRef(owner: String, name: String) {
            if (!apis.isForbidden(owner, name)) return
            if (annotated && owner == "java/lang/StrictMath") return
            val hint = if (owner == "java/lang/StrictMath") " outside @AllowStrictMath code" else ""
            report("uses ${owner.replace('/', '.')}.$name$hint, which is not allowed in platform-independent code")
        }
        when (insn) {
            is MethodInsnNode -> checkRef(insn.owner, insn.name)
            is FieldInsnNode -> checkRef(insn.owner, insn.name)
            // Method references such as Math::sin.
            is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<Handle>().forEach { checkRef(it.owner, it.name) }
        }
    }

    companion object {
        /** Math/StrictMath methods whose results are exactly specified, so identical on every platform. */
        val EXACT_MATH = listOf(
            "abs", "absExact", "min", "max", "clamp", "signum", "copySign",
            "sqrt", "fma", "IEEEremainder", "floor", "ceil", "rint", "round",
            "getExponent", "ulp", "nextUp", "nextDown", "nextAfter", "scalb",
            "addExact", "subtractExact", "multiplyExact", "divideExact", "negateExact",
            "incrementExact", "decrementExact", "toIntExact", "multiplyFull", "multiplyHigh", "unsignedMultiplyHigh",
            "floorDiv", "floorMod", "floorDivExact", "ceilDiv", "ceilMod", "ceilDivExact",
        ).flatMap { listOf("java.lang.Math#$it", "java.lang.StrictMath#$it") }
    }
}
