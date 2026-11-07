package com.truesim.build.slang

import groovy.json.JsonSlurper
import java.io.File
import java.util.Properties

/**
 * What the generators merging marked structs across a project and its libraries share ([ParticleModules],
 * [ShadingHooks]): finding the structs a marker attribute names in the Slang sources, with the functions each defines,
 * reading the libraries' indexes (a JSON object per line, shipped beside their modules), and finding the templates a
 * library ships for them. Each generator knows its own attribute, index and templates; this knows no engine.
 */
internal object MergedStructs {

    /**
     * A marked struct of the sources: [name] the marker's argument, [struct] its Slang name, [module] the module
     * declaring it (its path with dots), [attributes] its attribute text, [functions] those of its own scope, [body]
     * its body with its nested scopes emptied to `{}`.
     */
    class Declared(
        val name: String, val struct: String, val module: String, val attributes: String,
        val functions: Set<String>, val body: String,
    )

    /**
     * The structs of the `.slang` files under [root] whose attributes [marker] matches (its first group the name), in
     * the order of their files and places.
     */
    fun scan(root: File, marker: Regex): List<Declared> {
        if (!root.isDirectory) {
            return emptyList()
        }
        val result = mutableListOf<Declared>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "slang" }.sortedBy { it.path }) {
            val text = GenerateSlangRecords.withoutComments(file.readText())
            val module = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".slang").replace('/', '.')
            for (match in STRUCT.findAll(text)) {
                val attributes = match.groupValues[1]
                val name = marker.find(attributes)?.groupValues?.get(1) ?: continue
                val struct = match.groupValues[2]
                val flat = flat(text, match.range.last, struct)
                val functions = FUNCTION.findAll(flat).map { it.groupValues[1] }.toSet()
                result += Declared(name, struct, module, attributes, functions, flat)
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

    /** The entries of the [index] files of [includes], in their order: the first of each [key] kept. */
    fun libraryEntries(includes: Collection<File>, index: String, key: (Map<String, Any?>) -> String):
        List<Map<String, Any?>> {
        val found = linkedMapOf<String, Map<String, Any?>>()
        for (dir in includes.filter { it.isDirectory }) {
            val file = File(dir, index)
            if (!file.isFile) {
                continue
            }
            for (line in file.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
                @Suppress("UNCHECKED_CAST")
                val entry = JsonSlurper().parseText(line) as Map<String, Any?>
                found.putIfAbsent(key(entry), entry)
            }
        }
        return found.values.toList()
    }

    /** The first directory [directory] of [root] and [includes] holding [marker]; null if none does. */
    fun templates(root: File, includes: Collection<File>, directory: String, marker: String): File? =
        (listOf(root) + includes).map { File(it, directory) }.firstOrNull { File(it, marker).isFile }

    /** The properties of [file]; [Properties.getProperty] of a missing key fails naming the file. */
    fun properties(file: File): (String) -> String {
        val properties = Properties().apply { file.reader().use { load(it) } }
        return { key -> properties.getProperty(key) ?: throw IllegalStateException("$file needs $key") }
    }

    /** {@code force.speedLimit} as {@code FORCE_SPEED_LIMIT}. */
    fun constant(name: String): String =
        name.replace('.', '_').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    private val STRUCT = Regex("((?:\\[[^\\]]*]\\s*)+)(?:public\\s+)?struct\\s+(\\w+)[^{;]*\\{")

    /** A function of the struct's (its body emptied): name, generic parameters, parameters, body. */
    private val FUNCTION = Regex("(\\w+)\\s*(?:<[^>]*>)?\\s*\\([^(){};]*\\)\\s*\\{}")
}
