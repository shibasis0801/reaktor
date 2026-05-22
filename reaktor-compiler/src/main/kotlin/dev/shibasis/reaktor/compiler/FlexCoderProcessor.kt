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

        val encodeMethod = buildEncodeMethod(className, sortedProps)
        val decodeMethod = buildDecodeMethod(className, properties, sortedProps)
        val decodeMapMethod = buildDecodeMapMethod(className, properties, sortedProps)

        val coderObject = TypeSpec.objectBuilder(coderName)
            .addSuperinterface(FLEX_CODER.parameterizedBy(className))
            .addFunction(encodeMethod)
            .addFunction(decodeMethod)
            .addFunction(decodeMapMethod)
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
                    .addStatement("return %T(%M(%T(this, 0)).toMap())", accessorName, GET_ROOT, ARRAY_READ_BUFFER)
                    .build()
            )
            .build()

        val deps = classDecl.containingFile?.let { Dependencies(false, it) }
            ?: Dependencies(false)
        fileSpec.writeTo(codeGenerator, deps)

        logger.info("FlexCoderProcessor: generated ${coderName.simpleName} + ${accessorName.simpleName} for ${className.simpleName}")
        return className to coderName
    }

    private fun buildEncodeMethod(
        className: ClassName,
        sortedProps: List<KSPropertyDeclaration>
    ): FunSpec {
        val builder = FunSpec.builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("builder", FLEX_BUFFERS_BUILDER)
            .addParameter("value", className)
            .addParameter("key", String::class.asTypeName().copy(nullable = true))

        builder.addStatement("val m = builder.startMap()")

        for (prop in sortedProps) {
            val name = prop.simpleName.asString()
            val type = prop.type.resolve()
            generateEncodeField(builder, name, type, "value.$name")
        }

        builder.addStatement("builder.endMap(m, key, presorted = true)")
        return builder.build()
    }

    private fun generateEncodeField(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        accessor: String
    ) {
        val typeName = type.declaration.qualifiedName?.asString() ?: return
        val nullable = type.isMarkedNullable

        if (nullable) {
            builder.beginControlFlow("if ($accessor != null)")
        }

        when (typeName) {
            "kotlin.Boolean" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.Byte" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.Short" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.Int" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.Long" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.Float" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.Double" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.Char" -> builder.addStatement("builder.set(%S, $accessor.code)", fieldName)
            "kotlin.String" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.ByteArray" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.ShortArray" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.IntArray" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.LongArray" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.FloatArray" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.DoubleArray" -> builder.addStatement("builder.set(%S, $accessor)", fieldName)
            "kotlin.collections.List", "kotlin.collections.MutableList",
            "kotlin.collections.Set", "kotlin.collections.MutableSet",
            "kotlin.collections.ArrayList" -> {
                generateEncodeCollection(builder, fieldName, type, accessor)
            }
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "kotlin.collections.LinkedHashMap", "kotlin.collections.HashMap" -> {
                generateEncodeMap(builder, fieldName, type, accessor)
            }
            else -> {
                // Nested class — delegate to its FlexCoder
                val decl = type.declaration
                if (decl is KSClassDeclaration && hasStruct(decl)) {
                    val coderName = ClassName(
                        decl.packageName.asString(),
                        "${decl.simpleName.asString()}FlexCoder"
                    )
                    builder.addStatement("%T.encode(builder, $accessor, %S)", coderName, fieldName)
                } else {
                    builder.addComment("TODO: unsupported type $typeName for field $fieldName")
                }
            }
        }

        if (nullable) {
            builder.nextControlFlow("else")
            builder.addStatement("builder.putNull(%S)", fieldName)
            builder.endControlFlow()
        }
    }

    private fun generateEncodeCollection(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        accessor: String
    ) {
        val elementType = type.arguments.firstOrNull()?.type?.resolve() ?: return
        val elemTypeName = elementType.declaration.qualifiedName?.asString() ?: return
        val collTypeName = type.declaration.qualifiedName?.asString() ?: return
        val isSet = collTypeName.contains("Set")
        val listAccessor = if (isSet) "$accessor.toList()" else accessor

        // Try typed vector for primitive collections — more compact wire format
        when (elemTypeName) {
            "kotlin.Int" -> {
                builder.addStatement("builder.setIntList(%S, $listAccessor)", fieldName)
                return
            }
            "kotlin.Long" -> {
                builder.addStatement("builder.setLongList(%S, $listAccessor)", fieldName)
                return
            }
            "kotlin.Float" -> {
                builder.addStatement("builder.setFloatList(%S, $listAccessor)", fieldName)
                return
            }
            "kotlin.Double" -> {
                builder.addStatement("builder.setDoubleList(%S, $listAccessor)", fieldName)
                return
            }
            "kotlin.Short" -> {
                builder.addStatement("builder.setIntList(%S, $accessor.map { it.toInt() })", fieldName)
                return
            }
            "kotlin.Char" -> {
                builder.addStatement("builder.setIntList(%S, $accessor.map { it.code })", fieldName)
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
                    builder.addStatement("%T.encode(builder, elem, null)", coderName)
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
        builder.addStatement("builder.endVector(%S, v_${fieldName})", fieldName)
    }

    private fun generateEncodeMap(
        builder: FunSpec.Builder,
        fieldName: String,
        type: KSType,
        accessor: String
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
        builder.addStatement("builder.endMap(m_${fieldName}, %S)", fieldName)
    }

    private fun buildDecodeMethod(
        className: ClassName,
        originalProps: List<KSPropertyDeclaration>,
        sortedProps: List<KSPropertyDeclaration>
    ): FunSpec {
        val builder = FunSpec.builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("ref", REFERENCE)
            .returns(className)

        builder.addStatement("val map = ref.toMap()")

        // Decode fields in alphabetical order using O(1) index access
        for ((index, prop) in sortedProps.withIndex()) {
            val name = prop.simpleName.asString()
            val type = prop.type.resolve()
            generateDecodeField(builder, name, type, index)
        }

        // Build constructor call in original declaration order
        val constructorArgs = originalProps.joinToString(",\n    ") { prop ->
            val name = prop.simpleName.asString()
            "$name = _$name"
        }
        builder.addStatement("return %T(\n    $constructorArgs\n)", className)

        return builder.build()
    }

    private fun buildDecodeMapMethod(
        className: ClassName,
        originalProps: List<KSPropertyDeclaration>,
        sortedProps: List<KSPropertyDeclaration>
    ): FunSpec {
        val builder = FunSpec.builder("decodeMap")
            .addParameter("map", FLEX_MAP)
            .returns(className)

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
        val typeName = type.declaration.qualifiedName?.asString() ?: return
        val nullable = type.isMarkedNullable
        val mapAccess = "map[$index]"

        when (typeName) {
            "kotlin.Boolean" -> builder.addStatement("val _$fieldName = map.getBoolean($index)")
            "kotlin.Byte" -> builder.addStatement("val _$fieldName = map.getInt($index).toByte()")
            "kotlin.Short" -> builder.addStatement("val _$fieldName = map.getInt($index).toShort()")
            "kotlin.Int" -> builder.addStatement("val _$fieldName = map.getInt($index)")
            "kotlin.Long" -> builder.addStatement("val _$fieldName = map.getLong($index)")
            "kotlin.Float" -> builder.addStatement("val _$fieldName = map.getFloat($index)")
            "kotlin.Double" -> builder.addStatement("val _$fieldName = map.getDouble($index)")
            "kotlin.Char" -> builder.addStatement("val _$fieldName = map.getInt($index).toChar()")
            "kotlin.String" -> builder.addStatement("val _$fieldName = map.getString($index)")
            "kotlin.ByteArray" -> builder.addStatement("val _$fieldName = $mapAccess.toBlob().toByteArray()")
            "kotlin.IntArray" -> {
                builder.addStatement("val _${fieldName}_vec = map.getVector($index)")
                builder.addStatement("val _$fieldName = IntArray(_${fieldName}_vec.size) { _${fieldName}_vec.readInt(it) }")
            }
            "kotlin.LongArray" -> {
                builder.addStatement("val _${fieldName}_vec = map.getVector($index)")
                builder.addStatement("val _$fieldName = LongArray(_${fieldName}_vec.size) { _${fieldName}_vec.readLong(it) }")
            }
            "kotlin.FloatArray" -> {
                builder.addStatement("val _${fieldName}_vec = map.getVector($index)")
                builder.addStatement("val _$fieldName = FloatArray(_${fieldName}_vec.size) { _${fieldName}_vec.readDouble(it).toFloat() }")
            }
            "kotlin.DoubleArray" -> {
                builder.addStatement("val _${fieldName}_vec = map.getVector($index)")
                builder.addStatement("val _$fieldName = DoubleArray(_${fieldName}_vec.size) { _${fieldName}_vec.readDouble(it) }")
            }
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.ArrayList" -> {
                generateDecodeList(builder, fieldName, type, index)
            }
            "kotlin.collections.Set", "kotlin.collections.MutableSet", "kotlin.collections.LinkedHashSet" -> {
                generateDecodeSet(builder, fieldName, type, index)
            }
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "kotlin.collections.LinkedHashMap", "kotlin.collections.HashMap" -> {
                generateDecodeMap(builder, fieldName, type, index)
            }
            else -> {
                val decl = type.declaration
                if (decl is KSClassDeclaration && hasStruct(decl)) {
                    val coderName = ClassName(
                        decl.packageName.asString(),
                        "${decl.simpleName.asString()}FlexCoder"
                    )
                    builder.addStatement("val _$fieldName = %T.decodeMap(map.getMap($index))", coderName)
                } else {
                    builder.addComment("TODO: unsupported type $typeName for field $fieldName")
                    builder.addStatement("val _$fieldName: Nothing = error(%S)", "unsupported type $typeName")
                }
            }
        }
    }

    private fun generateDecodeList(builder: FunSpec.Builder, fieldName: String, type: KSType, index: Int) {
        val elemType = type.arguments.firstOrNull()?.type?.resolve() ?: return
        val elemName = elemType.declaration.qualifiedName?.asString() ?: return

        builder.addStatement("val _${fieldName}_vec = map.getVector($index)")

        when (elemName) {
            "kotlin.Int" -> builder.addStatement("val _$fieldName = ArrayList<Int>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(_${fieldName}_vec.readInt(i)) }")
            "kotlin.Long" -> builder.addStatement("val _$fieldName = ArrayList<Long>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(_${fieldName}_vec.readLong(i)) }")
            "kotlin.Float" -> builder.addStatement("val _$fieldName = ArrayList<Float>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(_${fieldName}_vec.readDouble(i).toFloat()) }")
            "kotlin.Double" -> builder.addStatement("val _$fieldName = ArrayList<Double>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(_${fieldName}_vec.readDouble(i)) }")
            "kotlin.String" -> builder.addStatement("val _$fieldName = ArrayList<String>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(_${fieldName}_vec.readString(i)) }")
            "kotlin.Short" -> builder.addStatement("val _$fieldName = ArrayList<Short>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(_${fieldName}_vec.readInt(i).toShort()) }")
            "kotlin.Char" -> builder.addStatement("val _$fieldName = ArrayList<Char>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(_${fieldName}_vec.readInt(i).toChar()) }")
            else -> {
                val elemDecl = elemType.declaration
                if (elemDecl is KSClassDeclaration && hasStruct(elemDecl)) {
                    val coderName = ClassName(elemDecl.packageName.asString(), "${elemDecl.simpleName.asString()}FlexCoder")
                    builder.addStatement("val _$fieldName = ArrayList<${elemDecl.simpleName.asString()}>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) list.add(%T.decodeMap(_${fieldName}_vec.readMap(i))) }", coderName)
                } else if (elemName.startsWith("kotlin.collections.List")) {
                    val innerType = elemType.arguments.firstOrNull()?.type?.resolve()
                    val innerName = innerType?.declaration?.qualifiedName?.asString()
                    when (innerName) {
                        "kotlin.Int" -> builder.addStatement("val _$fieldName = ArrayList<List<Int>>(_${fieldName}_vec.size).also { list -> for (i in 0 until _${fieldName}_vec.size) { val inner = _${fieldName}_vec.readVector(i); list.add(ArrayList<Int>(inner.size).also { il -> for (j in 0 until inner.size) il.add(inner.readInt(j)) }) } }")
                        else -> builder.addComment("TODO: unsupported List<List<$innerName>> for $fieldName")
                    }
                } else {
                    builder.addComment("TODO: unsupported list element $elemName for $fieldName")
                    builder.addStatement("val _$fieldName: Nothing = error(%S)", "unsupported")
                }
            }
        }
    }

    private fun generateDecodeSet(builder: FunSpec.Builder, fieldName: String, type: KSType, index: Int) {
        val elemType = type.arguments.firstOrNull()?.type?.resolve() ?: return
        val elemName = elemType.declaration.qualifiedName?.asString() ?: return

        builder.addStatement("val _${fieldName}_vec = map.getVector($index)")

        when (elemName) {
            "kotlin.Int" -> builder.addStatement("val _$fieldName = LinkedHashSet<Int>(_${fieldName}_vec.size).also { s -> for (i in 0 until _${fieldName}_vec.size) s.add(_${fieldName}_vec.readInt(i)) }")
            "kotlin.Float" -> builder.addStatement("val _$fieldName = LinkedHashSet<Float>(_${fieldName}_vec.size).also { s -> for (i in 0 until _${fieldName}_vec.size) s.add(_${fieldName}_vec.readDouble(i).toFloat()) }")
            "kotlin.String" -> builder.addStatement("val _$fieldName = LinkedHashSet<String>(_${fieldName}_vec.size).also { s -> for (i in 0 until _${fieldName}_vec.size) s.add(_${fieldName}_vec.readString(i)) }")
            else -> {
                if (elemName.startsWith("kotlin.collections.Set")) {
                    val innerType = elemType.arguments.firstOrNull()?.type?.resolve()
                    val innerName = innerType?.declaration?.qualifiedName?.asString()
                    when (innerName) {
                        "kotlin.Float" -> builder.addStatement("val _$fieldName = LinkedHashSet<Set<Float>>(_${fieldName}_vec.size).also { s -> for (i in 0 until _${fieldName}_vec.size) { val inner = _${fieldName}_vec.readVector(i); s.add(LinkedHashSet<Float>(inner.size).also { is2 -> for (j in 0 until inner.size) is2.add(inner.readDouble(j).toFloat()) }) } }")
                        else -> builder.addComment("TODO: unsupported Set<Set<$innerName>>")
                    }
                } else {
                    builder.addComment("TODO: unsupported set element $elemName for $fieldName")
                    builder.addStatement("val _$fieldName: Nothing = error(%S)", "unsupported")
                }
            }
        }
    }

    private fun generateDecodeMap(builder: FunSpec.Builder, fieldName: String, type: KSType, index: Int) {
        val keyType = type.arguments.getOrNull(0)?.type?.resolve() ?: return
        val valueType = type.arguments.getOrNull(1)?.type?.resolve() ?: return
        val keyTypeName = keyType.declaration.qualifiedName?.asString() ?: return
        val valueTypeName = valueType.declaration.qualifiedName?.asString() ?: return

        builder.addStatement("val _${fieldName}_map = map.getMap($index)")

        val keyConvert = when (keyTypeName) {
            "kotlin.String" -> "_${fieldName}_map.keyAsString(i)"
            "kotlin.Int" -> "_${fieldName}_map.keyAsString(i).toInt()"
            "kotlin.Long" -> "_${fieldName}_map.keyAsString(i).toLong()"
            else -> "_${fieldName}_map.keyAsString(i)"
        }

        when (valueTypeName) {
            "kotlin.Boolean" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, Boolean>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) m[$keyConvert] = _${fieldName}_map.getBoolean(i) }")
            "kotlin.Int" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, Int>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) m[$keyConvert] = _${fieldName}_map.getInt(i) }")
            "kotlin.Long" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, Long>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) m[$keyConvert] = _${fieldName}_map.getLong(i) }")
            "kotlin.Float" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, Float>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) m[$keyConvert] = _${fieldName}_map.getFloat(i) }")
            "kotlin.String" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, String>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) m[$keyConvert] = _${fieldName}_map.getString(i) }")
            "kotlin.Double" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, Double>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) m[$keyConvert] = _${fieldName}_map.getDouble(i) }")
            else -> {
                val valueDecl = valueType.declaration
                if (valueDecl is KSClassDeclaration && hasStruct(valueDecl)) {
                    val coderName = ClassName(valueDecl.packageName.asString(), "${valueDecl.simpleName.asString()}FlexCoder")
                    builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, ${valueDecl.simpleName.asString()}>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) m[$keyConvert] = %T.decodeMap(_${fieldName}_map.getMap(i)) }", coderName)
                } else if (valueTypeName.startsWith("kotlin.collections.List")) {
                    val innerType = valueType.arguments.firstOrNull()?.type?.resolve()
                    val innerName = innerType?.declaration?.qualifiedName?.asString()
                    when (innerName) {
                        "kotlin.Double" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, List<Double>>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) { val vec = _${fieldName}_map.getVector(i); m[$keyConvert] = ArrayList<Double>(vec.size).also { list -> for (j in 0 until vec.size) list.add(vec.readDouble(j)) } } }")
                        "kotlin.Int" -> builder.addStatement("val _$fieldName = LinkedHashMap<${keyType.toSimpleType()}, List<Int>>(_${fieldName}_map.size).also { m -> for (i in 0 until _${fieldName}_map.size) { val vec = _${fieldName}_map.getVector(i); m[$keyConvert] = ArrayList<Int>(vec.size).also { list -> for (j in 0 until vec.size) list.add(vec.readInt(j)) } } }")
                        else -> {
                            builder.addComment("TODO: unsupported Map value List<$innerName>")
                            builder.addStatement("val _$fieldName: Nothing = error(%S)", "unsupported")
                        }
                    }
                } else {
                    builder.addComment("TODO: unsupported map value $valueTypeName for $fieldName")
                    builder.addStatement("val _$fieldName: Nothing = error(%S)", "unsupported")
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

        // toDataClass() — materializes the full data class using index-based reads
        val toDataClassMethod = FunSpec.builder("toDataClass")
            .returns(className)

        for ((index, prop) in sortedProps.withIndex()) {
            val name = prop.simpleName.asString()
            val type = prop.type.resolve()
            generateDecodeField(toDataClassMethod, name, type, index)
        }

        val constructorArgs = originalProps.joinToString(",\n    ") { prop ->
            val name = prop.simpleName.asString()
            "$name = _$name"
        }
        toDataClassMethod.addStatement("return %T(\n    $constructorArgs\n)", className)
        builder.addFunction(toDataClassMethod.build())

        return builder.build()
    }

    private fun buildAccessorProperty(
        name: String,
        type: KSType,
        index: Int
    ): PropertySpec? {
        val typeName = type.declaration.qualifiedName?.asString() ?: return null

        return when (typeName) {
            "kotlin.Boolean" -> PropertySpec.builder(name, Boolean::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getBoolean($index)").build())
                .build()
            "kotlin.Byte" -> PropertySpec.builder(name, Byte::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getInt($index).toByte()").build())
                .build()
            "kotlin.Short" -> PropertySpec.builder(name, Short::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getInt($index).toShort()").build())
                .build()
            "kotlin.Int" -> PropertySpec.builder(name, Int::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getInt($index)").build())
                .build()
            "kotlin.Long" -> PropertySpec.builder(name, Long::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getLong($index)").build())
                .build()
            "kotlin.Float" -> PropertySpec.builder(name, Float::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getFloat($index)").build())
                .build()
            "kotlin.Double" -> PropertySpec.builder(name, Double::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getDouble($index)").build())
                .build()
            "kotlin.Char" -> PropertySpec.builder(name, Char::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getInt($index).toChar()").build())
                .build()
            "kotlin.String" -> PropertySpec.builder(name, String::class)
                .getter(FunSpec.getterBuilder().addStatement("return map.getString($index)").build())
                .build()
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
                    PropertySpec.builder(name, nestedAccessor)
                        .getter(FunSpec.getterBuilder()
                            .addStatement("return %T(map.getMap($index))", nestedAccessor)
                            .build())
                        .build()
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

        return when (elemName) {
            "kotlin.Int" -> PropertySpec.builder(name, FLEX_INT_LIST)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getVector($index))", FLEX_INT_LIST).build())
                .build()
            "kotlin.Long" -> PropertySpec.builder(name, FLEX_LONG_LIST)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getVector($index))", FLEX_LONG_LIST).build())
                .build()
            "kotlin.Double" -> PropertySpec.builder(name, FLEX_DOUBLE_LIST)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getVector($index))", FLEX_DOUBLE_LIST).build())
                .build()
            "kotlin.Float" -> PropertySpec.builder(name, FLEX_FLOAT_LIST)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getVector($index))", FLEX_FLOAT_LIST).build())
                .build()
            "kotlin.String" -> PropertySpec.builder(name, FLEX_STRING_LIST)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getVector($index))", FLEX_STRING_LIST).build())
                .build()
            "kotlin.Boolean" -> PropertySpec.builder(name, FLEX_BOOLEAN_LIST)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getVector($index))", FLEX_BOOLEAN_LIST).build())
                .build()
            else -> {
                val elemDecl = elemType.declaration
                if (elemDecl is KSClassDeclaration && hasStruct(elemDecl)) {
                    val nestedAccessor = ClassName(
                        elemDecl.packageName.asString(),
                        "${elemDecl.simpleName.asString()}Accessor"
                    )
                    PropertySpec.builder(name, FLEX_ACCESSOR_LIST.parameterizedBy(nestedAccessor))
                        .getter(FunSpec.getterBuilder()
                            .addStatement("return %T(map.getVector($index)) { %T(it) }", FLEX_ACCESSOR_LIST, nestedAccessor)
                            .build())
                        .build()
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

        return when (valueTypeName) {
            "kotlin.Int" -> PropertySpec.builder(name, FLEX_STRING_INT_MAP)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getMap($index))", FLEX_STRING_INT_MAP).build())
                .build()
            "kotlin.Double" -> PropertySpec.builder(name, FLEX_STRING_DOUBLE_MAP)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getMap($index))", FLEX_STRING_DOUBLE_MAP).build())
                .build()
            "kotlin.String" -> PropertySpec.builder(name, FLEX_STRING_STRING_MAP)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getMap($index))", FLEX_STRING_STRING_MAP).build())
                .build()
            "kotlin.Boolean" -> PropertySpec.builder(name, FLEX_STRING_BOOLEAN_MAP)
                .getter(FunSpec.getterBuilder().addStatement("return %T(map.getMap($index))", FLEX_STRING_BOOLEAN_MAP).build())
                .build()
            else -> {
                val valueDecl = valueType.declaration
                if (valueDecl is KSClassDeclaration && hasStruct(valueDecl)) {
                    val nestedAccessor = ClassName(
                        valueDecl.packageName.asString(),
                        "${valueDecl.simpleName.asString()}Accessor"
                    )
                    PropertySpec.builder(name, FLEX_ACCESSOR_MAP.parameterizedBy(nestedAccessor))
                        .getter(FunSpec.getterBuilder()
                            .addStatement("return %T(map.getMap($index)) { %T(it) }", FLEX_ACCESSOR_MAP, nestedAccessor)
                            .build())
                        .build()
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
