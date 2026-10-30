package com.truesim.build.slang

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import javax.inject.Inject

/**
 * Generates Java from the material records of the Slang sources, so the Slang struct is their one source of truth (the
 * contract of engine-render's `materials` module):
 *
 * - a `[MaterialParameters(features)]` or `[MaterialFeature(feature)]` struct becomes a Java record of the same name in
 *   [javaPackage]: its fields with the struct's initializers as `DEFAULTS`, `[Range]` checks, withers, the byte layout
 *   slangc itself gives the struct (from its reflection: pointer reads use scalar layout), a writer, a constant per
 *   field for writing one value in place, and `fromValues` for data by field name;
 * - a `[MaterialType("name")]` struct (an `IMaterial` with `typealias Parameters = ...`) gets its entry points as Slang
 *   sources under [slangOutput] and a Java class of the same name holding its `TYPE`, both from the templates in a
 *   `material-types` directory of the sources or a library (engine-render ships them beside its `materials` module):
 *   `type.properties` (`class`, the Java class of `TYPE`, and `factory`, the expression making it, with `{name}`,
 *   `{parameters}` and `{features}` filled in) and an `entry<Suffix>.slang.template` per entry-point file
 *   `<name><Suffix>.slang` (with `{name}`, `{struct}` and `{module}` filled in);
 *
 * - a struct of a `[[vk::push_constant]] ConstantBuffer<S>` block becomes a Java class `S` in [pushPackage] (or
 *   [javaPackage]): the bytes the block takes and each field's byte offset (nested structs' fields by path), from
 *   slangc's reflection of the block; a `[HostLayout]` struct, which the host writes into buffers, becomes `SLayout`;
 * - the scalar `static const`s of each module of [constantModules] become a Java class of constants, so the host uses
 *   the shader's values instead of copies.
 *
 * Structs of libraries (other projects' modules, on [includes]) are referred to through their `slang-records.index`,
 * which this task writes for its own under [indexOutput] and which ships in the jar beside the modules; a layout that
 * no longer matches its index fails the build.
 */
