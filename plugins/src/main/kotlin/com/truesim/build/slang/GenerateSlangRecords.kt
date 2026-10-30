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
 * - a `[MaterialType("name")]` struct (an `IMaterial` with `typealias Parameters = ...`) gets its visibility-buffer
 *   entry points, plain and with inline ray shadows, as Slang sources under [slangOutput], and a Java class of the same
 *   name holding its `MaterialType`.
 *
 * - a struct of a `[[vk::push_constant]] ConstantBuffer<S>` block becomes a Java class `S` in [pushPackage] (or
 *   [javaPackage]): the bytes the block takes and each field's byte offset (nested structs' fields by path), from
 *   slangc's reflection of the block.
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
        (pushPackage.orNull ?: javaPackage.orNull)?.let { pkg ->
            writeLayouts(root, declared.pushes, pkg, javaOut, false)
            writeLayouts(root, declared.hosts, pkg, javaOut, true)
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
        for (type in declared.types) {
            writeEntryPoints(slangOut, type)
            File(javaOut, pkg.replace('.', '/') + "/${type.struct}.java").apply { parentFile.mkdirs() }
                .writeText(typeSource(pkg, type, javaClass(type.parameters), reflected[type.parameters]
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

    class Offset(val name: String, val offset: Int)

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
                    .writeText(layoutSource(pkg, block, name, host, bytes, offsets))
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
            into += Offset(name, offset)
            end = maxOf(end, offset + (binding["size"] as Number).toInt())
            val t = field["type"] as Map<String, Any?>
            if (t["kind"] == "struct") {
                offsets("$name.", t, into, offset)
            }
        }
        return end
    }

    private fun layoutSource(pkg: String, block: PushBlock, name: String, host: Boolean, bytes: Int,
        offsets: List<Offset>): String = buildString {
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
        append(" */\n")
        append("public final class $name {\n\n")
        append("    /** Bytes the ${if (host) "struct" else "block"} takes. */\n")
        append("    public static final int BYTES = $bytes;\n")
        for (offset in offsets) {
            append("    /** {@code ${offset.name}}. */\n")
            append("    public static final int ${constant(offset.name)} = ${offset.offset};\n")
        }
        append("\n    private $name() {\n    }\n}\n")
    }

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

    private fun writeEntryPoints(out: File, type: MaterialTypeDecl) {
        for (traced in listOf(false, true)) {
            val suffix = if (traced) "Traced" else ""
            File(out, "material-types/${type.name}$suffix.slang").apply { parentFile.mkdirs() }.writeText(buildString {
                append("// Generated from ${type.module}'s ${type.struct}: its shading of the visibility buffer")
                append(if (traced) ", with inline ray shadows.\n" else ".\n")
                append("import visibility.resolve;\n")
                if (traced) {
                    append("import raytracing;\n")
                }
                append("import ${type.module};\n\n[shader(\"compute\")]\n[numthreads(8, 8, 1)]\n")
                append("void ${type.name}${suffix}Main(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)\n")
                append("{\n    shadeTiles<${type.struct}>(${if (traced) "QueryRays" else "NoRays"}(), group, thread);\n}\n")
            })
        }
    }

    private fun typeSource(pkg: String, type: MaterialTypeDecl, parameters: String, struct: Struct): String {
        val features = (struct.attributes["MaterialParameters"]?.firstOrNull() as Number?)?.toInt() ?: 0
        val simple = parameters.substringAfterLast('.')
        return """
            |package $pkg;
            |
            |import com.truesim.engine.render.MaterialType;
            |${if (parameters.substringBeforeLast('.') != pkg) "import $parameters;\n" else ""}
            |/** Generated from ${type.module}'s {@code ${type.struct}}: the material type {@code ${type.name}}. */
            |public final class ${type.struct} {
            |
            |    /** The type, to register with {@code Materials}: its shaders, by the generated entry points. */
            |    public static final MaterialType TYPE = new MaterialType("${type.name}", $simple.class,
            |        $simple::fromValues, $features, "shaders/material-types/${type.name}.spv", "${type.name}Main",
            |        "shaders/material-types/${type.name}Traced.spv", "${type.name}TracedMain");
            |
            |    private ${type.struct}() {
            |    }
            |}
            |""".trimMargin()
    }

    companion object {
        /** A library's records, beside its modules: struct, Java class, kind, bytes, layout signature per line. */
        const val INDEX = "slang-records.index"

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
    }
}
