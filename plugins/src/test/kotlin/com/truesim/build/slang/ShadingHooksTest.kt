package com.truesim.build.slang

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `truesim.slang`'s shading hooks (ShadingHooks): `[ShadingHook]` structs of a project and its libraries merged into one
 * dispatch, the listing, and variants of the libraries' shaders that shade, over small Slang projects built with
 * Gradle TestKit. The templates are a small engine's: a hook point `tint`, called under a specialization constant.
 */
class ShadingHooksTest {

    @Test
    fun `a hook goes into the dispatch under its bit, with its constant and import`() {
        val project = SlangProject("hookDispatch").project(configuration = "slang { shadingHooks = 'com.example.Hooks' }")
        engine(project, "")
        project.slang("game/red.slang", hook("game.red", "RedHook", "float3(1, 0, 0)"))

        project.build("generateSlangRecords")

        val dispatch = project.output("generated/slang-records/main/slang/shading-hooks/dispatch.slang").readText()
        assertThat(dispatch).contains("import game.red;")
            .contains("public static const uint HOOK_GAME_RED = 0; // game.red")
            .contains("public static const uint HOOK_COUNT = 1;")
            .contains("if ((SHADING_HOOKS & 1u) != 0) { c = RedHook.tint(c, data[0]); } // game.red")
        assertThat(project.java("Hooks", "com.example")).contains("NAMES = { \"game.red\" };")
        val index = project.output("generated/slang-records/main/index/shading-hooks.index").readText()
        assertThat(index).contains("\"kind\":\"hook\"").contains("\"name\":\"game.red\"").contains("\"index\":0")
            .contains("\"points\":[\"tint\"]")
    }

    @Test
    fun `a project without hooks still gets the dispatch its libraries' shading includes, and compiles`() {
        val project = SlangProject("noHooks").project(configuration = "")
        engine(project, "")

        project.build("compileSlang")

        val dispatch = project.output("generated/slang-records/main/slang/shading-hooks/dispatch.slang").readText()
        assertThat(dispatch).contains("public static const uint HOOK_COUNT = 0;").doesNotContain("SHADING_HOOKS & ")
        assertThat(project.output("generated/slang/main/shaders/lit.spv")).isFile()
    }

    @Test
    fun `a project's hooks follow its library's, and its listing rebuilds the library's shaders that shade`() {
        val project = SlangProject("hookLibrary")
            .project(":lib", "slang {\n    shadingHooks = 'com.example.lib.LibHooks'\n" +
                "    shadingShaders = ['lit']\n    javaPackage = 'com.example.lib'\n}\n\n" +
                "tasks.named('compileJava') { enabled = false }")
            .project(":app", "slang { shadingHooks = 'com.example.game.GameHooks' }\n\ndependencies {\n" +
                "    implementation project(':lib')\n}")
        engine(project, ":lib")
        project.attributes(":lib")
            .slang("lib/fog.slang", hook("lib.fog", "FogHook", "float3(0.5, 0.5, 0.5)"), ":lib")
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
            .slang("game/red.slang", hook("game.red", "RedHook", "float3(1, 0, 0)"), ":app")

        project.build(":app:compileSlang")

        val dispatch = project.output("generated/slang-records/main/slang/shading-hooks/dispatch.slang", ":app")
            .readText()
        assertThat(dispatch).contains("import lib.fog;").contains("import game.red;")
            .contains("public static const uint HOOK_LIB_FOG = 0; // lib.fog")
            .contains("public static const uint HOOK_GAME_RED = 1; // game.red")
            .contains("if ((SHADING_HOOKS & 2u) != 0) { c = RedHook.tint(c, data[1]); } // game.red")
        assertThat(project.java("GameHooks", "com.example.game", ":app"))
            .contains("NAMES = { \"lib.fog\", \"game.red\" };")
            .contains("\"shaders/lit.spv\", \"shaders/shading-hooks/GameHooks/lit.hooks.spv\"")
            .contains("\"shaders/material-types/glow.spv\", \"shaders/shading-hooks/GameHooks/material-types/glow.spv\"")
        val shaders = "generated/slang/main/shaders/shading-hooks/GameHooks"
        assertThat(project.output("$shaders/lit.hooks.spv", ":app")).isFile()
        assertThat(project.output("$shaders/material-types/glow.spv", ":app")).isFile()
        val index = project.output("generated/slang-records/main/index/shading-hooks.index", ":app").readText()
        assertThat(index).contains("game.red").doesNotContain("lib.fog")
        val libraryIndex = project.output("generated/slang-records/main/index/shading-hooks.index", ":lib").readText()
        assertThat(libraryIndex).contains("\"kind\":\"shader\",\"path\":\"lit\"")
            .contains("\"kind\":\"type\",\"name\":\"glow\",\"struct\":\"Glow\",\"module\":\"glowType\"")
    }

