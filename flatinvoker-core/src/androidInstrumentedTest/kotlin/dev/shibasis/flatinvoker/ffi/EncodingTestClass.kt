package dev.shibasis.flatinvoker.ffi

import kotlinx.serialization.Serializable

@Serializable
data class InnerNestedData(
    val innerValue: Double,
    val innerList: List<String>
)

@Serializable
data class NestedData(
    val nestedInt: Int,
    val nestedString: String,
    val innerNestedData: List<InnerNestedData>
)

@Serializable
data class EncodingSimpleCase(
    val mapOfStringToInt: Map<String, Int> = mapOf("one" to 1, "two" to 2),
    val mapOfIntToBoolean: Map<Int, Boolean> = mapOf(1 to true, 2 to false),
    val mutableMapOfStringToList: MutableMap<String, List<Double>> = mutableMapOf("key1" to listOf(1.0, 2.0), "key2" to listOf(3.0, 4.0)),
)

val contextualCase = mapOf(
    1 to "simpleValue1",
    2 to listOf("listValue1", "listValue2"),
    "stringKey1" to mapOf(
        "innerMapKey1" to "innerMapValue1",
        "innerMapKey2" to "innerMapValue2"
    ),
    "stringKey2" to NestedData(
        42, "nestedString", listOf(InnerNestedData(
            innerValue = 104.0,
            innerList = listOf("Inner", "List")
        ))
    ),
    4 to "simpleValue4"
)


@Serializable
data class EncodingComplexCase(
    val booleanField: Boolean = true,
    val byteField: Byte = 1,
    val shortField: Short = 2,
    val intField: Int = 3,
    val longField: Long = 4L,
    val floatField: Float = 5.0f,
    val doubleField: Double = 6.0,
    val charField: Char = 'A',
    val stringField: String = "Hello",
    val byteArrayField: ByteArray = byteArrayOf(1, 2, 3),
    val shortListField: List<Short> = listOf(4, 5, 6),
    val intSetField: Set<Int> = setOf(7, 8, 9),
    val longListField: MutableList<Long> = mutableListOf(10L, 11L, 12L),
    val floatSetField: Set<Float> = setOf(13.0f, 14.0f, 15.0f),
    val doubleListField: List<Double> = listOf(16.0, 17.0, 18.0),
    val charListField: MutableList<Char> = mutableListOf('B', 'C', 'D'),
    val stringSetField: Set<String> = setOf("World", "Kotlin"),
    val listOfLists: List<List<Int>> = listOf(listOf(1, 2), listOf(3, 4)),
    val mapOfStringToInt: Map<String, Int> = mapOf("one" to 1, "two" to 2),
    val mapOfIntToBoolean: Map<Int, Boolean> = mapOf(1 to true, 2 to false),
    val setOfSets: Set<Set<Float>> = setOf(setOf(1.0f, 2.0f), setOf(3.0f, 4.0f)),
    val mutableMapOfStringToList: MutableMap<String, List<Double>> = mutableMapOf("key1" to listOf(1.0, 2.0), "key2" to listOf(3.0, 4.0)),
    val nestedData: NestedData = NestedData(
        nestedInt = 99,
        nestedString = "Nested",
        innerNestedData = listOf(InnerNestedData(
            innerValue = 100.0,
            innerList = listOf("Inner", "List")
        ))
    ),
    val mapOfStringToNestedData: Map<String, NestedData> = mapOf("nested" to NestedData(
        nestedInt = 101,
        nestedString = "Nested",
        innerNestedData = listOf(InnerNestedData(
            innerValue = 102.0,
            innerList = listOf("Inner", "List")
        ))
    ), "nested2" to NestedData(
        nestedInt = 103,
        nestedString = "Nested",
        innerNestedData = listOf(InnerNestedData(
            innerValue = 104.0,
            innerList = listOf("Inner", "List")
        ))
    ))
)


@Serializable
data class EncodingSophisticatedCase(
    val field: EncodingComplexCase = EncodingComplexCase(),
    val arrayOfComplex: Array<EncodingComplexCase> = arrayOf(EncodingComplexCase(), EncodingComplexCase()),
    val listComplex: List<EncodingComplexCase> = listOf(EncodingComplexCase(), EncodingComplexCase()),
    val mapComplex: Map<String, EncodingComplexCase> = mapOf("key1" to EncodingComplexCase(), "key2" to EncodingComplexCase()),
    val mapOfListComplex: Map<String, List<EncodingComplexCase>> = mapOf("key1" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key2" to listOf(EncodingComplexCase(), EncodingComplexCase())),
    val mapOfMapComplex: Map<String, Map<String, EncodingComplexCase>> = mapOf("key1" to mapOf("key1" to EncodingComplexCase(), "key2" to EncodingComplexCase()), "key2" to mapOf("key1" to EncodingComplexCase(), "key2" to EncodingComplexCase())),
    val mapOfMapOfListComplex: Map<String, Map<String, List<EncodingComplexCase>>>
        = mapOf("key1" to mapOf("key1" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key2" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key3" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key4" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key5" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key6" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key7" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key8" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key9" to listOf(EncodingComplexCase(), EncodingComplexCase()), "key10" to listOf(EncodingComplexCase(), EncodingComplexCase()))),
    val setComplex: Set<EncodingComplexCase> = setOf(EncodingComplexCase(), EncodingComplexCase()),
    val setOfListComplex: Set<List<EncodingComplexCase>> = setOf(listOf(EncodingComplexCase(), EncodingComplexCase()), listOf(EncodingComplexCase(), EncodingComplexCase())),
    val setOfMapComplex: Set<Map<String, EncodingComplexCase>> = setOf(mapOf("key1" to EncodingComplexCase(), "key2" to EncodingComplexCase()), mapOf("key3" to EncodingComplexCase(), "key4" to EncodingComplexCase())),
    val setOfSetComplex: Set<Set<EncodingComplexCase>> = setOf(setOf(EncodingComplexCase(), EncodingComplexCase()), setOf(EncodingComplexCase(), EncodingComplexCase())),
)

/*
"{ booleanField: true, byteArrayField: [ 1, 2, 3 ], byteField: 1, charField: 65, charListField: [ 66, 67, 68 ], doubleField: 6.0, doubleListField: [ 16.0, 17.0, 18.0 ], floatField: 5.0, floatSetField: [ 13.0, 14.0, 15.0 ], intField: 3, intSetField: [ 7, 8, 9 ], listOfLists: [ [ 1, 2 ], [ 3, 4 ] ], longField: 4, longListField: [ 10, 11, 12 ], mapOfIntToBoolean: { \"0\": 1, \"1\": true, \"2\": 2, \"3\": false }, mapOfStringToInt: { \"0\": \"one\", \"1\": 1, \"2\": \"two\", \"3\": 2 }, mapOfStringToNestedData: { \"0\": \"nested\", \"1\": { innerNestedData: [ { innerList: [ \"Inner\", \"List\" ], innerValue: 102.0 } ], nestedInt: 101, nestedString: \"Nested\" }, \"2\": \"nested2\", \"3\": { innerNestedData: [ { innerList: [ \"Inner\", \"List\" ], innerValue: 104.0 } ], nestedInt: 103, nestedString: \"Nested\" } }, mutableMapOfStringToList: { \"0\": \"key1\", \"1\": [ 1.0, 2.0 ], \"2\": \"key2\", \"3\": [ 3.0, 4.0 ] }, nestedData: { innerNestedData: [ { innerList: [ \"Inner\", \"List\" ], innerValue: 100.0 } ], nestedInt: 99, nestedString: \"Nested\" }, setOfSets: [ [ 1.0, 2."...
 */