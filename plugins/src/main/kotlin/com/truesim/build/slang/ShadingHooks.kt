package com.truesim.build.slang

import groovy.json.JsonOutput
import java.io.File

/**
 * The shading hooks of a project and its libraries (`[ShadingHook("name")]` structs), merged into one generated
 * dispatch, from the templates of a `shading-hooks` directory of the sources or a library (engine-render ships them):
 * `hooks.properties`, `dispatch.slang.template` and `Hooks.java.template`. As with [ParticleModules], the plugin knows
 * no engine: the hook points, their interfaces, how each is called, where a hook's parameters are and what the listing
 * looks like all come from the templates.
 *
 * A hook's struct conforms to the interface of each hook point it has (`interface.<point>` of the properties, e.g.
 * `struct Fog : IMediaHook`), so the compiler checks its functions, and names its parameters, `typealias Parameters =
 * X;`, X a `[ShadingHookParameters]` record of the same sources: a Java record is generated for it as for material
 * records (any size), and the dispatch hands the hook a pointer to it. Hooks are ordered: libraries' as their indexes
 * order them, then the project's by file and place, then by their `[ShadingHookAfter("name")]` and
 * `[ShadingHookBefore("name")]` (a hook named nowhere is ignored; a cycle fails the build). A hook's place in that order
 * is its index: its own specialization constant (a bool, id `switch.first` + index, far above any shader's own) and its
 * place in a view's data. The dispatch, `shading-hooks/dispatch.slang` on the include path, is written for every
 * project seeing the templates, so the modules including it (the library's shading) compile in any project with the
 * hooks that project knows.
 *
 * A library's shaders are compiled in its own build, with the hooks it knows. A project naming a listing class (the
 * Java class [generate] writes) also gets variants of its libraries' shaders that shade: each material type's entry
 * points of the property `typeEntries` and each shader of a library that shades (whose dependencies include the
 * dispatch, as its build found them: [CompileSlang]'s `shading-shaders.index`),
 * rebuilt here with every hook, under `shading-hooks/<Class>/` (a shader `<path>` as `<path>.hooks`, a type's entry
 * points as they are named); the listing says which shader each replaces.
 */
internal class ShadingHooks(private val root: File, private val includes: Collection<File>) {

