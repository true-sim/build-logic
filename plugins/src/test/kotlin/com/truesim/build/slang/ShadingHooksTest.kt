package com.truesim.build.slang

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `truesim.slang`'s shading hooks (ShadingHooks): `[ShadingHook]` structs of a project and its libraries merged into one
 * dispatch, their parameters as records, their order, the listing, and variants of the libraries' shaders that shade,
 * over small Slang projects built with Gradle TestKit. The templates are a small engine's: a hook point `tint` (the
 * interface ITintHook), each hook under a bool specialization constant of its own, its parameters by pointer.
 */
class ShadingHooksTest {

    @Test
    fun `a hook goes into the dispatch under its own switch, its parameters as a record by pointer`() {
        val project = SlangProject("hookDispatch").project(configuration = PACKAGE)
        engine(project, "")
        project.slang("game/red.slang", hook("game.red", "RedHook"))

        project.build("compileSlang")

        val dispatch = dispatch(project)
        assertThat(dispatch).contains("import game.red;")
            .contains("public static const uint HOOK_GAME_RED = 0; // game.red")
            .contains("public static const uint HOOK_COUNT = 1;")
            .contains("[vk::constant_id(1024)] public const bool RUN_GAME_RED = false;")
            .contains("if (RUN_GAME_RED) { c = RedHook.tint((RedHookParameters*) (data[0]), c); } // game.red")
            .contains("return RUN_GAME_RED;")
        assertThat(project.java("Hooks", "com.example")).contains("NAMES = { \"game.red\" };")
            .contains("com.example.hooks.RedHookParameters.class").contains("FIRST = 1024;")
        assertThat(project.java("RedHookParameters", "com.example.hooks"))
            .contains("public record RedHookParameters(Vec3 color, float strength) implements ShadingHookParameters {")
        val index = project.output("generated/slang-records/main/index/shading-hooks.index").readText()
        assertThat(index).contains("\"kind\":\"hook\"").contains("\"name\":\"game.red\"").contains("\"index\":0")
            .contains("\"points\":[\"tint\"]").contains("\"parameters\":\"RedHookParameters\"")
        assertThat(project.output("generated/slang/main/shaders/lit.spv")).isFile()
    }

    @Test
    fun `a project without hooks still gets the dispatch its libraries' shading includes, and compiles`() {
        val project = SlangProject("noHooks").project(configuration = "")
        engine(project, "")

        project.build("compileSlang")

        assertThat(dispatch(project)).contains("public static const uint HOOK_COUNT = 0;").doesNotContain("RUN_")
            .contains("return false;")
        assertThat(project.output("generated/slang/main/shaders/lit.spv")).isFile()
        assertThat(project.output("generated/slang-shading/main/shading-shaders.index").readText())
            .contains("{\"kind\":\"shader\",\"path\":\"lit\"}").doesNotContain("plain")
    }

    @Test
    fun `hooks are ordered by after and before, past the order they were found in, without a limit`() {
        val project = SlangProject("orderedHooks").project(configuration = PACKAGE)
        engine(project, "")
        project.slang("game/a.slang", hook("game.snow", "Snow", "[ShadingHookAfter(\"game.wet\")]"))
        project.slang("game/b.slang", hook("game.wet", "Wet", "[ShadingHookAfter(\"game.dirt\")]"))
        project.slang("game/c.slang", hook("game.dirt", "Dirt", "[ShadingHookBefore(\"game.snow\")]" +
            "[ShadingHookAfter(\"other.missing\")]"))
        for (i in 0 until 40) {
            project.slang("many/h$i.slang", hook("many.h$i", "Many$i"))
        }

        project.build("compileSlang")

        val dispatch = dispatch(project)
        assertThat(dispatch.indexOf("Dirt.tint")).isLessThan(dispatch.indexOf("Wet.tint"))
        assertThat(dispatch.indexOf("Wet.tint")).isLessThan(dispatch.indexOf("Snow.tint"))
        assertThat(dispatch).contains("public static const uint HOOK_MANY_H9 = 42;") // by file: h9 is the 40th
            .contains("[vk::constant_id(1066)] public const bool RUN_MANY_H9 = false;")
        assertThat(project.output("generated/slang/main/shaders/lit.spv")).isFile()
    }

    @Test
    fun `hooks ordered in a cycle fail naming them`() {
        val project = SlangProject("cycle").project(configuration = PACKAGE)
        engine(project, "")
        project.slang("game/a.slang", hook("game.a", "A", "[ShadingHookAfter(\"game.b\")]"))
        project.slang("game/b.slang", hook("game.b", "B", "[ShadingHookAfter(\"game.a\")]"))

        assertThat(project.fail("generateSlangRecords").output)
            .contains("Shading hooks ordered in a cycle by [ShadingHookAfter]/[ShadingHookBefore]: [game.a, game.b]")
    }