    @Test
    fun `a hook without a hook point fails naming the points`() {
        val project = SlangProject("noPoint").project(configuration = "slang { shadingHooks = 'com.example.Hooks' }")
        engine(project, "")
        project.slang("game/idle.slang", """
            module idle;

            import hooks;

            [ShadingHook("game.idle")]
            public struct IdleHook
            {
                public static float3 other(float3 c) { return c; }
            };
        """)

        assertThat(project.fail("generateSlangRecords").output)
            .contains("IdleHook ([ShadingHook(\"game.idle\")], game.idle) has no hook point: define one of [tint]")
    }

    @Test
    fun `hooks without a listing fail naming the setting`() {
        val project = SlangProject("noListing").project(configuration = "")
        engine(project, "")
        project.slang("game/red.slang", hook("game.red", "RedHook", "float3(1, 0, 0)"))

        assertThat(project.fail("generateSlangRecords").output)
            .contains("Shading hooks (RedHook) need slang.shadingHooks, the generated Java class listing them")
    }

    @Test
    fun `more hooks than the dispatch takes fail naming the limit`() {
        val project = SlangProject("tooMany").project(configuration = "slang { shadingHooks = 'com.example.Hooks' }")
        engine(project, "")
        for (i in 0 until 3) {
            project.slang("game/hook$i.slang", hook("game.hook$i", "Hook$i", "float3(0, 0, 0)"))
        }

        assertThat(project.fail("generateSlangRecords").output)
            .contains("3 shading hooks (game.hook0, game.hook1, game.hook2): a dispatch takes at most 2 (hooks.max)")
    }

    @Test
    fun `a shader that shades must be one of the sources`() {
        val project = SlangProject("noShader")
            .project(configuration = "slang { shadingShaders = ['missing'] }")
        engine(project, "")

        assertThat(project.fail("generateSlangRecords").output)
            .contains("slang.shadingShaders names missing, which is no shader of")
    }

    /**
     * A small engine in [path]'s sources: the hook templates (a point `tint`, two hooks at most), the module `shade`
     * including the dispatch, and a shader `lit` shading through it.
     */
    private fun engine(project: SlangProject, path: String) {
        project.slang("shading-hooks/hooks.properties", """
            points=tint
            call.tint=c = {struct}.tint(c, data[{index}]);
            case=    if ((SHADING_HOOKS & {bit}) != 0) { {call} } // {name}\n
            constant=HOOK_{NAME}
            hooks.max=2
            typeEntries=
        """, path)
        project.slang("shading-hooks/dispatch.slang.template", """
            {imports}
            {constants}
            public static const uint HOOK_COUNT = {count};

            [vk::constant_id(3)]
            public const uint SHADING_HOOKS = 0;

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
                public static final String[] REPLACED = {
                    {replaced}
                };
            }
        """, path)
        project.slang("hooks.slang", """
            module hooks;

            [__AttributeUsage(_AttributeTargets.Struct)]
            public struct ShadingHookAttribute { string name; };
        """, path)
        project.slang("shade.slang", """
            module shade;

            #include "shading-hooks/dispatch.slang"

            public float3 shade(uint64_t* hooks, float3 c)
            {
                return tintHooks(c, hooks);
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
    }

    /** A hook module: [struct] marked [ShadingHook] [name], tinting by [color]. */
    private fun hook(name: String, struct: String, color: String) = """
        module ${name.substringAfterLast('.')};

        import hooks;

        [ShadingHook("$name")]
        public struct $struct
        {
            public static float3 tint(float3 c, uint64_t data)
            {
                return c * $color;
            }
        };
    """
}