@CacheableTask
abstract class GenerateSlangRecords @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    /** Directories of modules the sources import, and of library indexes. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includes: ConfigurableFileCollection

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val checksum: Property<String>

    @get:Input
    abstract val arguments: ListProperty<String>

    /** The package of the generated Java; needed once there are records. */
    @get:Input
    @get:Optional
    abstract val javaPackage: Property<String>

    /** The package of the push-constant and `[HostLayout]` layouts; [javaPackage] if unset, none without either. */
    @get:Input
    @get:Optional
    abstract val pushPackage: Property<String>

    /**
     * The class push-constant layouts extend, e.g. `com.truesim.engine.vulkan.PushConstants`: with it, each is a writer
     * too, made once, with a typed setter per field that puts the value at its offset. Without it, constants only.
     */
    @get:Input
    @get:Optional
    abstract val pushBase: Property<String>

    /**
     * Modules (paths under [sources] without `.slang`, e.g. `meshlets/common`) whose scalar `static const`s (integers,
     * floats and booleans whose values are literals or expressions of the module's earlier constants) become the Java
     * class `<Module>Constants` in [pushPackage] (or [javaPackage]).
     */
    @get:Input
    abstract val constantModules: ListProperty<String>

    /** Whether to mark the generated classes `@AllowAllocation` (with truesim.allocation-free): records allocate. */
    @get:Input
    abstract val allowAllocation: Property<Boolean>

    @get:OutputDirectory
    abstract val javaOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val slangOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val indexOutput: DirectoryProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val javaOut = javaOutput.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        val slangOut = slangOutput.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        val indexOut = indexOutput.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        val index = File(indexOut, INDEX)
        val root = sources.get().asFile
        val declared = if (root.isDirectory) scan(root) else Declared(emptyList(), emptyList(), emptyList(), emptyList())
        val layoutPackage = pushPackage.orNull ?: javaPackage.orNull
        layoutPackage?.let { pkg ->
            writeLayouts(root, declared.pushes, pkg, javaOut, false)
            writeLayouts(root, declared.hosts, pkg, javaOut, true)
        }
        if (constantModules.get().isNotEmpty()) {
            val pkg = layoutPackage
                ?: throw IllegalStateException("slang.constantModules need slang.pushPackage or slang.javaPackage")
            constantModules.get().forEach { writeConstants(root, it, pkg, javaOut) }
        }
        if (declared.records.isEmpty() && declared.types.isEmpty()) {
            index.writeText("")
            return
        }
        val pkg = javaPackage.orNull
            ?: throw IllegalStateException("Slang material records need slang.javaPackage, the generated Java's package")
        val library = readIndexes()
        val reflected = reflect(root, declared)
        val local = declared.records.associate { it.name to "$pkg.${it.name}" }
        fun javaClass(struct: String): String = local[struct] ?: library[struct]?.javaClass
            ?: throw IllegalStateException("$struct is no material record of these sources or their libraries")
        for ((name, struct) in reflected) {
            library[name]?.let { entry ->
                check(local.containsKey(name) || entry.signature == struct.signature()) {
                    "$name's layout changed since its library was built: rebuild ${entry.javaClass}'s project"
                }
            }
        }
        val records = mutableListOf<String>()
        for (record in declared.records) {
            val struct = reflected.getValue(record.name)
            val kinds = struct.fields.associate { field ->
                field.name to (field.nested?.let { nested ->
                    declared.records.firstOrNull { it.name == nested.name }?.kind ?: library[nested.name]?.kind
                } ?: "")
            }
            val java = JavaRecord(pkg, record, struct, ::javaClass, kinds, allowAllocation.get()).source()
            File(javaOut, pkg.replace('.', '/') + "/${record.name}.java").apply { parentFile.mkdirs() }.writeText(java)
            records += listOf(record.name, "$pkg.${record.name}", record.kind, struct.bytes().toString(),
                struct.signature()).joinToString(" ")
        }
        val templates = if (declared.types.isEmpty()) null else materialTemplates(root)
        for (type in declared.types) {
            writeEntryPoints(slangOut, type, templates!!)
            File(javaOut, pkg.replace('.', '/') + "/${type.struct}.java").apply { parentFile.mkdirs() }
                .writeText(typeSource(pkg, type, templates!!, javaClass(type.parameters), reflected[type.parameters]
                    ?: throw IllegalStateException("${type.struct}'s parameters ${type.parameters} are no record")))
        }
        index.writeText(records.joinToString("\n", postfix = "\n"))
    }

    // ------------------------------------------------------------------------------------------------ the sources

    class Record(val name: String, val module: String, val kind: String, val fields: Map<String, Declaration>)

    class Declaration(val type: String, val name: String, val default: String?)

    class MaterialTypeDecl(val name: String, val struct: String, val module: String, val parameters: String)

    class PushBlock(val struct: String, val module: String)

    class Declared(
        val records: List<Record>, val types: List<MaterialTypeDecl>, val pushes: List<PushBlock>,
        val hosts: List<PushBlock> = emptyList(),
    )

    private fun scan(root: File): Declared {
        val records = mutableListOf<Record>()
        val types = mutableListOf<MaterialTypeDecl>()
        val pushes = mutableListOf<PushBlock>()
        val hosts = mutableListOf<PushBlock>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "slang" }.sortedBy { it.path }) {
            val text = withoutComments(file.readText())
            val module = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".slang").replace('/', '.')
            for (match in RECORD.findAll(text)) {
                val kind = if (match.groupValues[1] == "MaterialParameters") "parameters" else "feature"
                val name = match.groupValues[2]
                records += Record(name, module, kind, declarations(name, body(text, match.range.last)))
            }
            for (match in TYPE.findAll(text)) {
                val body = body(text, match.range.last)
                val parameters = PARAMETERS.find(body)?.groupValues?.get(1)
                    ?: throw IllegalStateException("${match.groupValues[2]} needs `typealias Parameters = ...;`")
                types += MaterialTypeDecl(match.groupValues[1], match.groupValues[2], module, parameters)
            }
            for (match in PUSH.findAll(text)) {
                pushes += PushBlock(match.groupValues[1], module)
            }
            for (match in HOST.findAll(text)) {
                hosts += PushBlock(match.groupValues[1], module)
            }
        }
        for ((struct, blocks) in pushes.groupBy { it.struct }) {
            val modules = blocks.map { it.module }.distinct()
            check(modules.size == 1) {
                "Push-constant structs called $struct in $modules: rename all but one, as each becomes a Java class"
            }
        }
        val names = records.map { it.name } + types.map { it.struct }
        names.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let {
            throw IllegalStateException("Two material records or types are called $it")
        }
        return Declared(records, types, pushes.distinctBy { it.struct }, hosts)
    }

    /** The struct body after the `{` that ends at `from`, to its `}`: material records are flat. */
    private fun body(text: String, from: Int): String {
        val end = text.indexOf('}', from)
        check(end > from) { "No end of the struct at ${text.substring(from, minOf(text.length, from + 40))}" }
        return text.substring(from + 1, end)
    }

    private fun declarations(struct: String, body: String): Map<String, Declaration> {
        val result = linkedMapOf<String, Declaration>()
        for (piece in body.split(';')) {
            val plain = piece.replace(ATTRIBUTE, " ").replace(Regex("\\b(public|internal)\\b"), " ").trim()
            if (plain.isEmpty() || plain.startsWith("typealias") || plain.startsWith("static")) {
                continue
            }
            val match = FIELD.matchEntire(plain)
                ?: throw IllegalStateException("$struct: cannot read the field `$plain` (material records hold fields)")
            result[match.groupValues[2]] =
                Declaration(match.groupValues[1], match.groupValues[2], match.groupValues[4].trim().ifEmpty { null })
        }
        return result
    }

    private fun withoutComments(text: String) =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")

    // ------------------------------------------------------------------------------------------------ the layouts

    class Field(
        val name: String, val offset: Int, val size: Int, val scalar: String?, val count: Int,
        val nested: Struct?, val attributes: Map<String, List<Any?>>,
    )

    class Struct(val name: String, val attributes: Map<String, List<Any?>>, val fields: List<Field>) {
        fun bytes() = ((fields.maxOfOrNull { it.offset + it.size } ?: 0) + 3) / 4 * 4

        fun signature(): String {
            val crc = CRC32()
            crc.update(describe().toByteArray())
            return java.lang.Long.toHexString(crc.value)
        }

        private fun describe(): String = fields.joinToString(";") {
            "${it.name}:${it.offset}:${it.size}:${it.scalar}:${it.count}:${it.nested?.describe()}"
        }
    }

    /** Compiles a probe reading every record through a pointer and returns the records as slangc lays them out. */
    private fun reflect(root: File, declared: Declared): Map<String, Struct> {
        val slangc = SlangToolchain.install(cacheDir.get().asFile, version.get(), checksum.get())
        val temp = Files.createTempDirectory("slang-records").toFile()
        try {
            val structs = declared.records.map { it.name } + declared.types.map { it.parameters }.distinct()
                .filter { p -> declared.records.none { it.name == p } }
            val modules = (declared.records.map { it.module } + declared.types.map { it.module }).distinct()
            val probe = File(temp, "probe.slang")
            probe.writeText(buildString {
                modules.forEach { append("import $it;\n") }
                append("struct Probe\n{\n")
                structs.forEachIndexed { i, s -> append("    $s* p$i;\n") }
                append("};\n[[vk::push_constant]] ConstantBuffer<Probe> probe;\nRWStructuredBuffer<uint> probeOut;\n")
                append("[shader(\"compute\")]\n[numthreads(1, 1, 1)]\nvoid probeMain()\n{\n    probeOut[0] = 0")
                structs.indices.forEach { append(" + uint(probe.p$it != nullptr)") }
                append(";\n}\n")
            })
            val reflection = compileProbe(slangc, root, probe, "the material records")
            val found = mutableMapOf<String, Struct>()
            @Suppress("UNCHECKED_CAST")
            for (parameter in reflection["parameters"] as List<Map<String, Any?>>) {
                if (parameter["name"] != "probe") {
                    continue
                }
                val element = (parameter["type"] as Map<String, Any?>)["elementType"] as Map<String, Any?>
                for (field in element["fields"] as List<Map<String, Any?>>) {
                    val struct = struct((field["type"] as Map<String, Any?>)["valueType"] as Map<String, Any?>)
                    collect(struct, found)
                }
            }
            return found
        } finally {
            temp.deleteRecursively()
        }
    }

    /** Compiles {@code probe} (with the sources and libraries on the include path) and returns slangc's reflection. */
    private fun compileProbe(slangc: File, root: File, probe: File, what: String): Map<String, Any?> {
        val dir = probe.parentFile
        val json = File(dir, probe.nameWithoutExtension + ".json")
        val includeDirs = listOf(root) + includes.files.filter { it.isDirectory }
        val required = includeDirs.map { File(it, CompileSlang.ARGUMENTS) }.filter { it.isFile }
            .flatMap { it.readText().split(Regex("\\s+")).filter(String::isNotEmpty) }
        val log = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(
                listOf(slangc.absolutePath, probe.absolutePath, "-target", "spirv", "-profile", "spirv_1_6",
                    "-o", File(dir, probe.nameWithoutExtension + ".spv").absolutePath,
                    "-reflection-json", json.absolutePath) +
                    includeDirs.flatMap { listOf("-I", it.absolutePath) } + required + arguments.get(),
            )
            standardOutput = log
            errorOutput = log
            isIgnoreExitValue = true
        }
        check(result.exitValue == 0) { "Slang could not compile $what:\n$log" }
        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parse(json) as Map<String, Any?>
    }

    // ------------------------------------------------------------------------------------------------ push constants

    class Offset(val name: String, val offset: Int, val size: Int, val type: Map<String, Any?>)

    /**
     * Writes a Java class per struct of {@code blocks}: the bytes it takes and each field's byte offset (nested structs'
     * fields by path), as slangc lays it out, so the host writes each value where the shader reads it. Push-constant
     * structs are laid out as their block ({@code S}); with {@code host}, `[HostLayout]` structs as shaders read them
     * through pointers from buffers ({@code SLayout}).
     */
    private fun writeLayouts(root: File, blocks: List<PushBlock>, pkg: String, javaOut: File, host: Boolean) {
        if (blocks.isEmpty()) {
            return
        }
        val slangc = SlangToolchain.install(cacheDir.get().asFile, version.get(), checksum.get())
        val temp = Files.createTempDirectory("slang-layouts").toFile()
        try {
            val probe = File(temp, "layouts.slang")
            probe.writeText(buildString {
                blocks.map { it.module }.distinct().forEach { append("import $it;\n") }
                append("struct LayoutProbe\n{\n")
                blocks.forEachIndexed { i, b -> append("    ${b.struct}${if (host) "*" else ""} s$i;\n") }
                append("};\n[[vk::push_constant]] ConstantBuffer<LayoutProbe> layoutProbe;\n")
                append("RWStructuredBuffer<uint> probeOut;\n")
                append("[shader(\"compute\")]\n[numthreads(1, 1, 1)]\nvoid probeMain()\n{\n")
                append("    probeOut[0] = 0;\n}\n")
            })
            val reflection = compileProbe(slangc, root, probe, if (host) "the host layouts" else "the push constants")
            @Suppress("UNCHECKED_CAST")
            val parameter = (reflection["parameters"] as List<Map<String, Any?>>).first { it["name"] == "layoutProbe" }
            @Suppress("UNCHECKED_CAST")
            val element = (parameter["type"] as Map<String, Any?>)["elementType"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val fields = element["fields"] as List<Map<String, Any?>>
            blocks.forEachIndexed { i, block ->
                val field = fields[i]
                @Suppress("UNCHECKED_CAST")
                val type = field["type"] as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val struct = if (host) type["valueType"] as Map<String, Any?> else type
                val offsets = mutableListOf<Offset>()
                val end = offsets("", struct, offsets)
                @Suppress("UNCHECKED_CAST")
                val bytes = if (host) (end + 3) / 4 * 4
                    else ((field["binding"] as Map<String, Any?>)["size"] as Number).toInt()
                val name = block.struct + if (host) "Layout" else ""
                File(javaOut, pkg.replace('.', '/') + "/$name.java").apply { parentFile.mkdirs() }
                    .writeText(layoutSource(pkg, block, name, host, bytes, offsets, if (host) null else pushBase.orNull))
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    /**
     * The byte offsets of {@code type}'s fields, relative to its start (a nested struct's own fields by path); returns
     * where its last field ends.
     */
    @Suppress("UNCHECKED_CAST")
    private fun offsets(prefix: String, type: Map<String, Any?>, into: MutableList<Offset>, base: Int = 0): Int {
        var end = base
        for (field in type["fields"] as List<Map<String, Any?>>) {
            val name = prefix + field["name"] as String
            val binding = field["binding"] as Map<String, Any?>
            val offset = base + (binding["offset"] as Number).toInt()
            into += Offset(name, offset, (binding["size"] as Number).toInt(), field["type"] as Map<String, Any?>)
            end = maxOf(end, offset + (binding["size"] as Number).toInt())
            val t = field["type"] as Map<String, Any?>
            if (t["kind"] == "struct") {
                offsets("$name.", t, into, offset)
            }
        }
        return end
    }

    private fun layoutSource(pkg: String, block: PushBlock, name: String, host: Boolean, bytes: Int,
        offsets: List<Offset>, base: String?): String = buildString {
        append("package $pkg;\n\n")
        append("/**\n")
        if (host) {
            append(" * Generated from ${block.module}'s host layout {@code ${block.struct}}: the bytes it takes and each\n")
            append(" * field's byte offset, as shaders read it through pointers, so the host writes each value where they\n")
            append(" * read it.\n")
        } else {
            append(" * Generated from ${block.module}'s push-constant struct {@code ${block.struct}}: the bytes its block\n")
            append(" * takes and each field's byte offset, as slangc lays it out, so the host writes each value where the\n")
            append(" * shader reads it.\n")
        }
        if (base != null) {
            append(" *\n")
            append(" * <p>A writer too: made once (a pass keeps one), each field set by name at its offset, in any order;\n")
            append(" * a field not set again keeps its value.\n")
        }
        append(" */\n")
        append("public final class $name${if (base != null) " extends $base" else ""} {\n\n")
        append("    /** Bytes the ${if (host) "struct" else "block"} takes. */\n")
        append("    public static final int BYTES = $bytes;\n")
        for (offset in offsets) {
            append("    /** {@code ${offset.name}}. */\n")
            append("    public static final int ${constant(offset.name)} = ${offset.offset};\n")
        }
        if (base == null) {
            append("\n    private $name() {\n    }\n}\n")
            return@buildString
        }
        append("\n    public $name() {\n    }\n\n")
        append("    /** {@value #BYTES}: the whole block. */\n")
        append("    @Override\n    public int size() {\n        return BYTES;\n    }\n")
        for (offset in offsets) {
            setter(name, offset)?.let { append("\n").append(it) }
        }
        append("}\n")
    }

    /**
     * The setter of [offset]'s field in writer [name]: scalars, vectors, pointers, and matrices stored without padding;
     * null for the rest (arrays, and nested structs themselves, whose fields get setters of their own).
     */
    @Suppress("UNCHECKED_CAST")
    private fun setter(name: String, offset: Offset): String? {
        val type = offset.type
        val at = constant(offset.name)
        val method = javaName(offset.name.split('.').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString(""))
        val body = StringBuilder()
        val params: String
        val doc: String
        when (type["kind"]) {
            "scalar" -> {
                val scalar = type["scalarType"] as String?
                val java = SCALARS[scalar] ?: return null
                params = "$java value"
                doc = SLANG_NAMES.getValue(scalar!!)
                body.append(put(java, at, "value"))
            }
            "pointer" -> {
                params = "long address"
                doc = "a pointer, by device address"
                body.append(put("long", at, "address"))
            }
            "vector" -> {
                val scalar = (type["elementType"] as Map<String, Any?>)["scalarType"] as String?
                val java = SCALARS[scalar] ?: return null
                val count = (type["elementCount"] as Number).toInt()
                val names = listOf("x", "y", "z", "w").take(count)
                params = names.joinToString(", ") { "$java $it" }
                doc = "${SLANG_NAMES.getValue(scalar!!)}$count"
                val width = if (java == "long") 8 else 4
                names.forEachIndexed { i, component ->
                    body.append(put(java, if (i == 0) at else "$at + ${i * width}", component))
                }
            }
            "matrix" -> {
                val scalar = (type["elementType"] as Map<String, Any?>)["scalarType"] as String?
                val count = (type["rowCount"] as Number).toInt() * (type["columnCount"] as Number).toInt()
                if (scalar != "float32" || offset.size != 4 * count) {
                    return null
                }
                params = "float[] values"
                doc = "$count floats, in the order the shader's matrix layout stores them"
                body.append("        if (values.length != $count) {\n")
                body.append("            throw new IllegalArgumentException(\"${offset.name} takes $count floats: \" + values.length);\n")
                body.append("        }\n")
                body.append("        for (int i = 0; i < $count; i++) {\n")
                body.append("            putFloat($at + 4L * i, values[i]);\n")
                body.append("        }\n")
            }
            else -> return null
        }
        return "    /** Sets {@code ${offset.name}} ($doc). */\n" +
            "    public $name $method($params) {\n$body        return this;\n    }\n"
    }

    /** The statement putting the Java [java] value [value] at [at]. */
    private fun put(java: String, at: String, value: String): String = when (java) {
        "int" -> "        putInt($at, $value);\n"
        "boolean" -> "        putInt($at, $value ? 1 : 0);\n"
        "float" -> "        putFloat($at, $value);\n"
        else -> "        putLong($at, $value);\n"
    }

    /** [name], or `name_` where it is a Java keyword. */
    private fun javaName(name: String): String = if (name in JAVA_KEYWORDS) "${name}_" else name

    /** {@code meshTable} as {@code MESH_TABLE}, {@code occlusion.pyramid} as {@code OCCLUSION_PYRAMID}. */
    private fun constant(name: String): String =
        name.replace('.', '_').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    private fun collect(struct: Struct, into: MutableMap<String, Struct>) {
        into[struct.name] = struct
        struct.fields.mapNotNull { it.nested }.forEach { collect(it, into) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun struct(type: Map<String, Any?>): Struct = Struct(
        type["name"] as String,
        attributes(type["userAttribs"]),
        (type["fields"] as List<Map<String, Any?>>).map { field ->
            val t = field["type"] as Map<String, Any?>
            val binding = field["binding"] as Map<String, Any?>
            val offset = (binding["offset"] as Number).toInt()
            val size = (binding["size"] as Number).toInt()
            when (t["kind"]) {
                "scalar" -> Field(field["name"] as String, offset, size, t["scalarType"] as String, 1, null,
                    attributes(field["userAttribs"]))
                "vector" -> Field(field["name"] as String, offset, size,
                    (t["elementType"] as Map<String, Any?>)["scalarType"] as String,
                    (t["elementCount"] as Number).toInt(), null, attributes(field["userAttribs"]))
                "struct" -> Field(field["name"] as String, offset, size, null, 1, struct(t),
                    attributes(field["userAttribs"]))
                else -> throw IllegalStateException(
                    "${type["name"]}.${field["name"]}: material records hold scalars, vectors and records")
            }
        },
    )

    @Suppress("UNCHECKED_CAST")
    private fun attributes(value: Any?): Map<String, List<Any?>> =
        (value as List<Map<String, Any?>>? ?: emptyList()).associate {
            it["name"] as String to (it["arguments"] as List<Any?>? ?: emptyList())
        }

    // ------------------------------------------------------------------------------------------------ libraries

    class IndexEntry(val javaClass: String, val kind: String, val bytes: Int, val signature: String)

    private fun readIndexes(): Map<String, IndexEntry> {
        val entries = mutableMapOf<String, IndexEntry>()
        for (dir in includes.files.filter { it.isDirectory }) {
            val file = File(dir, INDEX)
            if (!file.isFile) {
                continue
            }
            for (line in file.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
                val parts = line.split(' ')
                entries.putIfAbsent(parts[0], IndexEntry(parts[1], parts[2], parts[3].toInt(), parts[4]))
            }
        }
        return entries
    }

    // ------------------------------------------------------------------------------------------------ material types

    /** A material type's templates: the class and factory of its `TYPE`, and the entry-point sources by suffix. */
    class MaterialTemplates(val typeClass: String, val factory: String, val entryPoints: Map<String, String>)

    /** The first `material-types` templates of the sources and the includes. */
    private fun materialTemplates(root: File): MaterialTemplates {
        val dir = (listOf(root) + includes.files).map { File(it, "material-types") }
            .firstOrNull { File(it, "type.properties").isFile }
            ?: throw IllegalStateException("Material types need the templates of a material-types directory " +
                "(type.properties and entry*.slang.template), in the sources or a library such as engine-render")
        val properties = java.util.Properties().apply { File(dir, "type.properties").reader().use { load(it) } }
        val entryPoints = dir.listFiles { file -> file.name.startsWith("entry") && file.name.endsWith(".slang.template") }!!
            .sortedBy { it.name }
            .associate { it.name.removePrefix("entry").removeSuffix(".slang.template") to it.readText() }
        fun property(key: String) = properties.getProperty(key)
            ?: throw IllegalStateException("$dir/type.properties needs $key")
        return MaterialTemplates(property("class"), property("factory"), entryPoints)
    }

    private fun writeEntryPoints(out: File, type: MaterialTypeDecl, templates: MaterialTemplates) {
        for ((suffix, template) in templates.entryPoints) {
            File(out, "material-types/${type.name}$suffix.slang").apply { parentFile.mkdirs() }.writeText(
                "// Generated from ${type.module}'s ${type.struct}: its material type's entry points.\n" +
                    template.replace("{name}", type.name).replace("{struct}", type.struct)
                        .replace("{module}", type.module))
        }
    }

    private fun typeSource(pkg: String, type: MaterialTypeDecl, templates: MaterialTemplates, parameters: String,
        struct: Struct): String {
        val features = (struct.attributes["MaterialParameters"]?.firstOrNull() as Number?)?.toInt() ?: 0
        val simple = parameters.substringAfterLast('.')
        val typeClass = templates.typeClass
        val factory = templates.factory.replace("{name}", type.name).replace("{parameters}", simple)
            .replace("{features}", features.toString())
        return """
            |package $pkg;
            |
            |import $typeClass;
            |${if (parameters.substringBeforeLast('.') != pkg) "import $parameters;\n" else ""}
            |/** Generated from ${type.module}'s {@code ${type.struct}}: the material type {@code ${type.name}}. */
            |public final class ${type.struct} {
            |
            |    /** The type, by its generated entry points. */
            |    public static final ${typeClass.substringAfterLast('.')} TYPE = $factory;
            |
            |    private ${type.struct}() {
            |    }
            |}
            |""".trimMargin()
    }

    // ------------------------------------------------------------------------------------------------ constants

    /** The scalar `static const`s of {@code module} as the Java class `<Module>Constants`. */
    private fun writeConstants(root: File, module: String, pkg: String, javaOut: File) {
        val file = File(root, "$module.slang")
        check(file.isFile) { "slang.constantModules: no module $module under $root" }
        val known = mutableMapOf<String, String>()
        val name = module.substringAfterLast('/').split('_', '-', '.')
            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + "Constants"
        val java = buildString {
            append("package $pkg;\n\n")
            append("/** Generated from $module's {@code static const}s: the shaders' values, for the host. */\n")
            append("public final class $name {\n\n")
            for (match in CONSTANT.findAll(file.readText())) {
                val (slangType, constant, value, comment) = match.destructured
                val type = JAVA_TYPES[slangType] ?: continue
                val expression = javaExpression(value.trim(), slangType, type, known) ?: continue
                known[constant] = type
                append("    /** ${comment.trim().ifEmpty { "{@code $constant}." }.replace("*/", "* /")} */\n")
                append("    public static final $type $constant = $expression;\n")
            }
            append("\n    private $name() {\n    }\n}\n")
        }
        File(javaOut, pkg.replace('.', '/') + "/$name.java").apply { parentFile.mkdirs() }.writeText(java)
    }

    /**
     * {@code value}, a Slang constant's initializer, as a Java expression of {@code type}; null unless it is literals,
     * operators and the module's earlier constants ({@code known}). Unsigned shifts right stay unsigned.
     */
    private fun javaExpression(value: String, slangType: String, type: String, known: Map<String, String>): String? {
        val tokens = TOKEN.findAll(value).map { it.value.trim() }.toList()
        if (tokens.joinToString("") != value.replace(Regex("\\s+"), "")) {
            return null // something no token covers
        }
        val unsigned = slangType.startsWith("u")
        val parts = tokens.map { token ->
            when {
                token[0].isDigit() -> number(token, type) ?: return null
                token == "true" || token == "false" -> if (type == "boolean") token else return null
                token[0].isLetter() || token[0] == '_' -> if (known.containsKey(token)) token else return null
                token == ">>" && unsigned -> ">>>"
                else -> token
            }
        }
        return parts.joinToString(" ").replace("( ", "(").replace(" )", ")").replace("~ ", "~")
    }

    /** A Slang number literal as a Java literal of {@code type}, or null where it does not fit. */
    private fun number(literal: String, type: String): String? {
        val bare = literal.trimEnd('u', 'U', 'l', 'L')
        val float = bare.contains('.') || (!bare.startsWith("0x", true) && bare.contains('e', true)) ||
            (!bare.startsWith("0x", true) && bare.endsWith('f', true))
        return when (type) {
            "float" -> bare.trimEnd('f', 'F') + "f"
            "double" -> bare.trimEnd('f', 'F')
            "long" -> if (float) null else bare + "L"
            "int" -> when {
                float -> null
                bare.startsWith("0x", true) -> if (bare.length - 2 <= 8) bare else null
                else -> bare.toLongOrNull()?.let { n ->
                    when {
                        n <= Int.MAX_VALUE -> bare
                        n <= 0xFFFFFFFFL -> "(int) ${bare}L" // a uint past int's range: the same bits
                        else -> null
                    }
                }
            }
            else -> null
        }
    }

    companion object {
        /** A library's records, beside its modules: struct, Java class, kind, bytes, layout signature per line. */
        const val INDEX = "slang-records.index"

        /** The Java type a writer takes for each Slang scalar type it sets. */
        private val SCALARS = mapOf("uint32" to "int", "int32" to "int", "float32" to "float", "uint64" to "long",
            "int64" to "long", "bool" to "boolean")
        private val SLANG_NAMES = mapOf("uint32" to "uint", "int32" to "int", "float32" to "float", "uint64" to "uint64_t",
            "int64" to "int64_t", "bool" to "bool")
        private val JAVA_KEYWORDS = setOf("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
            "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "record", "yield")
        private val ATTRIBUTE = Regex("\\[[^\\]]*]")
        private val RECORD = Regex(
            "\\[(MaterialParameters|MaterialFeature)(?:\\([^)]*\\))?]\\s*(?:\\[[^\\]]*]\\s*)*(?:public\\s+)?" +
                "struct\\s+(\\w+)[^{;]*\\{")
        private val TYPE = Regex(
            "\\[MaterialType\\(\"(\\w+)\"\\)]\\s*(?:\\[[^\\]]*]\\s*)*(?:public\\s+)?struct\\s+(\\w+)[^{;]*\\{")
        private val HOST = Regex("\\[HostLayout]\\s*(?:\\[[^\\]]*]\\s*)*(?:public\\s+)?struct\\s+(\\w+)")
        private val PUSH =
            Regex("\\[\\[vk::push_constant]]\\s*(?:(?:public|internal|uniform)\\s+)*ConstantBuffer<(\\w+)>")
        private val PARAMETERS = Regex("typealias\\s+Parameters\\s*=\\s*(\\w+)\\s*;")
        private val FIELD = Regex("(\\w+)\\s+(\\w+)\\s*(=\\s*(.+))?", RegexOption.DOT_MATCHES_ALL)
        private val CONSTANT = Regex(
            "^[ \\t]*(?:(?:public|internal)\\s+)?static\\s+const\\s+(\\w+)\\s+(\\w+)\\s*=\\s*([^;]+);[ \\t]*(?://[ \\t]*(.*))?$",
            RegexOption.MULTILINE)
        private val TOKEN = Regex(
            "\\s*(0[xX][0-9a-fA-F]+[uUlL]*|\\d+\\.\\d*(?:[eE][-+]?\\d+)?[fF]?|\\d+[eE][-+]?\\d+[fF]?|\\d+[uUlLfF]*|" +
                "[A-Za-z_]\\w*|<<|>>|[-+*/%&|^~()])")
        private val JAVA_TYPES = mapOf("int" to "int", "uint" to "int", "int32_t" to "int", "uint32_t" to "int",
            "int64_t" to "long", "uint64_t" to "long", "float" to "float", "double" to "double", "bool" to "boolean")
    }
}