    /**
     * A hook: [parameters] its record's struct, [javaClass] the record's Java class, [bytes] its size; [after] and
     * [before] the hooks it is ordered against.
     */
    class Hook(
        val name: String, val struct: String, val module: String, val points: List<String>, val index: Int,
        val parameters: String, val javaClass: String, val bytes: Int, val after: List<String>,
        val before: List<String>,
    ) {
        fun json(): String = JsonOutput.toJson(mapOf("kind" to "hook", "name" to name, "struct" to struct,
            "module" to module, "points" to points, "index" to index, "parameters" to parameters,
            "javaClass" to javaClass, "bytes" to bytes, "after" to after, "before" to before))

        fun at(i: Int) = Hook(name, struct, module, points, i, parameters, javaClass, bytes, after, before)
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
     * libraries' shading shaders; returns the project's index lines: its hooks and its material [types]. [records] are
     * the sources' records as slangc lays them out, [library] the libraries' records by struct.
     */
    fun generate(listing: String?, types: List<Type>,
        records: Map<String, GenerateSlangRecords.Struct>, library: Map<String, GenerateSlangRecords.IndexEntry>,
        javaPackage: String?, javaOut: File, slangOut: File): List<String> {
        val dir = templates
        if (dir == null) {
            check(declared.isEmpty() && listing == null) {
                "Shading hooks need the templates of a shading-hooks directory (hooks.properties, " +
                    "dispatch.slang.template, Hooks.java.template), in the sources or a library such as engine-render"
            }
            return types.map { it.json() }
        }
        val property = MergedStructs.properties(File(dir, "hooks.properties"))
        val points = property("points").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val libraryHooks = libraryHooks()
        val found = libraryHooks.toMutableList()
        for (d in declared) {
            val pointsOf = points.filter { property("interface.$it") in d.conforms }
            check(pointsOf.isNotEmpty()) {
                "${d.struct} ([ShadingHook(\"${d.name}\")], ${d.module}) has no hook point: conform to one of " +
                    points.map { property("interface.$it") }
            }
            val parameters = PARAMETERS.find(d.body)?.groupValues?.get(1) ?: throw IllegalStateException(
                "${d.struct} needs `public typealias Parameters = <its [ShadingHookParameters] record>;`")
            val struct = records[parameters]
            check(struct != null && library[parameters] == null) {
                "${d.struct}'s parameters $parameters are no [ShadingHookParameters] record of these sources: " +
                    "declare it beside the hook"
            }
            check("ShadingHookParameters" in struct.attributes) {
                "${d.struct}'s parameters $parameters are no [ShadingHookParameters] record: mark it so"
            }
            val javaClass = "${javaPackage ?: throw IllegalStateException("$parameters needs slang.javaPackage")}." +
                parameters
            found += Hook(d.name, d.struct, d.module, pointsOf, found.size, parameters, javaClass, struct.bytes(),
                AFTER.findAll(d.attributes).map { it.groupValues[1] }.toList(),
                BEFORE.findAll(d.attributes).map { it.groupValues[1] }.toList())
        }
        val all = ordered(found)
        all.groupBy { it.name }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { name ->
            throw IllegalStateException("Two shading hooks are called $name: " +
                all.filter { it.name == name }.joinToString { "${it.struct} (${it.module})" })
        }
        all.groupBy { it.struct }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { struct ->
            throw IllegalStateException("Two shading hooks' structs are called $struct (" +
                all.filter { it.struct == struct }.joinToString { it.module } + "): one dispatch imports them all")
        }
        File(slangOut, DISPATCH).apply { parentFile.mkdirs() }
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
        val own = declared.map { it.name }.toSet()
        return all.filter { it.name in own }.map { it.json() } + types.map { it.json() }
    }

    /**
     * [hooks] (in their found order) ordered by their after and before: the found order where they say nothing, a
     * hook ordered against one not here ignoring it; renumbered. Fails on a cycle.
     */
    private fun ordered(hooks: List<Hook>): List<Hook> {
        val byName = hooks.associateBy { it.name }
        val edges = hooks.associate { it.name to mutableSetOf<String>() } // name -> those after it
        for (h in hooks) {
            h.after.filter { it in byName && it != h.name }.forEach { edges.getValue(it) += h.name }
            h.before.filter { it in byName && it != h.name }.forEach { edges.getValue(h.name) += it }
        }
        val waiting = hooks.associate { it.name to 0 }.toMutableMap()
        edges.values.forEach { later -> later.forEach { waiting[it] = waiting.getValue(it) + 1 } }
        val result = mutableListOf<Hook>()
        val ready = hooks.filter { waiting.getValue(it.name) == 0 }.toMutableList()
        while (ready.isNotEmpty()) {
            val next = ready.minByOrNull { hooks.indexOf(it) }!!
            ready.remove(next)
            result += next.at(result.size)
            for (later in edges.getValue(next.name)) {
                waiting[later] = waiting.getValue(later) - 1
                if (waiting.getValue(later) == 0) {
                    ready += byName.getValue(later)
                }
            }
        }
        check(result.size == hooks.size) {
            "Shading hooks ordered in a cycle by [ShadingHookAfter]/[ShadingHookBefore]: " +
                hooks.map { it.name }.filter { n -> result.none { it.name == n } }
        }
        return result
    }

    // ------------------------------------------------------------------------------------------------ libraries

    /** The libraries' hooks, numbered from 0 in the order their indexes give (then by name). */
    private fun libraryHooks(): List<Hook> = libraryEntries("hook")
        .sortedWith(compareBy({ (it["index"] as Number).toInt() }, { it["name"] as String }))
        .mapIndexed { i, e ->
            @Suppress("UNCHECKED_CAST")
            Hook(e["name"] as String, e["struct"] as String, e["module"] as String, e["points"] as List<String>, i,
                e["parameters"] as String, e["javaClass"] as String, (e["bytes"] as Number).toInt(),
                e["after"] as List<String>? ?: emptyList(), e["before"] as List<String>? ?: emptyList())
        }

    private fun libraryEntries(kind: String) =
        (MergedStructs.libraryEntries(includes, INDEX) { "${it["kind"]} ${it["name"]}" } +
            MergedStructs.libraryEntries(includes, CompileSlang.SHADING_INDEX) { "${it["kind"]} ${it["path"]}" })
            .filter { it["kind"] == kind }

    // ------------------------------------------------------------------------------------------------ the output

    private fun constant(hook: Hook, property: (String) -> String): String =
        property("constant").replace("{NAME}", MergedStructs.constant(hook.name))

    private fun switch(hook: Hook, property: (String) -> String) =
        property("switch.name").replace("{NAME}", MergedStructs.constant(hook.name))

    private fun dispatch(template: String, all: List<Hook>, points: List<String>, property: (String) -> String):
        String {
        val first = property("switch.first").toInt()
        var text = template
            .replace("{count}", all.size.toString())
            .replace("{imports}", all.map { it.module }.distinct().joinToString("") { "import $it;\n" })
            .replace("{constants}", all.joinToString("") {
                "public static const uint ${constant(it, property)} = ${it.index}; // ${it.name}\n"
            })
            .replace("{switches}", all.joinToString("") {
                property("switch.declaration").replace("{id}", (first + it.index).toString())
                    .replace("{name}", switch(it, property))
            })
            .replace("{any}", if (all.isEmpty()) "false" else all.joinToString(" || ") { switch(it, property) })
        for (point in points) {
            val call = property("call.$point")
            text = text.replace("{calls:$point}", all.filter { point in it.points }.joinToString("") { hook ->
                property("case").replace("{call}", call).replace("{params}", property("params"))
                    .replace("{struct}", hook.struct)
                    .replace("{parameters}", hook.parameters)
                    .replace("{switch}", switch(hook, property))
                    .replace("{index}", hook.index.toString())
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
            .replace("{parameters}", all.joinToString(", ") { "${it.javaClass}.class" })
            .replace("{bytes}", all.joinToString(", ") { it.bytes.toString() })
            .replace("{firstSwitch}", property("switch.first"))
            .replace("{replaced}", replaced.joinToString(",\n            ") { "\"${it.first}\", \"${it.second}\"" })
        File(javaOut, listing.replace('.', '/') + ".java").apply { parentFile.mkdirs() }.writeText(java)
    }

    companion object {
        /** A project's shading hooks, shaders that shade and material types, beside its modules: JSON per line. */
        const val INDEX = "shading-hooks.index"

        /** The generated dispatch, on the include path. */
        const val DISPATCH = "shading-hooks/dispatch.slang"

        private val HOOK = Regex("\\[ShadingHook\\(\"([\\w.]+)\"\\)]")
        private val PARAMETERS = Regex("typealias\\s+Parameters\\s*=\\s*(\\w+)\\s*;")
        private val AFTER = Regex("\\[ShadingHookAfter\\(\"([\\w.]+)\"\\)]")
        private val BEFORE = Regex("\\[ShadingHookBefore\\(\"([\\w.]+)\"\\)]")
    }
}
