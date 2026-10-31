package com.truesim.build.slang

/**
 * The Java record of one Slang material record ([GenerateSlangRecords]): fields as slangc lays them out, defaults
 * from the Slang initializers.
 */
internal class JavaRecord(
    private val pkg: String,
    private val record: GenerateSlangRecords.Record,
    private val struct: GenerateSlangRecords.Struct,
    private val javaClass: (String) -> String,
    /** Per field of a nested record, the record's kind ("parameters", "feature"), else "". */
    private val nestedKinds: Map<String, String>,
    private val allowAllocation: Boolean,
) {
    private val name = record.name
    private val imports = sortedSetOf<String>()

    private enum class Kind { F32, I32, VEC, COLOR, RECORD }

    private class Member(
        val field: GenerateSlangRecords.Field,
        val kind: Kind,
        val javaType: String,
        val default: String,
    ) {
        val name get() = field.name
    }

    private val members: List<Member> = struct.fields.map { member(it) }

    private fun member(field: GenerateSlangRecords.Field): Member {
        val declared = record.fields[field.name]?.default
        val nested = field.nested
        if (nested != null) {
            check(declared == null) { "$name.${field.name}: a nested record takes its own defaults" }
            val type = simple(javaClass(nested.name))
            return Member(field, Kind.RECORD, type, "$type.DEFAULTS")
        }
        val numbers = declared?.let { literal(it) }
        val float = field.scalar == "float32"
        check(float || field.scalar == "int32" || field.scalar == "uint32") {
            "$name.${field.name}: material records hold 32-bit floats and integers, not ${field.scalar}"
        }
        if (field.count == 1) {
            val value = numbers?.single() ?: 0.0
            return if (float) Member(field, Kind.F32, "float", floatLiteral(value))
            else Member(field, Kind.I32, "int", value.toLong().toInt().toString())
        }
        check(float && field.count in 2..4) { "$name.${field.name}: vectors are float2 to float4" }
        val values = when {
            numbers == null -> List(field.count) { 0.0 }
            numbers.size == 1 -> List(field.count) { numbers[0] }
            else -> numbers.also {
                check(it.size == field.count) { "$name.${field.name}: ${field.count} values, not ${it.size}" }
            }
        }
        if ("Color" in field.attributes) {
            check(field.count >= 3) { "$name.${field.name}: a [Color] is a float3 or float4" }
            imports += "com.truesim.common.math.color.Color"
            val rgba = if (field.count == 3) values + 1.0 else values
            return Member(field, Kind.COLOR, "Color", "new Color(${rgba.joinToString(", ") { plain(it) }})")
        }
        val type = "Vec${field.count}"
        imports += "com.truesim.common.math.$type"
        return Member(field, Kind.VEC, type, "new $type(${values.joinToString(", ") { plain(it) }})")
    }

    /** The numbers of a Slang literal: `0.7`, `-1`, `float3(0.9, 0.9, 0.95)`, `float3(0)`. */
    private fun literal(text: String): List<Double> {
        val call = Regex("(?:float|int|uint)[234]?\\s*\\((.*)\\)").matchEntire(text.trim())
        val parts = call?.groupValues?.get(1)?.split(',') ?: listOf(text)
        return parts.map {
            it.trim().removeSuffix("f").removeSuffix("u").toDoubleOrNull()
                ?: throw IllegalStateException("$name: defaults are literals, not `$text`")
        }
    }

    private fun floatLiteral(value: Double) = plain(value) + "f"

    private fun plain(value: Double): String {
        val text = value.toString()
        return if (text.endsWith(".0")) text.removeSuffix(".0") else text
    }

    private fun simple(className: String): String {
        if (className.substringBeforeLast('.') != pkg) {
            imports += className
        }
        return className.substringAfterLast('.')
    }

    private fun range(field: GenerateSlangRecords.Field): Pair<Double, Double>? =
        field.attributes["Range"]?.let { (it[0] as Number).toDouble() to (it[1] as Number).toDouble() }

    private fun texture(field: GenerateSlangRecords.Field): Boolean? =
        field.attributes["Texture"]?.let { (it.firstOrNull() as Number?)?.toInt() == 1 }

    fun source(): String {
        val parameters = record.kind == "parameters"
        val iface = if (parameters) "MaterialParameters" else "MaterialFeature"
        imports += "com.truesim.engine.render.$iface"
        imports += "com.truesim.engine.render.MaterialField"
        imports += "com.truesim.engine.render.MaterialValues"
        imports += "java.lang.foreign.MemorySegment"
        imports += "java.lang.foreign.ValueLayout"
        imports += "java.nio.ByteOrder"
        if (members.any { it.kind == Kind.RECORD || it.kind == Kind.VEC || it.kind == Kind.COLOR }) {
            imports += "java.util.Objects"
        }
        val body = StringBuilder()
        val components = members.joinToString(", ") { "${it.javaType} ${it.name}" }
        body.append("/**\n * Generated from ${record.module}'s {@code $name}")
        body.append(if (parameters) ": a material type's parameters" else ": a material feature's parameters")
        body.append(",\n * laid out as slangc lays the struct out.\n */\n")
        if (allowAllocation) {
            imports += "com.truesim.annotations.AllowAllocation"
            body.append("@AllowAllocation(\"material records are made when materials change\")\n")
        }
        body.append("public record $name($components) implements $iface {\n\n")
        body.append("    private static final ValueLayout.OfFloat F32 =\n")
        body.append("        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);\n")
        body.append("    private static final ValueLayout.OfInt I32 =\n")
        body.append("        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);\n\n")
        body.append("    /** Its bytes in a material's block. */\n")
        body.append("    public static final int BYTES = ${struct.bytes()};\n\n")
        body.append("    /** The Slang initializers. */\n")
        body.append("    public static final $name DEFAULTS = new $name(${members.joinToString(", ") { it.default }});\n\n")
        if (!parameters) {
            val feature = (struct.attributes["MaterialFeature"]?.firstOrNull() as Number?)?.toInt()
                ?: throw IllegalStateException("$name: [MaterialFeature(feature)] names its feature bit")
            body.append("    /** The feature bit it is the parameters of. */\n")
            body.append("    public static final int FEATURE = $feature;\n\n")
            body.append("    /** Its name in material data. */\n")
            body.append("    public static final String NAME = \"${featureName()}\";\n\n")
        }
        constants(body, struct, "", 0)
        body.append("\n    public $name {\n")
        for (m in members) {
            when (m.kind) {
                Kind.RECORD, Kind.VEC, Kind.COLOR ->
                    body.append("        Objects.requireNonNull(${m.name}, \"${m.name}\");\n")
                else -> {
                    range(m.field)?.let { (lo, hi) ->
                        body.append("        if (!(${m.name} >= ${plain(lo)} && ${m.name} <= ${plain(hi)})) {\n")
                        body.append("            throw new IllegalArgumentException(\"${m.name} is ${plain(lo)} to ")
                        body.append("${plain(hi)}: \" + ${m.name});\n        }\n")
                    }
                    if (texture(m.field) != null) {
                        body.append("        if (${m.name} < -1) {\n            throw new IllegalArgumentException(")
                        body.append("\"${m.name} is a texture slot, or -1 for none: \" + ${m.name});\n        }\n")
                    }
                }
            }
        }
        body.append("    }\n")
        for (m in members) {
            val wither = "with" + m.name.replaceFirstChar { it.uppercase() }
            body.append("\n    public $name $wither(${m.javaType} ${m.name}) {\n")
            body.append("        return new $name(${members.joinToString(", ") { it.name }});\n    }\n")
        }
        body.append("\n    /** Built from data by field name, the defaults for those it lacks. */\n")
        body.append("    public static $name fromValues(MaterialValues values) {\n")
        body.append("        return new $name(")
        body.append(members.joinToString(",\n            ", prefix = "\n            ") { fromValue(it) })
        body.append(");\n    }\n")
        body.append("\n    @Override\n    public int bytes() {\n        return BYTES;\n    }\n")
        body.append("\n    @Override\n    public void write(MemorySegment segment, long offset) {\n")
        for (m in members) {
            val at = "offset + ${m.field.offset}"
            when (m.kind) {
                Kind.F32 -> body.append("        segment.set(F32, $at, ${m.name});\n")
                Kind.I32 -> body.append("        segment.set(I32, $at, ${m.name});\n")
                Kind.RECORD -> body.append("        ${m.name}.write(segment, $at);\n")
                Kind.VEC, Kind.COLOR -> {
                    val parts = if (m.kind == Kind.COLOR) listOf("r", "g", "b", "a") else listOf("x", "y", "z", "w")
                    for (i in 0 until m.field.count) {
                        body.append("        segment.set(F32, offset + ${m.field.offset + 4 * i}, ")
                        body.append("(float) ${m.name}.${parts[i]}());\n")
                    }
                }
            }
        }
        body.append("    }\n")
        body.append(textures())
        if (parameters) {
            body.append(mask())
            val features = (struct.attributes["MaterialParameters"]?.firstOrNull() as Number?)?.toInt() ?: 0
            body.append("\n    @Override\n    public int allowedFeatures() {\n        return $features;\n    }\n")
        } else {
            body.append("\n    @Override\n    public int feature() {\n        return FEATURE;\n    }\n")
        }
        body.append("}\n")
        val head = StringBuilder("package $pkg;\n\n")
        imports.forEach { head.append("import $it;\n") }
        return head.append("\n").append(body).toString()
    }

    private fun featureName() = name.removeSuffix("Parameters").replaceFirstChar { it.lowercase() }

    /**
     * Every [Texture] field's value given to a consumer, nested records' too: for Materials to count the materials
     * naming each texture. Nothing for records without (the interface's default).
     */
    private fun textures(): String {
        val calls = members.mapNotNull {
            when {
                it.kind == Kind.RECORD -> "        ${it.name}.textures(each);\n"
                it.kind == Kind.I32 && texture(it.field) != null -> "        each.accept(${it.name});\n"
                else -> null
            }
        }
        if (calls.isEmpty()) {
            return ""
        }
        imports += "java.util.function.IntConsumer"
        return "\n    @Override\n    public void textures(IntConsumer each) {\n" + calls.joinToString("") + "    }\n"
    }

    private fun fromValue(m: Member): String {
        val d = "DEFAULTS.${m.name}()"
        return when (m.kind) {
            Kind.RECORD -> "${m.javaType}.fromValues(values.nested(\"${m.name}\"))"
            Kind.F32 -> "values.f32(\"${m.name}\", $d)"
            Kind.I32 -> texture(m.field)?.let { "values.texture(\"${m.name}\", $it, $d)" }
                ?: "values.i32(\"${m.name}\", $d)"
            Kind.COLOR -> "values.color(\"${m.name}\", $d)"
            Kind.VEC -> "values.vec${m.field.count}(\"${m.name}\", $d)"
        }
    }

    /** A MaterialField per leaf field, nested ones by path: e.g. BASE_ROUGHNESS for base.roughness. */
    private fun constants(body: StringBuilder, s: GenerateSlangRecords.Struct, prefix: String, base: Int) {
        for (f in s.fields) {
            val path = prefix + f.name
            val nested = f.nested
            if (nested != null) {
                constants(body, nested, "$path.", base + f.offset)
                continue
            }
            val constant = path.split('.').joinToString("_") { snake(it) }
            val offset = base + f.offset
            val make = when {
                f.count > 1 -> "MaterialField.floats($name.class, \"$path\", $offset, ${f.count})"
                f.scalar == "float32" -> range(f)?.let { (lo, hi) ->
                    "MaterialField.f32($name.class, \"$path\", $offset, ${floatLiteral(lo)}, ${floatLiteral(hi)})"
                } ?: "MaterialField.f32($name.class, \"$path\", $offset)"
                texture(f) != null -> "MaterialField.texture($name.class, \"$path\", $offset)"
                else -> "MaterialField.i32($name.class, \"$path\", $offset)"
            }
            body.append("    /** {@code $path}, to set in place: {@code Materials.update}. */\n")
            body.append("    public static final MaterialField $constant = $make;\n")
        }
    }

    private fun snake(camel: String) = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    /** The mask's alpha and texture: the fields marked [MaskAlpha], [MaskTexture], else a nested parameters'. */
    private fun mask(): String {
        val alphaField = members.firstOrNull { "MaskAlpha" in it.field.attributes }
        val textureField = members.firstOrNull { "MaskTexture" in it.field.attributes }
        val delegate = members.firstOrNull { it.kind == Kind.RECORD && nestedKinds[it.name] == "parameters" }
        val alpha = when {
            alphaField == null -> delegate?.let { "${it.name}.maskAlpha()" } ?: "1"
            alphaField.kind == Kind.F32 -> alphaField.name
            alphaField.kind == Kind.COLOR && alphaField.field.count == 4 -> "(float) ${alphaField.name}.a()"
            alphaField.kind == Kind.VEC && alphaField.field.count == 4 -> "(float) ${alphaField.name}.w()"
            else -> throw IllegalStateException("$name.${alphaField.name}: [MaskAlpha] is a float or a float4")
        }
        val texture = when {
            textureField == null -> delegate?.let { "${it.name}.maskTexture()" } ?: "-1"
            textureField.kind == Kind.I32 -> textureField.name
            else -> throw IllegalStateException("$name.${textureField.name}: [MaskTexture] is a texture slot (int)")
        }
        return "\n    @Override\n    public float maskAlpha() {\n        return $alpha;\n    }\n" +
            "\n    @Override\n    public int maskTexture() {\n        return $texture;\n    }\n"
    }
}
