package com.truesim.build.slang

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import java.io.File

/** `truesim.slang`'s material records (GenerateSlangRecords), over small Slang projects built with Gradle TestKit. */
class GenerateSlangRecordsTest {

    private val glow = """
        module glow;

        import materials;

        [MaterialParameters(3)]
        public struct GlowParameters
        {
            [Color] public float3 color = float3(1, 0, 1);
            [Range(0, 16)] public float strength = 1;
            public float2 offset = float2(0.5, -1);
            public int layer = 2;
        };
    """

    @Test
    fun `a record becomes a Java record with slangc's layout and the struct's defaults`() {
        val project = SlangProject("record").project().attributes().slang("glow.slang", glow)

        val result = project.build("generateSlangRecords")

        assertThat(result.task(":generateSlangRecords")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val java = project.java("GlowParameters")
        assertThat(java)
            .contains("package com.example.materials;")
            .contains("Generated from glow's {@code GlowParameters}: a material type's parameters")
            .contains("public record GlowParameters(Color color, float strength, Vec2 offset, int layer) " +
                "implements MaterialParameters {")
            .contains("public static final int BYTES = 28;") // scalar layout: 12 + 4 + 8 + 4
            .contains("DEFAULTS = new GlowParameters(new Color(1, 0, 1, 1), 1f, new Vec2(0.5, -1), 2);")
            .contains("import com.truesim.common.math.color.Color;")
            .contains("import com.truesim.common.math.Vec2;")
        val index = project.output("generated/slang-records/main/index/slang-records.index").readText()
        assertThat(index).startsWith("GlowParameters com.example.materials.GlowParameters parameters 28 ")
    }

    @Test
    fun `a record nests a feature record of its sources`() {
        val project = SlangProject("nested").project().attributes().slang("layered.slang", """
            module layered;

            import materials;

            [MaterialFeature(4)]
            public struct Tiling
            {
                public float2 scale = float2(2, 2);
            };

            [MaterialParameters(4)]
            public struct Layered
            {
                public Tiling tiling;
                public float strength = 1;
            };
        """)

        project.build("generateSlangRecords")

        assertThat(project.java("Tiling")).contains("public record Tiling(Vec2 scale) implements MaterialFeature {")
        assertThat(project.java("Layered"))
            .contains("public record Layered(Tiling tiling, float strength) implements MaterialParameters {")
            .contains("DEFAULTS = new Layered(Tiling.DEFAULTS, 1f);")
    }

    @Test
    fun `a record of a file without visibility modifiers needs not be public`() {
        val project = SlangProject("legacy").project().attributes().slang("legacy.slang", """
            import materials;

            [MaterialParameters(0)]
            struct Legacy
            {
                float strength = 1;
            };
        """)

        project.build("generateSlangRecords")

        assertThat(project.java("Legacy")).contains("public record Legacy(float strength)")
    }

    @Test
    fun `a record struct that is not public in its module fails naming the file, struct and fix`() {
        val project = SlangProject("hidden").project().attributes().slang("hidden.slang", """
            module hidden;

            import materials;

            [MaterialParameters(0)]
            struct Hidden
            {
                float strength = 1;
            };
        """)

        val output = project.fail("generateSlangRecords").output

        assertThat(output).contains("${slang(project, "hidden.slang")}: the [MaterialParameters] struct Hidden is " +
            "not public, so the build cannot read it from outside module hidden: declare it `public struct Hidden`, " +
            "with public fields")
        assertThat(output).doesNotContain("Ptr<error")
    }

    @Test
    fun `a feature record that is not public in a file using public fails too`() {
        val project = SlangProject("hiddenFeature").project().attributes().slang("feature.slang", """
            import materials;

            public static const uint FEATURE = 1;

            [MaterialFeature(FEATURE)]
            struct Hidden
            {
                public float strength = 1;
            };
        """)

        assertThat(project.fail("generateSlangRecords").output).contains(
            "${slang(project, "feature.slang")}: the [MaterialFeature] struct Hidden is not public")
    }

    @Test
    fun `a default that is not a literal fails naming the file and field`() {
        val project = SlangProject("defaultExpression").project().attributes().slang("glow.slang", """
            module glow;

            import materials;

            static const float BRIGHT = 4;

            [MaterialParameters(0)]
            public struct GlowParameters
            {
                public float strength = BRIGHT * 2;
            };
        """)

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "glow.slang")}: " +
            "GlowParameters.strength: defaults are literals (`0.5`, `-1`, `float3(1, 0, 1)`), not `BRIGHT * 2`: " +
            "write its value")
    }

    @Test
    fun `a nested record with a default fails naming the file and the fix`() {
        val project = SlangProject("nestedDefault").project().attributes().slang("layered.slang", """
            module layered;

            import materials;

            [MaterialFeature(4)]
            public struct Tiling
            {
                public float scale = 1;
            };

            [MaterialParameters(4)]
            public struct Layered
            {
                public Tiling tiling = { 2 };
            };
        """)

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "layered.slang")}: " +
            "Layered.tiling: a nested record takes its own defaults: remove `= { 2 }`, and give Tiling's fields the " +
            "initializers")
    }

    @Test
    fun `a scalar type records do not hold fails naming the file and the types they do`() {
        val project = SlangProject("bool").project().attributes().slang("flags.slang", """
            module flags;

            import materials;

            [MaterialParameters(0)]
            public struct Flags
            {
                public bool on;
            };
        """)

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "flags.slang")}: " +
            "Flags.on: records hold 32-bit floats and integers, uint64_t addresses and buffers (T*), not bool: make " +
            "it a float, int or uint")
    }

    @Test
    fun `a matrix field fails naming the file and the kinds records hold`() {
        val project = SlangProject("matrix").project().attributes().slang("skewed.slang", """
            module skewed;

            import materials;

            [MaterialParameters(0)]
            public struct Skewed
            {
                public float4x4 transform;
            };
        """)

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "skewed.slang")}: " +
            "Skewed.transform is a matrix: material records hold scalars, vectors, buffer pointers (T*) and records")
    }

    @Test
    fun `two records of one name fail naming both files`() {
        val record = """
            [MaterialParameters(0)]
            public struct Glow
            {
                public float strength = 1;
            };
        """
        val project = SlangProject("duplicate").project().attributes()
            .slang("a/glow.slang", "module glow;\nimport materials;\n" + record.trimIndent())
            .slang("b/glow.slang", "module glow;\nimport materials;\n" + record.trimIndent())

        assertThat(project.fail("generateSlangRecords").output).contains("Two material records or types are called " +
            "Glow (in ${slang(project, "a/glow.slang")} and ${slang(project, "b/glow.slang")}): rename all but one, " +
            "as each becomes the Java class Glow")
    }

    @Test
    fun `records without slang javaPackage fail naming the record and the setting`() {
        val project = SlangProject("noPackage").project(configuration = "").attributes().slang("glow.slang", glow)

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "glow.slang")}: the " +
            "record GlowParameters needs slang.javaPackage, the generated Java's package: set it in the " +
            "slang { } block")
    }

    @Test
    fun `a material type gets its entry points and TYPE from the templates`() {
        val project = typeProject("materialType", "public typealias Parameters = GlowParameters;")

        project.build("generateSlangRecords")

        assertThat(project.java("Glow"))
            .contains("import com.example.render.MaterialType;")
            .contains("public static final MaterialType TYPE = new MaterialType(\"glow\", GlowParameters.class, 3);")
        val entry = project.output("generated/slang-records/main/slang/material-types/glow.slang").readText()
        assertThat(entry).contains("// Generated from glowType's Glow: its material type's entry points.")
            .contains("import glowType;").contains("Glow.surface; // glow")
        val traced = project.output("generated/slang-records/main/slang/material-types/glowTraced.slang").readText()
        assertThat(traced).contains("// traced glow")
    }

    @Test
    fun `a material type without its Parameters fails naming the file and the typealias`() {
        val project = typeProject("noParameters", "")

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "glowType.slang")}: Glow " +
            "needs `public typealias Parameters = <its [MaterialParameters] struct>;`")
    }

    @Test
    fun `a material type whose parameters are no record fails naming the file and the fix`() {
        val project = typeProject("plainParameters", "public typealias Parameters = Plain;")
            .slang("plain.slang", "module plain;\n\npublic struct Plain\n{\n    public float strength;\n};")
        project.slang("glowType.slang", File(project.dir, "src/main/slang/glowType.slang").readText()
            .replace("import glow;", "import glow;\nimport plain;"))

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "glowType.slang")}: " +
            "Plain is no material record of these sources or their libraries: mark it [MaterialParameters(features)] " +
            "or [MaterialFeature(feature)]")
    }

    @Test
    fun `a material type that is not public in its module fails naming the file, struct and fix`() {
        val project = typeProject("hiddenType", "public typealias Parameters = GlowParameters;")
        project.slang("glowType.slang", File(project.dir, "src/main/slang/glowType.slang").readText()
            .replace("public struct Glow", "struct Glow"))

        assertThat(project.fail("generateSlangRecords").output).contains("${slang(project, "glowType.slang")}: the " +
            "[MaterialType] struct Glow is not public, so the build cannot read it from outside module glowType: " +
            "declare it `public struct Glow`")
    }

    @Test
    fun `a material type without templates fails naming where they go`() {
        val project = typeProject("noTemplates", "public typealias Parameters = GlowParameters;")
        File(project.dir, "src/main/slang/material-types").deleteRecursively()

        assertThat(project.fail("generateSlangRecords").output).contains("Material types need the templates of a " +
            "material-types directory (type.properties and entry*.slang.template), in the sources or a library")
    }

    @Test
    fun `a record nests a library's record through its slang-records index`() {
        val project = SlangProject("library")
            // the library's jar without its classes, whose generated Java needs the engine
            .project(":lib", "slang { javaPackage = 'com.example.lib' }\n\n" +
                "tasks.named('compileJava') { enabled = false }")
            .attributes(":lib")
            .slang("tiling.slang", """
                module tiling;

                import materials;

                [MaterialFeature(4)]
                public struct Tiling
                {
                    public float2 scale = float2(1, 1);
                };
            """, ":lib")
            .project(":app", "slang { javaPackage = 'com.example.game' }\n\ndependencies {\n" +
                "    implementation project(':lib')\n}")
            .slang("tiled.slang", """
                module tiled;

                import materials;
                import tiling;

                [MaterialParameters(4)]
                public struct Tiled
                {
                    public Tiling tiling;
                    public float strength = 1;
                };
            """, ":app")

        project.build(":app:generateSlangRecords")

        assertThat(project.java("Tiled", "com.example.game", ":app"))
            .contains("import com.example.lib.Tiling;")
            .contains("public record Tiled(Tiling tiling, float strength) implements MaterialParameters {")
        val index = project.output("generated/slang-records/main/index/slang-records.index", ":app").readText()
        assertThat(index).startsWith("Tiled com.example.game.Tiled parameters ").doesNotContain("Tiling")
    }

    @Test
    fun `a library record whose layout no longer matches its index fails naming the project to rebuild`() {
        val project = SlangProject("staleIndex")
            .project(configuration = "slang { javaPackage = 'com.example.game' }\n\ndependencies {\n" +
                "    implementation files('stale')\n}")
            .write("stale/slang/materials.slang", SlangProject.ATTRIBUTES)
            .write("stale/slang/tiling.slang", """
                module tiling;

                import materials;

                [MaterialFeature(4)]
                public struct Tiling
                {
                    public float2 scale = float2(1, 1);
                    public float rotation; // added since the index was written
                };
            """)
            .write("stale/slang/slang-records.index", "Tiling com.example.lib.Tiling feature 8 1234abcd")
            .slang("tiled.slang", """
                module tiled;

                import materials;
                import tiling;

                [MaterialParameters(4)]
                public struct Tiled
                {
                    public Tiling tiling;
                };
            """)

        assertThat(project.fail("generateSlangRecords").output).contains("Tiling's layout changed since its library " +
            "was built (its slang-records.index): rebuild com.example.lib.Tiling's project, or depend on the version " +
            "matching its Slang modules")
    }

    /** A project with a record `GlowParameters`, a material type `Glow` (with [typeBody]) and its templates. */
    private fun typeProject(name: String, typeBody: String) = SlangProject(name).project().attributes()
        .slang("glow.slang", glow)
        .slang("glowType.slang", """
            module glowType;

            import materials;
            import glow;

            [MaterialType("glow")]
            public struct Glow
            {
                $typeBody

                public static float3 surface(GlowParameters p)
                {
                    return p.color * p.strength;
                }
            };
        """)
        .slang("material-types/type.properties", """
            class=com.example.render.MaterialType
            factory=new MaterialType("{name}", {parameters}.class, {features})
        """)
        .slang("material-types/entry.slang.template", "import {module};\n// {struct}.surface; // {name}")
        .slang("material-types/entryTraced.slang.template", "import {module};\n// traced {name}")

    /** The path of [path] of [project]'s main Slang sources, as messages name it. */
    private fun slang(project: SlangProject, path: String) =
        File(project.dir, "src/main/slang/$path").path
}
