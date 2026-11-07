package com.truesim.build.slang

import groovy.json.JsonOutput
import java.io.File

/**
 * The shading hooks of a project and its libraries (`[ShadingHook("name")]` structs), merged into one generated
 * dispatch, from the templates of a `shading-hooks` directory of the sources or a library (engine-render ships them):
 * `hooks.properties`, `dispatch.slang.template` and `Hooks.java.template`. As with [ParticleModules], the plugin knows
 * no engine: the hook points, how each is called and what the listing looks like all come from the templates.
 *
 * A hook's struct defines a function per hook point it has (`points` of the properties). Hooks are numbered in a fixed
 * order, the libraries' as their indexes number them, then the project's by file and place; a hook's number is its
 * bit in the dispatch's mask and its place in the table of its data. The dispatch, `shading-hooks/dispatch.slang` on
 * the include path, is written for every project seeing the templates, so the modules including it (the library's
 * shading) compile in any project with the hooks that project knows.
 *
 * A library's shaders are compiled in its own build, with the hooks it knows. A project naming a listing class (the
 * Java class [generate] writes) also gets variants of its libraries' shaders that shade: each material type's entry
 * points of the property `typeEntries` and each shader a library lists as shading (its `slang.shadingShaders`),
 * rebuilt here with every hook, under `shading-hooks/<Class>/` (a shader `<path>` as `<path>.hooks`, a type's entry
 * points as they are named); the listing says which shader each replaces.
 */
internal class ShadingHooks(private val root: File, private val includes: Collection<File>) {

    class Hook(val name: String, val struct: String, val module: String, val points: List<String>, val index: Int) {
        fun json(): String = JsonOutput.toJson(mapOf("kind" to "hook", "name" to name, "struct" to struct,
            "module" to module, "points" to points, "index" to index))
    }

    /** A material type: its entry points are named and made from the material-types templates as [name]. */
    class Type(val name: String, val struct: String, val module: String) {
        fun json(): String = JsonOutput.toJson(mapOf("kind" to "type", "name" to name, "struct" to struct,
            "module" to module))
    }

    private val declared = MergedStructs.scan(root, HOOK)

    /** The templates' directory, or null where neither the sources nor a library ship them. */
    private val templates = MergedStructs.templates(root, includes, "shading-hooks", "hooks.properties")

    /** Whether the project's sources declare shading hooks. */
    fun any() = declared.isNotEmpty()

    /**
     * Writes the dispatch (where the templates are found), and with a [listing] class its Java and the variants of the
     * libraries' shading shaders; returns the project's index lines: its hooks, its [shaders] that shade and its
     * material [types].
     */
    fun generate(listing: String?, shaders: List<String>, types: List<Type>, javaOut: File, slangOut: File):
        List<String> {
        val dir = templates
        if (dir == null) {
            check(declared.isEmpty() && listing == null) {
                "Shading hooks need the templates of a shading-hooks directory (hooks.properties, " +
                    "dispatch.slang.template, Hooks.java.template), in the sources or a library such as engine-render"
            }
            return shaders.map { shaderLine(it) } + types.map { it.json() }
        }
        val property = MergedStructs.properties(File(dir, "hooks.properties"))
        val points = property("points").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val library = libraryHooks()
        val all = library.toMutableList()
        for (d in declared) {
            val pointsOf = points.filter { it in d.functions }
            check(pointsOf.isNotEmpty()) {
                "${d.struct} ([ShadingHook(\"${d.name}\")], ${d.module}) has no hook point: define one of $points"
            }
            all += Hook(d.name, d.struct, d.module, pointsOf, all.size)
        }
        all.groupBy { it.name }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { name ->
            throw IllegalStateException("Two shading hooks are called $name: " +
                all.filter { it.name == name }.joinToString { "${it.struct} (${it.module})" })
        }
        all.groupBy { it.struct }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { struct ->
            throw IllegalStateException("Two shading hooks' structs are called $struct (" +
                all.filter { it.struct == struct }.joinToString { it.module } + "): one dispatch imports them all")
        }
        val max = property("hooks.max").toInt()
        check(all.size <= max) {
            "${all.size} shading hooks (${all.joinToString { it.name }}): a dispatch takes at most $max (hooks.max)"
        }
        for (shader in shaders) {
            check(File(root, "$shader.slang").isFile) {
                "slang.shadingShaders names $shader, which is no shader of $root ($shader.slang)"
            }
        }
        File(slangOut, "shading-hooks/dispatch.slang").apply { parentFile.mkdirs() }
            .writeText("// Generated from the [ShadingHook] structs of these sources and their libraries.\n" +
                dispatch(File(dir, "dispatch.slang.template").readText(), all, points, property))
        if (listing == null) {
            check(declared.isEmpty()) {
                "Shading hooks (${declared.joinToString { it.struct }}) need slang.shadingHooks, the generated Java " +
                    "class listing them"
            }
        } else {
            writeListing(listing, all, property, File(dir, "Hooks.java.template"), javaOut, slangOut)
        }
        return all.drop(library.size).map { it.json() } + shaders.map { shaderLine(it) } + types.map { it.json() }
    }