    @Test
    fun `a project's hooks follow its library's, and its listing rebuilds the library's shaders that shade`() {
        val project = SlangProject("hookLibrary")
            .project(":lib", "slang {\n    shadingHooks = 'com.example.lib.LibHooks'\n" +
                "    javaPackage = 'com.example.lib'\n}\n\ntasks.named('compileJava') { enabled = false }")
            .project(":app", "slang {\n    shadingHooks = 'com.example.game.GameHooks'\n" +
                "    javaPackage = 'com.example.game'\n}\n\ndependencies {\n    implementation project(':lib')\n}")
        engine(project, ":lib")
        project.attributes(":lib")
            .slang("lib/fog.slang", hook("lib.fog", "FogHook"), ":lib")
            .slang("glowType.slang", """
                module glowType;

                import materials;

                [MaterialParameters(0)]
                public struct GlowParameters
                {
                    public float strength = 1;
                };

                [MaterialType("glow")]
                public struct Glow
                {
                    public typealias Parameters = GlowParameters;
                };
            """, ":lib")
            .slang("material-types/type.properties", """
                class=com.example.render.MaterialType
                factory=new MaterialType("{name}")
            """, ":lib")
            .slang("material-types/entry.slang.template", """
                // The type {name}'s shading.
                import {module};
                import shade;

                struct TypePush
                {
                    uint64_t* hooks;
                    float3* out;
                };

                [[vk::push_constant]]
                ConstantBuffer<TypePush> push;

                [shader("compute")]
                [numthreads(1, 1, 1)]
                void {name}Main()
                {
                    push.out[0] = shade(push.hooks, float3(1, 1, 1));
                }
            """, ":lib")
            .slang("game/red.slang", hook("game.red", "RedHook"), ":app")

        project.build(":app:compileSlang")

        val dispatch = dispatch(project, ":app")
        assertThat(dispatch).contains("import lib.fog;").contains("import game.red;")
            .contains("public static const uint HOOK_LIB_FOG = 0; // lib.fog")
            .contains("public static const uint HOOK_GAME_RED = 1; // game.red")
            .contains("if (RUN_GAME_RED) { c = RedHook.tint((RedHookParameters*) (data[1]), c); } // game.red")
        assertThat(project.java("GameHooks", "com.example.game", ":app"))
            .contains("NAMES = { \"lib.fog\", \"game.red\" };")
            .contains("com.example.lib.FogHookParameters.class, com.example.game.RedHookParameters.class")
            .contains("\"shaders/lit.spv\", \"shaders/shading-hooks/GameHooks/lit.hooks.spv\"")
            .contains("\"shaders/material-types/glow.spv\", \"shaders/shading-hooks/GameHooks/material-types/glow.spv\"")
            .doesNotContain("plain")
        val shaders = "generated/slang/main/shaders/shading-hooks/GameHooks"
        assertThat(project.output("$shaders/lit.hooks.spv", ":app")).isFile()
        assertThat(project.output("$shaders/material-types/glow.spv", ":app")).isFile()
        val index = project.output("generated/slang-records/main/index/shading-hooks.index", ":app").readText()
        assertThat(index).contains("game.red").doesNotContain("lib.fog")
        val libraryIndex = project.output("generated/slang-records/main/index/shading-hooks.index", ":lib").readText()
        assertThat(libraryIndex).contains("\"kind\":\"type\",\"name\":\"glow\",\"struct\":\"Glow\",\"module\":\"glowType\"")
    }

