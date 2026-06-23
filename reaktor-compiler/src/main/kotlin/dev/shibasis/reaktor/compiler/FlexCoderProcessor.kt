package dev.shibasis.reaktor.compiler

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

private val STRUCT_FQ = "dev.shibasis.reaktor.flexbuffer.core.Struct"
private val FLEX_CODER = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexCoder")
private val FLEX_CODER_REGISTRY = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexCoderRegistry")
private val FLEX_BUFFERS_BUILDER = ClassName("dev.shibasis.reaktor.flexbuffer.flatbuffers", "FlexBuffersBuilder")
private val REFERENCE = ClassName("dev.shibasis.reaktor.flexbuffer.flatbuffers", "Reference")
private val FLEX_MAP = ClassName("dev.shibasis.reaktor.flexbuffer.flatbuffers", "Map")
private val FLEX_VECTOR = ClassName("dev.shibasis.reaktor.flexbuffer.flatbuffers", "Vector")
private val FLEX_INT_LIST = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexIntList")
private val FLEX_LONG_LIST = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexLongList")
private val FLEX_DOUBLE_LIST = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexDoubleList")
private val FLEX_FLOAT_LIST = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexFloatList")
private val FLEX_STRING_LIST = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexStringList")
private val FLEX_BOOLEAN_LIST = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexBooleanList")
private val FLEX_ACCESSOR_LIST = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexAccessorList")
private val FLEX_STRING_INT_MAP = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexStringIntMap")
private val FLEX_STRING_DOUBLE_MAP = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexStringDoubleMap")
private val FLEX_STRING_STRING_MAP = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexStringStringMap")
private val FLEX_STRING_BOOLEAN_MAP = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexStringBooleanMap")
private val FLEX_ACCESSOR_MAP = ClassName("dev.shibasis.reaktor.flexbuffer.core", "FlexAccessorMap")
private val ARRAY_READ_BUFFER = ClassName("dev.shibasis.reaktor.flexbuffer.flatbuffers", "ArrayReadBuffer")
private val GET_ROOT = MemberName("dev.shibasis.reaktor.flexbuffer.flatbuffers", "getRoot")
private val FLEX_READ = ClassName("dev.shibasis.reaktor.flexbuffer.flatbuffers", "FlexRead")

class FlexCoderProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(STRUCT_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS }
            .toList()

        if (symbols.isEmpty()) return emptyList()

        val deferred = mutableListOf<KSAnnotated>()
        val generated = mutableListOf<Pair<ClassName, ClassName>>() // (class, coder)

        for (classDecl in symbols) {
            try {
                val result = generateFlexCoder(classDecl)
                if (result != null) {
                    generated.add(result)
                } else {
                    deferred.add(classDecl)
                }
            } catch (e: Exception) {
                logger.error("FlexCoderProcessor: failed for ${classDecl.simpleName.asString()}: ${e.message}", classDecl)
            }
        }

        if (generated.isNotEmpty()) {
            generateRegistrationFunction(generated, symbols.first().containingFile)
        }

        return deferred
    }

    private fun generateFlexCoder(classDecl: KSClassDeclaration): Pair<ClassName, ClassName>? {
        val className = classDecl.toClassName()
        val coderName = ClassName(className.packageName, "${className.simpleName}FlexCoder")
        val accessorName = ClassName(className.packageName, "${className.simpleName}Accessor")
        val properties = classDecl.getAllProperties()
            .filter { it.hasBackingField }
            .toList()

        if (properties.isEmpty()) {
            logger.warn("FlexCoderProcessor: ${className.simpleName} has no properties, skipping")
            return null
        }

        val sortedProps = properties.sortedBy { it.simpleName.asString() }

        // Pre-encoded key block: sorted field names, null-terminated, concatenated.
        // Written once per buffer via builder.keyBlock(); every map of this type
        // shares the key bytes AND the key vector — zero per-field key work.
        val sortedNames = sortedProps.map { it.simpleName.asString() }
        val keyStarts = IntArray(sortedNames.size)
        var keyCursor = 0
        for ((i, n) in sortedNames.withIndex()) {
            keyStarts[i] = keyCursor
            keyCursor += n.encodeToByteArray().size + 1
        }
        val keysLiteral = sortedNames.joinToString("\u0000", postfix = "\u0000")

        val encodeMethod = buildEncodeMethod(className)
        val encodeKeyedMethod = buildEncodeKeyedMethod(className, sortedProps)
        val decodeMethod = buildDecodeMethod(className)
        val decodeMapMethod = buildDecodeMapMethod(className)
        val decodeAtMethod = buildDecodeAtMethod(className, properties, sortedProps)

        val coderObject = TypeSpec.objectBuilder(coderName)
            .addSuperinterface(FLEX_CODER.parameterizedBy(className))
            .addProperty(
                PropertySpec.builder("KEYS", ByteArray::class, KModifier.PRIVATE)
                    .initializer("%S.encodeToByteArray()", keysLiteral)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("KEY_STARTS", IntArray::class, KModifier.PRIVATE)
                    .initializer("intArrayOf(${keyStarts.joinToString(", ")})")
                    .build()
            )
            .addFunction(encodeMethod)
            .addFunction(encodeKeyedMethod)
            .addFunction(decodeMethod)
            .addFunction(decodeMapMethod)
            .addFunction(decodeAtMethod)
            .build()

        val accessorClass = buildAccessorClass(className, accessorName, properties, sortedProps)

        val optIn = AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
            .addMember("%T::class", ClassName("kotlin", "ExperimentalUnsignedTypes"))
            .build()

        val fileSpec = FileSpec.builder(className.packageName, coderName.simpleName)
            .addAnnotation(optIn)
            .addType(coderObject)
            .addType(accessorClass)
            .addFunction(
                FunSpec.builder("as${className.simpleName}")
                    .receiver(REFERENCE)
                    .returns(accessorName)
                    .addStatement("return %T(this.toMap())", accessorName)
                    .build()
            )
            .addFunction(
                FunSpec.builder("as${className.simpleName}")
                    .receiver(ByteArray::class)
                    .returns(accessorName)
                    .addStatement("return %T(%M(this).toMap())", accessorName, GET_ROOT)
                    .build()
            )
            .build()

        val deps = classDecl.containingFile?.let { Dependencies(false, it) }
            ?: Dependencies(false)
        fileSpec.writeTo(codeGenerator, deps)

        logger.info("FlexCoderProcessor: generated ${coderName.simpleName} + ${accessorName.simpleName} for ${className.simpleName}")
        return className to coderName
    }

    private fun buildEncodeMethod(className: ClassName): FunSpec =
        FunSpec.builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("builder", FLEX_BUFFERS_BUILDER)
            .addParameter("value", className)
            .addParameter("key", String::class.asTypeName().copy(nullable = true))
            .addStatement("encodeKeyed(builder, value, builder.resolveKey(key))")
            .build()

    /**
     * The keyed fast path: field keys resolve to precomputed buffer offsets via the
     * shared key block — no per-field hashing, no per-map key-vector writes after
     * the first map of this type, no runtime sort, no key-width loops.
     */
    private fun buildEncodeKeyedMethod(
        className: ClassName,
        sortedProps: List<KSPropertyDeclaration>
    ): FunSpec {
        val builder = FunSpec.builder("encodeKeyed")
            .addParameter("builder", FLEX_BUFFERS_BUILDER)
            .addParameter("value", className)
            .addParameter("keyOffset", Int::class)

        builder.addStatement("val ko = builder.keyBlock(KEYS, KEY_STARTS)")
        builder.addStatement("val m = builder.startMap()")

        for ((index, prop) in sortedProps.withIndex()) {
            val name = prop.simpleName.asString()
            val type = prop.type.resolve()
            generateEncodeField(builder, name, type, "value.$name", index)
        }

        builder.addStatement("builder.endMapKeyed(m, keyOffset, KEYS, ko)")
        return builder.build()
    }

    private fun generateEncodeField(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        accessor: String,
        index: Int
    ) {
        val typeName = type.declaration.qualifiedName?.asString() ?: return
        val nullable = type.isMarkedNullable
        val ko = "ko[$index]"

        if (nullable) {
            builder.beginControlFlow("if ($accessor != null)")
        }

        when (typeName) {
            "kotlin.Boolean" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.Byte" -> builder.addStatement("builder.setKeyed($ko, $accessor.toLong())")
            "kotlin.Short" -> builder.addStatement("builder.setKeyed($ko, $accessor.toLong())")
            "kotlin.Int" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.Long" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.Float" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.Double" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.Char" -> builder.addStatement("builder.setKeyed($ko, $accessor.code)")
            "kotlin.String" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.ByteArray" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.ShortArray" -> builder.addStatement("builder.setKeyed($ko, IntArray($accessor.size) { $accessor[it].toInt() })")
            "kotlin.IntArray" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.LongArray" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.FloatArray" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.DoubleArray" -> builder.addStatement("builder.setKeyed($ko, $accessor)")
            "kotlin.collections.List", "kotlin.collections.MutableList",
            "kotlin.collections.Set", "kotlin.collections.MutableSet",
            "kotlin.collections.ArrayList" -> {
                generateEncodeCollection(builder, fieldName, type, accessor, ko)
            }
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "kotlin.collections.LinkedHashMap", "kotlin.collections.HashMap" -> {
                generateEncodeMap(builder, fieldName, type, accessor, ko)
            }
            else -> {
                // Nested class — delegate to its FlexCoder's keyed path (direct object call)
                val decl = type.declaration
                if (decl is KSClassDeclaration && hasStruct(decl)) {
                    val coderName = ClassName(
                        decl.packageName.asString(),
                        "${decl.simpleName.asString()}FlexCoder"
                    )
                    builder.addStatement("%T.encodeKeyed(builder, $accessor, $ko)", coderName)
                } else {
                    builder.addComment("TODO: unsupported type $typeName for field $fieldName")
                }
            }
        }

        if (nullable) {
            builder.nextControlFlow("else")
            builder.addStatement("builder.putNullKeyed($ko)")
            builder.endControlFlow()
        }
    }

    private fun generateEncodeCollection(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        accessor: String,
        ko: String
    ) {
        val elementType = type.arguments.firstOrNull()?.type?.resolve() ?: return
        val elemTypeName = elementType.declaration.qualifiedName?.asString() ?: return
        val collTypeName = type.declaration.qualifiedName?.asString() ?: return
        val isSet = collTypeName.contains("Set")
        val listAccessor = if (isSet) "$accessor.toList()" else accessor

        // Try typed vector for primitive collections — more compact wire format
        when (elemTypeName) {
            "kotlin.Int" -> {
                builder.addStatement("builder.setIntListKeyed($ko, $listAccessor)")
                return
            }
            "kotlin.Long" -> {
                builder.addStatement("builder.setLongListKeyed($ko, $listAccessor)")
                return
            }
            "kotlin.Float" -> {
                builder.addStatement("builder.setFloatListKeyed($ko, $listAccessor)")
                return
            }
            "kotlin.Double" -> {
                builder.addStatement("builder.setDoubleListKeyed($ko, $listAccessor)")
                return
            }
            "kotlin.Short" -> {
                builder.addStatement("builder.setIntListKeyed($ko, $accessor.map { it.toInt() })")
                return
            }
            "kotlin.Char" -> {
                builder.addStatement("builder.setIntListKeyed($ko, $accessor.map { it.code })")
                return
            }
        }

        // General vector for non-primitive element types
        builder.addStatement("val v_${fieldName} = builder.startVector()")
        builder.beginControlFlow("for (elem in $accessor)")

        when (elemTypeName) {
            "kotlin.Boolean" -> builder.addStatement("builder.put(elem)")
            "kotlin.Byte" -> builder.addStatement("builder.put(elem)")
            "kotlin.Int" -> builder.addStatement("builder.put(elem)")
            "kotlin.Long" -> builder.addStatement("builder.put(elem)")
            "kotlin.Float" -> builder.addStatement("builder.put(elem)")
            "kotlin.Double" -> builder.addStatement("builder.put(elem)")
            "kotlin.String" -> builder.addStatement("builder.put(elem)")
            else -> {
                val elemDecl = elementType.declaration
                if (elemDecl is KSClassDeclaration && hasStruct(elemDecl)) {
                    val coderName = ClassName(
                        elemDecl.packageName.asString(),
                        "${elemDecl.simpleName.asString()}FlexCoder"
                    )
                    builder.addStatement("%T.encodeKeyed(builder, elem, -1)", coderName)
                } else if (elemTypeName.startsWith("kotlin.collections.List") ||
                    elemTypeName.startsWith("kotlin.collections.Set")) {
                    val innerElemType = elementType.arguments.firstOrNull()?.type?.resolve()
                    val innerElemName = innerElemType?.declaration?.qualifiedName?.asString()
                    val innerIsSet = elemTypeName.contains("Set")
                    val elemListExpr = if (innerIsSet) "elem.toList()" else "elem"
                    when (innerElemName) {
                        "kotlin.Int" -> builder.addStatement("builder.setIntList(null, $elemListExpr)")
                        "kotlin.Float" -> builder.addStatement("builder.setFloatList(null, $elemListExpr)")
                        "kotlin.Double" -> builder.addStatement("builder.setDoubleList(null, $elemListExpr)")
                        else -> builder.addComment("TODO: unsupported nested collection element $innerElemName")
                    }
                } else {
                    builder.addComment("TODO: unsupported collection element type $elemTypeName")
                }
            }
        }

        builder.endControlFlow()
        builder.addStatement("builder.endVectorKeyed($ko, v_${fieldName})")
    }

    private fun generateEncodeMap(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        accessor: String,
        ko: String
    ) {
        val keyType = type.arguments.getOrNull(0)?.type?.resolve() ?: return
        val valueType = type.arguments.getOrNull(1)?.type?.resolve() ?: return
        val valueTypeName = valueType.declaration.qualifiedName?.asString() ?: return

        builder.addStatement("val m_${fieldName} = builder.startMap()")
        builder.beginControlFlow("for ((k, v) in $accessor)")

        val keyExpr = when (keyType.declaration.qualifiedName?.asString()) {
            "kotlin.String" -> "k"
            else -> "k.toString()"
        }

        when (valueTypeName) {
            "kotlin.Boolean" -> builder.addStatement("builder.set($keyExpr, v)", )
            "kotlin.Int" -> builder.addStatement("builder.set($keyExpr, v)")
            "kotlin.Long" -> builder.addStatement("builder.set($keyExpr, v)")
            "kotlin.Float" -> builder.addStatement("builder.set($keyExpr, v)")
            "kotlin.Double" -> builder.addStatement("builder.set($keyExpr, v)")
            "kotlin.String" -> builder.addStatement("builder.set($keyExpr, v)")
            else -> {
                val valueDecl = valueType.declaration
                if (valueDecl is KSClassDeclaration && hasStruct(valueDecl)) {
                    val coderName = ClassName(
                        valueDecl.packageName.asString(),
                        "${valueDecl.simpleName.asString()}FlexCoder"
                    )
                    builder.addStatement("%T.encode(builder, v, $keyExpr)", coderName)
                } else if (valueTypeName.startsWith("kotlin.collections.List")) {
                    val innerType = valueType.arguments.firstOrNull()?.type?.resolve()
                    val innerName = innerType?.declaration?.qualifiedName?.asString()
                    when (innerName) {
                        "kotlin.Double" -> builder.addStatement("builder.setDoubleList($keyExpr, v)")
                        "kotlin.Int" -> builder.addStatement("builder.setIntList($keyExpr, v)")
                        "kotlin.Float" -> builder.addStatement("builder.setFloatList($keyExpr, v)")
                        else -> builder.addComment("TODO: unsupported map value List<$innerName>")
                    }
                } else {
                    builder.addComment("TODO: unsupported map value type $valueTypeName")
                }
            }
        }

        builder.endControlFlow()
        builder.addStatement("builder.endMapDynamicKeyed(m_${fieldName}, $ko)")
    }

    private fun buildDecodeMethod(className: ClassName): FunSpec =
        FunSpec.builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("ref", REFERENCE)
            .returns(className)
            .addStatement("val m = ref.toMap()")
            .addStatement("return decodeAt(m.buf, m.end, m.byteWidth)")
            .build()

    private fun buildDecodeMapMethod(className: ClassName): FunSpec =
        FunSpec.builder("decodeMap")
            .addParameter("map", FLEX_MAP)
            .returns(className)
            .addStatement("return decodeAt(map.buf, map.end, map.byteWidth)")
            .build()

    /**
     * Allocation-free positional decode: navigates with hoisted (buf, end, bw, tb)
     * locals through FlexRead statics. No Map/Vector objects anywhere in the tree —
     * the only allocations are the result objects.
     */
    private fun buildDecodeAtMethod(
        className: ClassName,
        originalProps: List<KSPropertyDeclaration>,
        sortedProps: List<KSPropertyDeclaration>
    ): FunSpec {
        val builder = FunSpec.builder("decodeAt")
            .addParameter("buf", ByteArray::class)
            .addParameter("end", Int::class)
            .addParameter("bw", Int::class)
            .returns(className)

        builder.addStatement("val sz = %T.size(buf, end, bw)", FLEX_READ)
        builder.addStatement("val tb = end + sz * bw")

        for ((index, prop) in sortedProps.withIndex()) {
            val name = prop.simpleName.asString()
            val type = prop.type.resolve()
            generateDecodeField(builder, name, type, index)
        }

        val constructorArgs = originalProps.joinToString(",\n    ") { prop ->
            val name = prop.simpleName.asString()
            "$name = _$name"
        }
        builder.addStatement("return %T(\n    $constructorArgs\n)", className)
        return builder.build()
    }

    private fun generateDecodeField(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        index: Int
    ) {
        val nullable = type.isMarkedNullable
        if (nullable) {
            builder.beginControlFlow("val _$fieldName = if (%T.isNull(buf, tb, $index))", FLEX_READ)
            builder.addStatement("null")
            builder.nextControlFlow("else")
            generateDecodeFieldInner(builder, fieldName, type, index, prefixed = false)
            builder.endControlFlow()
        } else {
            generateDecodeFieldInner(builder, fieldName, type, index, prefixed = true)
        }
    }

    private fun generateDecodeFieldInner(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        index: Int,
        prefixed: Boolean
    ) {
        val typeName = type.declaration.qualifiedName?.asString() ?: return
        val prefix = if (prefixed) "val _$fieldName = " else ""

        when (typeName) {
            "kotlin.Boolean" -> builder.addStatement("${prefix}%T.getBoolean(buf, end, bw, $index)", FLEX_READ)
            "kotlin.Byte" -> builder.addStatement("${prefix}%T.getInt(buf, end, bw, tb, $index).toByte()", FLEX_READ)
            "kotlin.Short" -> builder.addStatement("${prefix}%T.getInt(buf, end, bw, tb, $index).toShort()", FLEX_READ)
            "kotlin.Int" -> builder.addStatement("${prefix}%T.getInt(buf, end, bw, tb, $index)", FLEX_READ)
            "kotlin.Long" -> builder.addStatement("${prefix}%T.getLong(buf, end, bw, tb, $index)", FLEX_READ)
            "kotlin.Float" -> builder.addStatement("${prefix}%T.getFloat(buf, end, bw, tb, $index)", FLEX_READ)
            "kotlin.Double" -> builder.addStatement("${prefix}%T.getDouble(buf, end, bw, tb, $index)", FLEX_READ)
            "kotlin.Char" -> builder.addStatement("${prefix}%T.getInt(buf, end, bw, tb, $index).toChar()", FLEX_READ)
            "kotlin.String" -> builder.addStatement("${prefix}%T.getString(buf, end, bw, tb, $index)", FLEX_READ)
            "kotlin.ByteArray" -> builder.addStatement("${prefix}%T.getBlob(buf, end, bw, tb, $index)", FLEX_READ)
            "kotlin.IntArray" -> {
                emitVecHeader(builder, fieldName, index)
                builder.addStatement("${prefix}%T.toIntArray(buf, _${fieldName}_ve, _${fieldName}_vw, %T.size(buf, _${fieldName}_ve, _${fieldName}_vw))", FLEX_READ, FLEX_READ)
            }
            "kotlin.LongArray" -> {
                emitVecHeader(builder, fieldName, index)
                builder.addStatement("${prefix}%T.toLongArray(buf, _${fieldName}_ve, _${fieldName}_vw, %T.size(buf, _${fieldName}_ve, _${fieldName}_vw))", FLEX_READ, FLEX_READ)
            }
            "kotlin.FloatArray" -> {
                emitVecHeader(builder, fieldName, index)
                builder.addStatement("${prefix}%T.toFloatArray(buf, _${fieldName}_ve, _${fieldName}_vw, %T.size(buf, _${fieldName}_ve, _${fieldName}_vw))", FLEX_READ, FLEX_READ)
            }
            "kotlin.DoubleArray" -> {
                emitVecHeader(builder, fieldName, index)
                builder.addStatement("${prefix}%T.toDoubleArray(buf, _${fieldName}_ve, _${fieldName}_vw, %T.size(buf, _${fieldName}_ve, _${fieldName}_vw))", FLEX_READ, FLEX_READ)
            }
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.ArrayList" -> {
                generateDecodeList(builder, fieldName, type, index, prefix, asSet = false)
            }
            "kotlin.collections.Set", "kotlin.collections.MutableSet", "kotlin.collections.LinkedHashSet" -> {
                generateDecodeList(builder, fieldName, type, index, prefix, asSet = true)
            }
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "kotlin.collections.LinkedHashMap", "kotlin.collections.HashMap" -> {
                generateDecodeMap(builder, fieldName, type, index, prefix)
            }
            else -> {
                val decl = type.declaration
                if (decl is KSClassDeclaration && hasStruct(decl)) {
                    val coderName = ClassName(
                        decl.packageName.asString(),
                        "${decl.simpleName.asString()}FlexCoder"
                    )
                    builder.addStatement(
                        "${prefix}%T.decodeAt(buf, %T.childEnd(buf, end, bw, $index), %T.childWidth(buf, tb, $index))",
                        coderName, FLEX_READ, FLEX_READ
                    )
                } else {
                    builder.addComment("TODO: unsupported type $typeName for field $fieldName")
                    builder.addStatement("${prefix}error(%S)", "unsupported type $typeName")
                }
            }
        }
    }

    /** Emits `_x_ve` / `_x_vw` locals: the child container's data position and width. */
    private fun emitVecHeader(builder: FunSpec.Builder, fieldName: String, index: Int) {
        builder.addStatement("val _${fieldName}_ve = %T.childEnd(buf, end, bw, $index)", FLEX_READ)
        builder.addStatement("val _${fieldName}_vw = %T.childWidth(buf, tb, $index)", FLEX_READ)
    }

    private fun generateDecodeList(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        index: Int,
        prefix: String,
        asSet: Boolean
    ) {
        val elemType = type.arguments.firstOrNull()?.type?.resolve() ?: return
        val elemName = elemType.declaration.qualifiedName?.asString() ?: return
        val f = fieldName
        val collCtor = if (asSet) "LinkedHashSet" else "ArrayList"
        val collVar = if (asSet) "s" else "list"

        emitVecHeader(builder, f, index)
        builder.addStatement("val _${f}_n = %T.size(buf, _${f}_ve, _${f}_vw)", FLEX_READ)

        fun typedLoop(kt: String, readCall: String) {
            builder.addStatement(
                "${prefix}$collCtor<$kt>(_${f}_n).also { $collVar -> for (j in 0 until _${f}_n) $collVar.add($readCall) }",
                FLEX_READ
            )
        }

        when (elemName) {
            // Primitive lists are written as typed vectors by the keyed encoder.
            "kotlin.Int" -> typedLoop("Int", "%T.typedInt(buf, _${f}_ve, _${f}_vw, j)")
            "kotlin.Long" -> typedLoop("Long", "%T.typedLong(buf, _${f}_ve, _${f}_vw, j)")
            "kotlin.Float" -> typedLoop("Float", "%T.typedFloat(buf, _${f}_ve, _${f}_vw, j)")
            "kotlin.Double" -> typedLoop("Double", "%T.typedDouble(buf, _${f}_ve, _${f}_vw, j)")
            "kotlin.Short" -> typedLoop("Short", "%T.typedInt(buf, _${f}_ve, _${f}_vw, j).toShort()")
            "kotlin.Char" -> typedLoop("Char", "%T.typedInt(buf, _${f}_ve, _${f}_vw, j).toChar()")
            // Booleans go through the generic vector path (inline values).
            "kotlin.Boolean" -> typedLoop("Boolean", "%T.getBoolean(buf, _${f}_ve, _${f}_vw, j)")
            "kotlin.String" -> {
                builder.addStatement("val _${f}_tb = _${f}_ve + _${f}_n * _${f}_vw")
                typedLoop("String", "%T.vecString(buf, _${f}_ve, _${f}_vw, _${f}_tb, j)")
            }
            else -> {
                val elemDecl = elemType.declaration
                if (elemDecl is KSClassDeclaration && hasStruct(elemDecl)) {
                    val coderName = ClassName(elemDecl.packageName.asString(), "${elemDecl.simpleName.asString()}FlexCoder")
                    builder.addStatement("val _${f}_tb = _${f}_ve + _${f}_n * _${f}_vw")
                    builder.addStatement(
                        "${prefix}$collCtor<${elemDecl.simpleName.asString()}>(_${f}_n).also { $collVar -> for (j in 0 until _${f}_n) $collVar.add(%T.decodeAt(buf, %T.childEnd(buf, _${f}_ve, _${f}_vw, j), %T.childWidth(buf, _${f}_tb, j))) }",
                        coderName, FLEX_READ, FLEX_READ
                    )
                } else if (elemName.startsWith("kotlin.collections.List") || elemName.startsWith("kotlin.collections.Set")) {
                    val innerType = elemType.arguments.firstOrNull()?.type?.resolve()
                    val innerName = innerType?.declaration?.qualifiedName?.asString()
                    val innerIsSet = elemName.contains("Set")
                    val innerCtor = if (innerIsSet) "LinkedHashSet" else "ArrayList"
                    val outerElem = if (innerIsSet) "Set" else "List"
                    builder.addStatement("val _${f}_tb = _${f}_ve + _${f}_n * _${f}_vw")
                    fun innerLoop(kt: String, readCall: String) {
                        builder.addStatement(
                            "${prefix}$collCtor<$outerElem<$kt>>(_${f}_n).also { $collVar -> for (j in 0 until _${f}_n) { " +
                                "val ie = %T.childEnd(buf, _${f}_ve, _${f}_vw, j); val iw = %T.childWidth(buf, _${f}_tb, j); " +
                                "val im = %T.size(buf, ie, iw); " +
                                "$collVar.add($innerCtor<$kt>(im).also { inner -> for (q in 0 until im) inner.add($readCall) }) } }",
                            FLEX_READ, FLEX_READ, FLEX_READ, FLEX_READ
                        )
                    }
                    when (innerName) {
                        "kotlin.Int" -> innerLoop("Int", "%T.typedInt(buf, ie, iw, q)")
                        "kotlin.Float" -> innerLoop("Float", "%T.typedFloat(buf, ie, iw, q)")
                        "kotlin.Double" -> innerLoop("Double", "%T.typedDouble(buf, ie, iw, q)")
                        else -> {
                            builder.addComment("TODO: unsupported nested collection element $innerName for $fieldName")
                            builder.addStatement("${prefix}error(%S)", "unsupported")
                        }
                    }
                } else {
                    builder.addComment("TODO: unsupported list element $elemName for $fieldName")
                    builder.addStatement("${prefix}error(%S)", "unsupported")
                }
            }
        }
    }

    private fun generateDecodeMap(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        index: Int,
        prefix: String
    ) {
        val keyType = type.arguments.getOrNull(0)?.type?.resolve() ?: return
        val valueType = type.arguments.getOrNull(1)?.type?.resolve() ?: return
        val keyTypeName = keyType.declaration.qualifiedName?.asString() ?: return
        val valueTypeName = valueType.declaration.qualifiedName?.asString() ?: return
        val f = fieldName

        emitVecHeader(builder, f, index)
        builder.addStatement("val _${f}_n = %T.size(buf, _${f}_ve, _${f}_vw)", FLEX_READ)
        builder.addStatement("val _${f}_tb = _${f}_ve + _${f}_n * _${f}_vw")
        builder.addStatement("val _${f}_ke = %T.keysEnd(buf, _${f}_ve, _${f}_vw)", FLEX_READ)
        builder.addStatement("val _${f}_kw = %T.keysWidth(buf, _${f}_ve, _${f}_vw)", FLEX_READ)

        val keyKt = keyType.toSimpleType()
        val keyConvertSuffix = when (keyTypeName) {
            "kotlin.String" -> ""
            "kotlin.Int" -> ".toInt()"
            "kotlin.Long" -> ".toLong()"
            else -> ""
        }
        val keyCall = "%T.keyString(buf, _${f}_ke, _${f}_kw, j)$keyConvertSuffix"

        fun mapLoop(valKt: String, valCall: String, extraTypes: Int) {
            val types = mutableListOf<Any>(FLEX_READ)
            repeat(extraTypes) { types.add(FLEX_READ) }
            builder.addStatement(
                "${prefix}LinkedHashMap<$keyKt, $valKt>(_${f}_n).also { m -> for (j in 0 until _${f}_n) m[$keyCall] = $valCall }",
                *types.toTypedArray()
            )
        }

        when (valueTypeName) {
            "kotlin.Boolean" -> mapLoop("Boolean", "%T.getBoolean(buf, _${f}_ve, _${f}_vw, j)", 1)
            "kotlin.Int" -> mapLoop("Int", "%T.getInt(buf, _${f}_ve, _${f}_vw, _${f}_tb, j)", 1)
            "kotlin.Long" -> mapLoop("Long", "%T.getLong(buf, _${f}_ve, _${f}_vw, _${f}_tb, j)", 1)
            "kotlin.Float" -> mapLoop("Float", "%T.getFloat(buf, _${f}_ve, _${f}_vw, _${f}_tb, j)", 1)
            "kotlin.Double" -> mapLoop("Double", "%T.getDouble(buf, _${f}_ve, _${f}_vw, _${f}_tb, j)", 1)
            "kotlin.String" -> mapLoop("String", "%T.getString(buf, _${f}_ve, _${f}_vw, _${f}_tb, j)", 1)
            else -> {
                val valueDecl = valueType.declaration
                if (valueDecl is KSClassDeclaration && hasStruct(valueDecl)) {
                    val coderName = ClassName(valueDecl.packageName.asString(), "${valueDecl.simpleName.asString()}FlexCoder")
                    builder.addStatement(
                        "${prefix}LinkedHashMap<$keyKt, ${valueDecl.simpleName.asString()}>(_${f}_n).also { m -> for (j in 0 until _${f}_n) m[$keyCall] = %T.decodeAt(buf, %T.childEnd(buf, _${f}_ve, _${f}_vw, j), %T.childWidth(buf, _${f}_tb, j)) }",
                        FLEX_READ, coderName, FLEX_READ, FLEX_READ
                    )
                } else if (valueTypeName.startsWith("kotlin.collections.List")) {
                    val innerType = valueType.arguments.firstOrNull()?.type?.resolve()
                    val innerName = innerType?.declaration?.qualifiedName?.asString()
                    fun listValLoop(kt: String, readCall: String) {
                        builder.addStatement(
                            "${prefix}LinkedHashMap<$keyKt, List<$kt>>(_${f}_n).also { m -> for (j in 0 until _${f}_n) { " +
                                "val ie = %T.childEnd(buf, _${f}_ve, _${f}_vw, j); val iw = %T.childWidth(buf, _${f}_tb, j); " +
                                "val im = %T.size(buf, ie, iw); " +
                                "m[$keyCall] = ArrayList<$kt>(im).also { inner -> for (q in 0 until im) inner.add($readCall) } } }",
                            FLEX_READ, FLEX_READ, FLEX_READ, FLEX_READ, FLEX_READ
                        )
                    }
                    when (innerName) {
                        "kotlin.Double" -> listValLoop("Double", "%T.typedDouble(buf, ie, iw, q)")
                        "kotlin.Int" -> listValLoop("Int", "%T.typedInt(buf, ie, iw, q)")
                        "kotlin.Float" -> listValLoop("Float", "%T.typedFloat(buf, ie, iw, q)")
                        else -> {
                            builder.addComment("TODO: unsupported Map value List<$innerName>")
                            builder.addStatement("${prefix}error(%S)", "unsupported")
                        }
                    }
                } else {
                    builder.addComment("TODO: unsupported map value $valueTypeName for $fieldName")
                    builder.addStatement("${prefix}error(%S)", "unsupported")
                }
            }
        }
    }

    private fun buildAccessorClass(
        className: ClassName,
        accessorName: ClassName,
        originalProps: List<KSPropertyDeclaration>,
        sortedProps: List<KSPropertyDeclaration>
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(accessorName)
            .addAnnotation(JvmInline::class)
            .addModifiers(KModifier.VALUE)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("map", FLEX_MAP)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("map", FLEX_MAP)
                    .initializer("map")
                    .build()
            )

        for ((index, prop) in sortedProps.withIndex()) {
            val name = prop.simpleName.asString()
            val type = prop.type.resolve()
            val accessorProp = buildAccessorProperty(name, type, index)
            if (accessorProp != null) {
                builder.addProperty(accessorProp)
            }
        }

        // toDataClass() — materializes the full data class via the coder's
        // allocation-free positional decode.
        val coderName = ClassName(className.packageName, "${className.simpleName}FlexCoder")
        builder.addFunction(
            FunSpec.builder("toDataClass")
                .returns(className)
                .addStatement("return %T.decodeAt(map.buf, map.end, map.byteWidth)", coderName)
                .build()
        )

        return builder.build()
    }

    private fun buildAccessorProperty(
        name: String,
        type: KSType,
        index: Int
    ): PropertySpec? {
        val typeName = type.declaration.qualifiedName?.asString() ?: return null
        val nullable = type.isMarkedNullable

        // Helper to build a nullable or non-nullable property with appropriate getter
        fun scalarProp(propType: TypeName, readExpr: String): PropertySpec {
            val actualType = if (nullable) propType.copy(nullable = true) else propType
            val getter = if (nullable) {
                FunSpec.getterBuilder().addStatement("return if (map.isNullAt($index)) null else $readExpr").build()
            } else {
                FunSpec.getterBuilder().addStatement("return $readExpr").build()
            }
            return PropertySpec.builder(name, actualType).getter(getter).build()
        }

        return when (typeName) {
            "kotlin.Boolean" -> scalarProp(Boolean::class.asTypeName(), "map.getBoolean($index)")
            "kotlin.Byte" -> scalarProp(Byte::class.asTypeName(), "map.getInt($index).toByte()")
            "kotlin.Short" -> scalarProp(Short::class.asTypeName(), "map.getInt($index).toShort()")
            "kotlin.Int" -> scalarProp(Int::class.asTypeName(), "map.getInt($index)")
            "kotlin.Long" -> scalarProp(Long::class.asTypeName(), "map.getLong($index)")
            "kotlin.Float" -> scalarProp(Float::class.asTypeName(), "map.getFloat($index)")
            "kotlin.Double" -> scalarProp(Double::class.asTypeName(), "map.getDouble($index)")
            "kotlin.Char" -> scalarProp(Char::class.asTypeName(), "map.getInt($index).toChar()")
            "kotlin.String" -> scalarProp(String::class.asTypeName(), "map.getString($index)")
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.ArrayList" -> {
                buildAccessorListProperty(name, type, index)
            }
            "kotlin.collections.Set", "kotlin.collections.MutableSet", "kotlin.collections.LinkedHashSet" -> {
                buildAccessorListProperty(name, type, index)
            }
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "kotlin.collections.LinkedHashMap", "kotlin.collections.HashMap" -> {
                buildAccessorMapProperty(name, type, index)
            }
            else -> {
                val decl = type.declaration
                if (decl is KSClassDeclaration && hasStruct(decl)) {
                    val nestedAccessor = ClassName(
                        decl.packageName.asString(),
                        "${decl.simpleName.asString()}Accessor"
                    )
                    val propType = if (nullable) nestedAccessor.copy(nullable = true) else nestedAccessor
                    val getter = if (nullable) {
                        FunSpec.getterBuilder()
                            .addStatement("return if (map.isNullAt($index)) null else %T(map.getMap($index))", nestedAccessor)
                            .build()
                    } else {
                        FunSpec.getterBuilder()
                            .addStatement("return %T(map.getMap($index))", nestedAccessor)
                            .build()
                    }
                    PropertySpec.builder(name, propType).getter(getter).build()
                } else null
            }
        }
    }

    private fun buildAccessorListProperty(
        name: String,
        type: KSType,
        index: Int
    ): PropertySpec? {
        val elemType = type.arguments.firstOrNull()?.type?.resolve() ?: return null
        val elemName = elemType.declaration.qualifiedName?.asString() ?: return null
        val nullable = type.isMarkedNullable

        // Helper to build nullable-aware collection property
        fun collProp(propType: TypeName, readExpr: String, vararg args: Any): PropertySpec {
            val actualType = if (nullable) propType.copy(nullable = true) else propType
            val getter = if (nullable) {
                FunSpec.getterBuilder().addStatement("return if (map.isNullAt($index)) null else $readExpr", *args).build()
            } else {
                FunSpec.getterBuilder().addStatement("return $readExpr", *args).build()
            }
            return PropertySpec.builder(name, actualType).getter(getter).build()
        }

        return when (elemName) {
            "kotlin.Int" -> collProp(FLEX_INT_LIST, "%T(map.getVector($index))", FLEX_INT_LIST)
            "kotlin.Long" -> collProp(FLEX_LONG_LIST, "%T(map.getVector($index))", FLEX_LONG_LIST)
            "kotlin.Double" -> collProp(FLEX_DOUBLE_LIST, "%T(map.getVector($index))", FLEX_DOUBLE_LIST)
            "kotlin.Float" -> collProp(FLEX_FLOAT_LIST, "%T(map.getVector($index))", FLEX_FLOAT_LIST)
            "kotlin.String" -> collProp(FLEX_STRING_LIST, "%T(map.getVector($index))", FLEX_STRING_LIST)
            "kotlin.Boolean" -> collProp(FLEX_BOOLEAN_LIST, "%T(map.getVector($index))", FLEX_BOOLEAN_LIST)
            else -> {
                val elemDecl = elemType.declaration
                if (elemDecl is KSClassDeclaration && hasStruct(elemDecl)) {
                    val nestedAccessor = ClassName(
                        elemDecl.packageName.asString(),
                        "${elemDecl.simpleName.asString()}Accessor"
                    )
                    collProp(FLEX_ACCESSOR_LIST.parameterizedBy(nestedAccessor),
                        "%T(map.getVector($index)) { %T(it) }", FLEX_ACCESSOR_LIST, nestedAccessor)
                } else null
            }
        }
    }

    private fun buildAccessorMapProperty(
        name: String,
        type: KSType,
        index: Int
    ): PropertySpec? {
        val valueType = type.arguments.getOrNull(1)?.type?.resolve() ?: return null
        val valueTypeName = valueType.declaration.qualifiedName?.asString() ?: return null
        val nullable = type.isMarkedNullable

        fun mapProp(propType: TypeName, readExpr: String, vararg args: Any): PropertySpec {
            val actualType = if (nullable) propType.copy(nullable = true) else propType
            val getter = if (nullable) {
                FunSpec.getterBuilder().addStatement("return if (map.isNullAt($index)) null else $readExpr", *args).build()
            } else {
                FunSpec.getterBuilder().addStatement("return $readExpr", *args).build()
            }
            return PropertySpec.builder(name, actualType).getter(getter).build()
        }

        return when (valueTypeName) {
            "kotlin.Int" -> mapProp(FLEX_STRING_INT_MAP, "%T(map.getMap($index))", FLEX_STRING_INT_MAP)
            "kotlin.Double" -> mapProp(FLEX_STRING_DOUBLE_MAP, "%T(map.getMap($index))", FLEX_STRING_DOUBLE_MAP)
            "kotlin.String" -> mapProp(FLEX_STRING_STRING_MAP, "%T(map.getMap($index))", FLEX_STRING_STRING_MAP)
            "kotlin.Boolean" -> mapProp(FLEX_STRING_BOOLEAN_MAP, "%T(map.getMap($index))", FLEX_STRING_BOOLEAN_MAP)
            else -> {
                val valueDecl = valueType.declaration
                if (valueDecl is KSClassDeclaration && hasStruct(valueDecl)) {
                    val nestedAccessor = ClassName(
                        valueDecl.packageName.asString(),
                        "${valueDecl.simpleName.asString()}Accessor"
                    )
                    mapProp(FLEX_ACCESSOR_MAP.parameterizedBy(nestedAccessor),
                        "%T(map.getMap($index)) { %T(it) }", FLEX_ACCESSOR_MAP, nestedAccessor)
                } else null
            }
        }
    }

    private fun generateRegistrationFunction(
        generated: List<Pair<ClassName, ClassName>>,
        sourceFile: KSFile?
    ) {
        val builder = FunSpec.builder("registerGeneratedFlexCoders")
        for ((className, coderName) in generated) {
            builder.addStatement("%T.register(%T::class, %T)", FLEX_CODER_REGISTRY, className, coderName)
            // Also register by serial name so the serializer-based API
            // (FlexBuffers.encode(serializer<T>(), value)) uses FlexCoder automatically
            val serialName = "${className.packageName}.${className.simpleName}"
            builder.addStatement("%T.registerBySerialName(%S, %T)", FLEX_CODER_REGISTRY, serialName, coderName)
        }

        val pkg = generated.first().first.packageName
        val fileSpec = FileSpec.builder(pkg, "FlexCoderRegistration")
            .addFunction(builder.build())
            .build()

        val deps = sourceFile?.let { Dependencies(false, it) } ?: Dependencies(false)
        fileSpec.writeTo(codeGenerator, deps)
    }

    private fun hasStruct(decl: KSClassDeclaration): Boolean {
        return decl.annotations.any {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == STRUCT_FQ
        }
    }

    private fun KSType.toSimpleType(): String {
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.String" -> "String"
            "kotlin.Int" -> "Int"
            "kotlin.Long" -> "Long"
            "kotlin.Boolean" -> "Boolean"
            "kotlin.Double" -> "Double"
            "kotlin.Float" -> "Float"
            else -> declaration.simpleName.asString()
        }
    }
}

class FlexCoderProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return FlexCoderProcessor(environment.codeGenerator, environment.logger)
    }
}