    // ------------------------------------------------------------------------------------------------ libraries

    /** The libraries' hooks, numbered from 0 in the order their indexes give (then by name). */
    private fun libraryHooks(): List<Hook> = libraryEntries("hook")
        .sortedWith(compareBy({ (it["index"] as Number).toInt() }, { it["name"] as String }))
        .mapIndexed { i, e ->
            @Suppress("UNCHECKED_CAST")
            Hook(e["name"] as String, e["struct"] as String, e["module"] as String, e["points"] as List<String>, i)
        }

    private fun libraryEntries(kind: String) =
        MergedStructs.libraryEntries(includes, INDEX) { "${it["kind"]} ${it["name"] ?: it["path"]}" }
            .filter { it["kind"] == kind }

    // ------------------------------------------------------------------------------------------------ the output

    private fun constant(hook: Hook, property: (String) -> String): String =
        property("constant").replace("{NAME}", MergedStructs.constant(hook.name))

    private fun dispatch(template: String, all: List<Hook>, points: List<String>, property: (String) -> String):
        String {
        var text = template
            .replace("{count}", all.size.toString())
            .replace("{imports}", all.map { it.module }.distinct().joinToString("") { "import $it;\n" })
            .replace("{constants}", all.joinToString("") {
                "public static const uint ${constant(it, property)} = ${it.index}; // ${it.name}\n"
            })
        for (point in points) {
            val call = property("call.$point")
            text = text.replace("{calls:$point}", all.filter { point in it.points }.joinToString("") { hook ->
                property("case").replace("{call}", call).replace("{struct}", hook.struct)
                    .replace("{index}", hook.index.toString())
                    .replace("{bit}", "${1L shl hook.index}u")
                    .replace("{name}", hook.name)
            })
        }
        return text
    }

    /** The Java listing, and the variants of the libraries' shaders that shade, with every hook. */
    private fun writeListing(listing: String, all: List<Hook>, property: (String) -> String, template: File,
        javaOut: File, slangOut: File) {
        val simple = listing.substringAfterLast('.')
        val replaced = mutableListOf<Pair<String, String>>()
        for (entry in libraryEntries("shader")) {
            val shader = entry["path"] as String
            val variant = "shading-hooks/$simple/$shader.hooks" // not $shader: its #include would find itself
            File(slangOut, "$variant.slang").apply { parentFile.mkdirs() }.writeText(
                "// Generated: $shader's entry points ([shader(...)]) with the shading hooks of $listing.\n" +
                    "#include \"$shader.slang\"\n")
            replaced += "shaders/$shader.spv" to "shaders/$variant.spv"
        }
        val types = libraryEntries("type")
        if (types.isNotEmpty()) {
            val materials = MergedStructs.templates(root, includes, "material-types", "type.properties")
                ?: throw IllegalStateException("Material types' variants need the templates of a material-types " +
                    "directory, in the sources or a library such as engine-render")
            val suffixes = property("typeEntries").split(',').map { it.trim() }
            for (type in types) {
                val name = type["name"] as String
                for (suffix in suffixes) {
                    val entry = File(materials, "entry$suffix.slang.template")
                    check(entry.isFile) { "${template.parentFile}/hooks.properties names type entries $suffix, " +
                        "which $materials has no template of (entry$suffix.slang.template)" }
                    val variant = "shading-hooks/$simple/material-types/$name$suffix"
                    File(slangOut, "$variant.slang").apply { parentFile.mkdirs() }.writeText(
                        "// Generated: ${type["module"]}'s ${type["struct"]}, its material type's entry points with " +
                            "the shading hooks of $listing.\n" + entry.readText().replace("{name}", name)
                            .replace("{struct}", type["struct"] as String).replace("{module}", type["module"] as String))
                    replaced += "shaders/material-types/$name$suffix.spv" to "shaders/$variant.spv"
                }
            }
        }
        val java = template.readText()
            .replace("{package}", listing.substringBeforeLast('.'))
            .replace("{class}", simple)
            .replace("{names}", all.joinToString(", ") { "\"${it.name}\"" })
            .replace("{replaced}", replaced.joinToString(",\n            ") { "\"${it.first}\", \"${it.second}\"" })
        File(javaOut, listing.replace('.', '/') + ".java").apply { parentFile.mkdirs() }.writeText(java)
    }

    private fun shaderLine(path: String) = JsonOutput.toJson(mapOf("kind" to "shader", "path" to path))

    companion object {
        /** A project's shading hooks, shaders that shade and material types, beside its modules: JSON per line. */
        const val INDEX = "shading-hooks.index"

        private val HOOK = Regex("\\[ShadingHook\\(\"([\\w.]+)\"\\)]")
    }
}
