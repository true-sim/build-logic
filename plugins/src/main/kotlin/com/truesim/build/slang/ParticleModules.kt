package com.truesim.build.slang

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.util.Properties

/**
 * The particle modules of a project and its libraries (`[ParticleModule("name")]` structs), collected into one
 * interpreting kernel's dispatch and the Java listing it is encoded for, from the templates of a `particle-modules`
 * directory of the sources or a library (engine-render ships them): `modules.properties`, `dispatch.slang.template`,
 * `Modules.java.template` and `entry<Suffix>.slang.template`s. The plugin itself knows no engine: what the stages are,
 * how a field is read and what a Java type looks like all come from the templates.
 *
 * A module's struct declares its fields (their types the kinds `field.<type>.*` of the properties name, their
 * initializers their fallbacks, in alphabetical order of their names) and a function per stage it has (`stages`); an
 * attribute `[ModuleCapability("NAME")]` puts its cases and import under the define `capability.NAME`, and
 * `[BuiltInTwin]` or `[ModuleTwin("java.Class")]` gives it a CPU target, simulated by the host itself or by that
 * class. Modules are numbered in a fixed order (the libraries' as their indexes number them, then the project's by
 * file and place) and each one's number is its opcode.
 */
internal class ParticleModules(private val root: File, private val includes: Collection<File>) {

    class Field(val name: String, val type: String, val initializer: String?)

    class Module(
        val name: String, val struct: String, val module: String, val stages: List<String>, val capability: String?,
        val twin: String?, val fields: List<Field>, val javaClass: String, val opcode: Int,
    ) {
        fun json(): String = JsonOutput.toJson(mapOf("name" to name, "struct" to struct, "module" to module,
            "stages" to stages, "capability" to capability, "twin" to twin, "javaClass" to javaClass,
            "opcode" to opcode, "fields" to fields.map { mapOf("name" to it.name, "type" to it.type,
                "initializer" to it.initializer) }))
    }

    /** A function of the struct's own (not of a nested scope), by name. */
    private class Declared(val name: String, val struct: String, val module: String, val attributes: String,
        val functions: Set<String>, val fields: List<Field>)

    /** The project's own modules, in the order of their files and places, before numbering. */
    private val declared: List<Declared> = if (root.isDirectory) scan() else emptyList()

    /** Whether the project's sources declare particle modules. */
    fun any() = declared.isNotEmpty()

