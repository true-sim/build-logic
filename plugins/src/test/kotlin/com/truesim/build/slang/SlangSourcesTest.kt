package com.truesim.build.slang

import com.truesim.build.slang.GenerateSlangRecords.Companion.explained
import com.truesim.build.slang.GenerateSlangRecords.Companion.internalByDefault
import com.truesim.build.slang.GenerateSlangRecords.Companion.withoutComments
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

/** How GenerateSlangRecords reads Slang sources, without compiling them. */
class SlangSourcesTest {

    @Test
    fun `declarations are internal by default in a module, a file implementing one, or one using modifiers`() {
        assertThat(internalByDefault("module glow;\nstruct S {};")).isTrue()
        assertThat(internalByDefault("  implementing glow;\nstruct S {};")).isTrue()
        assertThat(internalByDefault("public static const int X = 1;\nstruct S {};")).isTrue()
        assertThat(internalByDefault("struct S { private float a; };")).isTrue()
        assertThat(internalByDefault("import materials;\nstruct S { float a; };")).isFalse()
    }

    @Test
    fun `commented out modifiers do not count`() {
        assertThat(internalByDefault(withoutComments("// module glow; public\n/* internal */ struct S {};")))
            .isFalse()
    }

    @Test
    fun `a failure names its file once`() {
        val file = File("shaders", "glow.slang")

        assertThatThrownBy { explained(file) { error("Glow.a: no") } }.hasMessage("$file: Glow.a: no")
        assertThatThrownBy { explained(file) { explained(file) { error("Glow.a: no") } } }
            .hasMessage("$file: Glow.a: no")
    }
}