    @Test
    fun `a shader is compiled again only when something it depends on changed`() {
        val project = SlangProject("incremental").project(configuration = "")
        engine(project, "")
        project.build("compileSlang")
        val lit = project.output("generated/slang/main/shaders/lit.spv")
        val plain = project.output("generated/slang/main/shaders/plain.spv")
        val litTime = lit.lastModified()
        val plainTime = plain.lastModified()
        Thread.sleep(1100) // file times of a second's resolution
        project.slang("shade.slang", File(project.dir, "src/main/slang/shade.slang").readText() + "\n// changed\n")

        val result = project.build("compileSlang")

        assertThat(result.task(":compileSlang")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(lit.lastModified()).`as`("lit imports shade: compiled again").isGreaterThan(litTime)
        assertThat(plain.lastModified()).`as`("plain does not: kept").isEqualTo(plainTime)
    }

    @Test
    fun `a hook without a hook point fails naming the interfaces`() {
        val project = SlangProject("noPoint").project(configuration = PACKAGE)
        engine(project, "")
        project.slang("game/idle.slang", """
            module idle;

            import hooks;

            [ShadingHookParameters]
            public struct IdleParameters
            {
                public float strength;
            };

            [ShadingHook("game.idle")]
            public struct IdleHook
            {
                public typealias Parameters = IdleParameters;
            };
        """)

        assertThat(project.fail("generateSlangRecords").output)
            .contains("IdleHook ([ShadingHook(\"game.idle\")], game.idle) has no hook point: conform to one of [ITintHook]")
    }

    @Test
    fun `hooks without a listing fail naming the setting`() {
        val project = SlangProject("noListing").project(configuration = "slang { javaPackage = 'com.example.hooks' }")
        engine(project, "")
        project.slang("game/red.slang", hook("game.red", "RedHook"))

        assertThat(project.fail("generateSlangRecords").output)
            .contains("Shading hooks (RedHook) need slang.shadingHooks, the generated Java class listing them")
    }

    @Test
    fun `a hook's parameters must be a hook record of its sources`() {
        val project = SlangProject("plainParameters").project(configuration = PACKAGE)
        engine(project, "")
        project.slang("game/fog.slang", """
            module fog;

            import hooks;

            public struct Plain
            {
                public float density;
            };

            [ShadingHook("game.fog")]
            public struct Fog : ITintHook
            {
                public typealias Parameters = Plain;

                public static float3 tint(Plain* p, float3 c)
                {
                    return c;
                }
            };
        """)

        assertThat(project.fail("generateSlangRecords").output).contains("Fog's parameters Plain are no " +
            "[ShadingHookParameters] record of these sources: declare it beside the hook")
    }

    private fun dispatch(project: SlangProject, path: String = "") =
        project.output("generated/slang-records/main/slang/shading-hooks/dispatch.slang", path).readText()

    /**
     * A small engine in [path]'s sources: the hook templates (a point `tint`), the module `hooks` (attributes and the
     * point's interface), the module `shade` including the dispatch, a shader `lit` shading through it and a shader
     * `plain` that does not.
     */
    private fun engine(project: SlangProject, path: String) {
        project.slang("shading-hooks/hooks.properties", """
            points=tint
            interface.tint=ITintHook
            call.tint=c = {struct}.tint({params}, c);
            params=({parameters}*) (data[{index}])
            case=\    if ({switch}) { {call} } // {name}\n
            constant=HOOK_{NAME}
            switch.name=RUN_{NAME}
            switch.first=1024
            switch.declaration=[vk::constant_id({id})] public const bool {name} = false;\n
            typeEntries=
        """, path)
        project.slang("shading-hooks/dispatch.slang.template", """
            {imports}
            {constants}
            public static const uint HOOK_COUNT = {count};

            {switches}
            public bool anyHooks()
            {
                return {any};
            }

            public float3 tintHooks(float3 c, uint64_t* data)
            {
            {calls:tint}
                return c;
            }
        """, path)
        project.slang("shading-hooks/Hooks.java.template", """
            package {package};

            public final class {class} {
                public static final String[] NAMES = { {names} };
                public static final Class<?>[] PARAMETERS = { {parameters} };
                public static final int[] BYTES = { {bytes} };
                public static final int FIRST = {firstSwitch};
                public static final String[] REPLACED = {
                    {replaced}
                };
            }
        """, path)
        project.slang("hooks.slang", """
            module hooks;

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct ShadingHookAttribute { string name; };

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct ShadingHookParametersAttribute {};

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct ShadingHookAfterAttribute { string name; };

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct ShadingHookBeforeAttribute { string name; };

            public interface ITintHook
            {
                associatedtype Parameters;
                static float3 tint(Parameters* p, float3 c);
            };
        """, path)
        project.slang("shade.slang", """
            module shade;

            #include "shading-hooks/dispatch.slang"

            public float3 shade(uint64_t* hooks, float3 c)
            {
                return anyHooks() ? tintHooks(c, hooks) : c;
            }
        """, path)
        project.slang("lit.slang", """
            import shade;

            struct LitPush
            {
                uint64_t* hooks;
                float3* out;
            };

            [[vk::push_constant]]
            ConstantBuffer<LitPush> push;

            [shader("compute")]
            [numthreads(1, 1, 1)]
            void litMain()
            {
                push.out[0] = shade(push.hooks, float3(1, 1, 1));
            }
        """, path)
        project.slang("plain.slang", """
            struct PlainPush
            {
                float3* out;
            };

            [[vk::push_constant]]
            ConstantBuffer<PlainPush> push;

            [shader("compute")]
            [numthreads(1, 1, 1)]
            void plainMain()
            {
                push.out[0] = float3(1, 1, 1);
            }
        """, path)
    }

    /** A hook module: [struct] marked [ShadingHook] [name] (with [attributes]), tinting by its parameters. */
    private fun hook(name: String, struct: String, attributes: String = "") = """
        module ${name.substringAfterLast('.')};

        import hooks;

        [ShadingHookParameters]
        public struct ${struct}Parameters
        {
            public float3 color = float3(1, 0.5, 0.25);
            public float strength = 2;
        };

        [ShadingHook("$name")]
        $attributes
        public struct $struct : ITintHook
        {
            public typealias Parameters = ${struct}Parameters;

            public static float3 tint(${struct}Parameters* p, float3 c)
            {
                return c * p.color * p.strength;
            }
        };
    """

    private companion object {
        const val PACKAGE = "slang {\n    shadingHooks = 'com.example.Hooks'\n    javaPackage = 'com.example.hooks'\n}"
    }
}