    /**
     * Generates the dispatch, the Java listing [javaClass] and, where the project's modules extend its libraries', its
     * kernel variants' entry points; returns the project's index lines (its own modules).
     */
    fun generate(javaClass: String, javaOut: File, slangOut: File): List<String> {
        val dir = templates()
        val properties = Properties().apply { File(dir, "modules.properties").reader().use { load(it) } }
        fun property(key: String) = properties.getProperty(key)
            ?: throw IllegalStateException("$dir/modules.properties needs $key")
        val stages = property("stages").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val maxFields = property("fields.max").toInt()
        val library = libraryModules()
        val all = library.toMutableList()
        for (d in declared) {
            val stagesOf = stages.filter { it in d.functions }
            check(stagesOf.isNotEmpty()) {
                "${d.struct} ([ParticleModule(\"${d.name}\")]) has no stage function: define one of $stages"
            }
            val java = stagesOf.map { property("stage.$it") }.distinct()
            check(java.size == 1) { "${d.struct}'s stages ${stagesOf} run at different times ($java): keep to one" }
            check(d.fields.size <= maxFields) {
                "${d.struct} has ${d.fields.size} fields: a module has at most $maxFields (fields.max)"
            }
            for (field in d.fields) {
                check(properties.getProperty("field.${field.type}.words") != null) {
                    "${d.struct}.${field.name} is a ${field.type}: module fields are " +
                        properties.stringPropertyNames().filter { it.startsWith("field.") && it.endsWith(".words") }
                            .map { it.removePrefix("field.").removeSuffix(".words") }.sorted()
                }
            }
            val capability = CAPABILITY.find(d.attributes)?.groupValues?.get(1)
            capability?.let { check(properties.getProperty("capability.$it") != null) {
                "${d.struct} needs capability $it, which modules.properties has no define for (capability.$it)"
            } }
            val twin = TWIN.find(d.attributes)?.let { it.groupValues[2].ifEmpty { "" } }
            all += Module(d.name, d.struct, d.module, stagesOf, capability, twin, d.fields, javaClass, all.size)
        }
        all.groupBy { it.name }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { name ->
            throw IllegalStateException("Two particle modules are called $name: " +
                all.filter { it.name == name }.joinToString { "${it.struct} (${it.module})" })
        }
        all.groupBy { it.struct }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { struct ->
            throw IllegalStateException("Two particle modules' structs are called $struct (" +
                all.filter { it.struct == struct }.joinToString { it.module } + "): one kernel imports them all")
        }
        val maxOpcode = property("opcodes.max").toInt()
        check(all.size - 1 <= maxOpcode) { "${all.size} particle modules: opcodes go up to $maxOpcode (opcodes.max)" }
        val own = all.drop(library.size)
        File(slangOut, "particle-modules/dispatch.slang").apply { parentFile.mkdirs() }
            .writeText("// Generated from the [ParticleModule] structs of these sources and their libraries.\n" +
                dispatch(File(dir, "dispatch.slang.template").readText(), all, stages, properties))
        val simple = javaClass.substringAfterLast('.')
        val variant = library.isNotEmpty()
        if (variant) {
            dir.listFiles { f -> f.name.startsWith("entry") && f.name.endsWith(".slang.template") }!!
                .sortedBy { it.name }.forEach { template ->
                    val suffix = template.name.removePrefix("entry").removeSuffix(".slang.template")
                    File(slangOut, "particle-modules/$simple$suffix.slang").writeText(
                        "// Generated: a kernel variant with the particle modules of $javaClass.\n" +
                            template.readText().replace("{class}", simple))
                }
        }
        val java = File(dir, "Modules.java.template").readText()
            .replace("{package}", javaClass.substringBeforeLast('.'))
            .replace("{class}", simple)
            .replace("{entry}", if (variant) "particle-modules/$simple" else "")
            .replace("{own}", own.joinToString(",\n        ") { javaType(it, stages, properties) })
            .replace("{libraryTypes}", library.map { it.javaClass }.distinct().joinToString("") { ", $it.OWN" })
            .replace("{names}", all.joinToString(", ") { "\"${it.name}\"" })
            .replace("{stages}", all.joinToString(", ") { m ->
                m.stages.sumOf { 1 shl stages.indexOf(it).also { i ->
                    check(i >= 0) { "${m.name} (of ${m.javaClass}) has stage $it, which modules.properties lacks" }
                } }.toString()
            })
        File(javaOut, javaClass.replace('.', '/') + ".java").apply { parentFile.mkdirs() }.writeText(java)
        return own.map { it.json() }
    }

    // ------------------------------------------------------------------------------------------------ the sources

    private fun scan(): List<Declared> {
        val result = mutableListOf<Declared>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "slang" }.sortedBy { it.path }) {
            val text = GenerateSlangRecords.withoutComments(file.readText())
            val module = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".slang").replace('/', '.')
            for (match in STRUCT.findAll(text)) {
                val attributes = match.groupValues[1]
                val name = MODULE.find(attributes)?.groupValues?.get(1) ?: continue
                val struct = match.groupValues[2]
                val flat = flat(text, match.range.last, struct)
                val functions = FUNCTION.findAll(flat).map { it.groupValues[1] }.toSet()
                val fields = mutableListOf<Field>()
                for (piece in flat.replace(DEFINITION, ";").split(';')) {
                    val plain = piece.replace(ATTRIBUTE, " ").replace(Regex("\\b(public|internal)\\b"), " ").trim()
                    if (plain.isEmpty() || plain.startsWith("typealias") || plain.startsWith("static")) {
                        continue
                    }
                    val f = FIELD.matchEntire(plain)
                        ?: throw IllegalStateException("$struct: cannot read the field `$plain` (one per declaration)")
                    fields += Field(f.groupValues[2], f.groupValues[1], f.groupValues[3].trim().ifEmpty { null })
                }
                result += Declared(name, struct, module, attributes, functions, fields.sortedBy { it.name })
            }
        }
        return result
    }

    /** The struct's body after the `{` at `from`, its nested scopes emptied to `{}`. */
    private fun flat(text: String, from: Int, struct: String): String {
        val out = StringBuilder()
        var depth = 1
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                if (depth == 1) {
                    out.append('{')
                }
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) {
                    return out.toString()
                }
                if (depth == 1) {
                    out.append('}')
                }
            } else if (depth == 1) {
                out.append(c)
            }
            i++
        }
        throw IllegalStateException("No end of the struct $struct")
    }

    // ------------------------------------------------------------------------------------------------ libraries

    /** The libraries' modules, numbered from 0 in the order their indexes give (then by name). */
    private fun libraryModules(): List<Module> {
        val found = linkedMapOf<String, Map<String, Any?>>()
        for (dir in includes.filter { it.isDirectory }) {
            val file = File(dir, INDEX)
            if (!file.isFile) {
                continue
            }
            for (line in file.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
                @Suppress("UNCHECKED_CAST")
                val entry = JsonSlurper().parseText(line) as Map<String, Any?>
                found.putIfAbsent(entry["name"] as String, entry)
            }
        }
        return found.values.sortedWith(compareBy({ (it["opcode"] as Number).toInt() }, { it["name"] as String }))
            .mapIndexed { i, e ->
                @Suppress("UNCHECKED_CAST")
                Module(e["name"] as String, e["struct"] as String, e["module"] as String, e["stages"] as List<String>,
                    e["capability"] as String?, e["twin"] as String?, (e["fields"] as List<Map<String, Any?>>).map {
                        Field(it["name"] as String, it["type"] as String, it["initializer"] as String?)
                    }, e["javaClass"] as String, i)
            }
    }

    /** The first `particle-modules` templates of the sources and the includes. */
    private fun templates(): File = (listOf(root) + includes).map { File(it, "particle-modules") }
        .firstOrNull { File(it, "modules.properties").isFile }
        ?: throw IllegalStateException("Particle modules need the templates of a particle-modules directory " +
            "(modules.properties, dispatch.slang.template, Modules.java.template), in the sources or a library " +
            "such as engine-render")

    // ------------------------------------------------------------------------------------------------ the output

    private fun opcode(m: Module, properties: Properties): String =
        properties.getProperty("opcode", "OP_{NAME}").replace("{NAME}", constant(m.name))

    private fun dispatch(template: String, all: List<Module>, stages: List<String>, properties: Properties): String {
        var text = template
            .replace("{count}", all.size.toString())
            .replace("{opcodes}", all.joinToString("") {
                "static const uint ${opcode(it, properties)} = ${it.opcode}; // ${it.name}\n"
            })
            .replace("{imports}", imports(all, properties))
        for (stage in stages) {
            text = text.replace("{cases:$stage}", all.filter { stage in it.stages }.joinToString("") {
                gated(it, properties, case(it, stage, properties))
            })
        }
        return text
    }

    /** Each module's Slang module imported once: under its capability's define if every module of it needs it. */
    private fun imports(all: List<Module>, properties: Properties): String = all.groupBy { it.module }.entries
        .joinToString("") { (module, modules) ->
            val capabilities = modules.map { it.capability }.distinct()
            val line = "import $module;\n"
            capabilities.singleOrNull()?.let { define(it, properties) }?.let { "#ifdef $it\n$line#endif\n" } ?: line
        }

    private fun define(capability: String?, properties: Properties): String? =
        capability?.let { properties.getProperty("capability.$it") }

    private fun gated(m: Module, properties: Properties, text: String): String =
        define(m.capability, properties)?.let { "#ifdef $it\n$text#endif\n" } ?: text

    /** The case of module [m] in stage [stage]'s dispatch: its fields read into its struct, then the stage's call. */
    private fun case(m: Module, stage: String, properties: Properties): String = buildString {
        append("    case ${opcode(m, properties)}:\n    {\n")
        append("        ${m.struct} it;\n")
        var word = 0
        m.fields.forEachIndexed { index, field ->
            val read = properties.getProperty("field.${field.type}.read")
                ?: throw IllegalStateException("modules.properties has no field.${field.type}.read")
            append("        it.${field.name} = ${read.replace("{word}", word.toString())
                .replace("{index}", index.toString())};\n")
            word += properties.getProperty("field.${field.type}.words").toInt()
        }
        append("        ${properties.getProperty("call.$stage") ?: "it.$stage();"}\n")
        append("        break;\n    }\n")
    }

    private fun javaType(m: Module, stages: List<String>, properties: Properties): String {
        fun property(key: String) = properties.getProperty(key)
            ?: throw IllegalStateException("modules.properties needs $key")
        val targets = when {
            m.twin == null -> property("java.gpu")
            m.twin.isEmpty() -> properties.getProperty("java.cpu", "")
            else -> property("java.twin").replace("{twin}", m.twin)
        }
        val fields = m.fields.joinToString("") { field ->
            val value = field.initializer ?: properties.getProperty("field.${field.type}.default", "0")
            property("java.field").replace("{field}", field.name).replace("{fallback}",
                property("field.${field.type}.java").replace("{value}", javaFloats(m, field, value)))
        }
        return property("java.type").replace("{name}", m.name)
            .replace("{stage}", property("stage.${m.stages.first { it in stages }}"))
            .replace("{targets}", targets)
            .replace("{capability}", m.capability?.let { property("java.capability").replace("{capability}", it) }
                ?: "")
            .replace("{fields}", fields)
    }

    /** A field's initializer (a number, or a vector of them: `float4(1, 1, 1, 1)`) as Java float literals. */
    private fun javaFloats(m: Module, field: Field, initializer: String): String {
        val inner = VECTOR.matchEntire(initializer.trim())?.groupValues?.get(1) ?: initializer
        return inner.split(',').joinToString(", ") { part ->
            val literal = part.trim()
            val negative = literal.startsWith("-")
            val bare = literal.removePrefix("-").trimEnd('u', 'U', 'l', 'L')
            val number = when {
                bare.startsWith("0x", true) -> bare.substring(2).toLong(16).toString()
                NUMBER.matches(bare) -> bare.trimEnd('f', 'F')
                else -> throw IllegalStateException(
                    "${m.struct}.${field.name} = $initializer: a module field's fallback is made of number literals")
            }
            (if (negative) "-" else "") + number + "f"
        }
    }

    companion object {
        /** A project's particle modules, beside its Slang modules in the jar: a JSON object per line. */
        const val INDEX = "particle-modules.index"

        /** {@code force.speedLimit} as {@code FORCE_SPEED_LIMIT}. */
        fun constant(name: String): String =
            name.replace('.', '_').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

        private val STRUCT = Regex("((?:\\[[^\\]]*]\\s*)+)(?:public\\s+)?struct\\s+(\\w+)[^{;]*\\{")
        private val MODULE = Regex("\\[ParticleModule\\(\"([\\w.]+)\"\\)]")
        private val CAPABILITY = Regex("\\[ModuleCapability\\(\"(\\w+)\"\\)]")
        private val TWIN = Regex("\\[(ModuleTwin\\(\"([\\w.$]+)\"\\)|BuiltInTwin)]")
        private val ATTRIBUTE = Regex("\\[[^\\]]*]")
        /** A function of the struct's (its body emptied): name, generic parameters, parameters, body. */
        private val FUNCTION = Regex("(\\w+)\\s*(?:<[^>]*>)?\\s*\\([^(){};]*\\)\\s*\\{}")
        /** The same with what precedes it to the declaration before: removed before reading fields. */
        private val DEFINITION = Regex("[^;{}]*\\([^;{}]*\\)\\s*\\{}")
        private val FIELD = Regex("(\\w+)\\s+(\\w+)\\s*(?:=\\s*(.+))?", RegexOption.DOT_MATCHES_ALL)
        private val VECTOR = Regex("\\w+\\((.*)\\)", RegexOption.DOT_MATCHES_ALL)
        private val NUMBER = Regex("(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[fF]?")
    }
}
