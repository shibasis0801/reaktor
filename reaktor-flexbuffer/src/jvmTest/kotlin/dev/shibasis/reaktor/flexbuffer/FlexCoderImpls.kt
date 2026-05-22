@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.flatbuffers.FlexBuffersBuilder
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Map
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Reference
import dev.shibasis.reaktor.core.*
import dev.shibasis.reaktor.flexbuffer.core.FlexCoder
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry

// ─── FlatPrimitives ───
// Alphabetical: b=0, by=1, c=2, d=3, f=4, i=5, l=6, s=7, sh=8

object FlatPrimitivesCoder : FlexCoder<FlexBufferBenchmark.FlatPrimitives> {
    override fun encode(builder: FlexBuffersBuilder, value: FlexBufferBenchmark.FlatPrimitives, key: String?) {
        val m = builder.startMap()
        builder.set("b", value.b)
        builder.set("by", value.by)
        builder.set("c", value.c.code)
        builder.set("d", value.d)
        builder.set("f", value.f)
        builder.set("i", value.i)
        builder.set("l", value.l)
        builder.set("s", value.s)
        builder.set("sh", value.sh)
        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): FlexBufferBenchmark.FlatPrimitives {
        val map = ref.toMap()
        return FlexBufferBenchmark.FlatPrimitives(
            b = map.getBoolean(0),
            by = map.getInt(1).toByte(),
            c = map.getInt(2).toChar(),
            d = map.getDouble(3),
            f = map.getFloat(4),
            i = map.getInt(5),
            l = map.getLong(6),
            s = map.getString(7),
            sh = map.getInt(8).toShort()
        )
    }
}

// ─── CollectionHeavy ───
// Alphabetical: doubleList=0, intList=1, intMap=2, nestedList=3, stringList=4, stringMap=5

object CollectionHeavyCoder : FlexCoder<FlexBufferBenchmark.CollectionHeavy> {
    override fun encode(builder: FlexBuffersBuilder, value: FlexBufferBenchmark.CollectionHeavy, key: String?) {
        val m = builder.startMap()

        val vDbl = builder.startVector()
        for (d in value.doubleList) builder.put(d)
        builder.endVector("doubleList", vDbl)

        builder.setIntList("intList", value.intList)

        val mInt = builder.startMap()
        for ((k, v) in value.intMap.entries.sortedBy { it.key }) builder.set(k, v)
        builder.endMap(mInt, "intMap")

        val vNested = builder.startVector()
        for (inner in value.nestedList) builder.setIntList(null, inner)
        builder.endVector("nestedList", vNested)

        val vStr = builder.startVector()
        for (s in value.stringList) builder.put(s)
        builder.endVector("stringList", vStr)

        val mStr = builder.startMap()
        for ((k, v) in value.stringMap.entries.sortedBy { it.key }) builder.set(k, v)
        builder.endMap(mStr, "stringMap")

        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): FlexBufferBenchmark.CollectionHeavy {
        val map = ref.toMap()

        val doubleListVec = map.getVector(0)
        val doubleList = ArrayList<Double>(doubleListVec.size)
        for (i in 0 until doubleListVec.size) doubleList.add(doubleListVec.readDouble(i))

        val intListVec = map.getVector(1)
        val intList = ArrayList<Int>(intListVec.size)
        for (i in 0 until intListVec.size) intList.add(intListVec.readInt(i))

        val intMapRef = map.getMap(2)
        val intMap = LinkedHashMap<String, Int>(intMapRef.size)
        for (i in 0 until intMapRef.size) intMap[intMapRef.keyAsString(i)] = intMapRef.getInt(i)

        val nestedListVec = map.getVector(3)
        val nestedList = ArrayList<List<Int>>(nestedListVec.size)
        for (i in 0 until nestedListVec.size) {
            val inner = nestedListVec.readVector(i)
            val innerList = ArrayList<Int>(inner.size)
            for (j in 0 until inner.size) innerList.add(inner.readInt(j))
            nestedList.add(innerList)
        }

        val stringListVec = map.getVector(4)
        val stringList = ArrayList<String>(stringListVec.size)
        for (i in 0 until stringListVec.size) stringList.add(stringListVec.readString(i))

        val stringMapRef = map.getMap(5)
        val stringMap = LinkedHashMap<String, String>(stringMapRef.size)
        for (i in 0 until stringMapRef.size) stringMap[stringMapRef.keyAsString(i)] = stringMapRef.getString(i)

        return FlexBufferBenchmark.CollectionHeavy(
            intList = intList,
            stringList = stringList,
            doubleList = doubleList,
            nestedList = nestedList,
            stringMap = stringMap,
            intMap = intMap
        )
    }
}

// ─── DeeplyNested ───
// Level3: data=0, items=1
// Level2: level3=0, value=1
// Level1: level2=0, name=1
// DeeplyNested: level1=0

object Level3Coder : FlexCoder<FlexBufferBenchmark.Level3> {
    override fun encode(builder: FlexBuffersBuilder, value: FlexBufferBenchmark.Level3, key: String?) {
        val m = builder.startMap()
        val mData = builder.startMap()
        for ((k, v) in value.data.entries.sortedBy { it.key }) builder.set(k, v)
        builder.endMap(mData, "data")
        val vItems = builder.startVector()
        for (s in value.items) builder.put(s)
        builder.endVector("items", vItems)
        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): FlexBufferBenchmark.Level3 = decodeMap(ref.toMap())

    fun decodeMap(map: Map): FlexBufferBenchmark.Level3 {
        val dataMap = map.getMap(0)
        val data = LinkedHashMap<String, Double>(dataMap.size)
        for (i in 0 until dataMap.size) data[dataMap.keyAsString(i)] = dataMap.getDouble(i)

        val itemsVec = map.getVector(1)
        val items = ArrayList<String>(itemsVec.size)
        for (i in 0 until itemsVec.size) items.add(itemsVec.readString(i))

        return FlexBufferBenchmark.Level3(items = items, data = data)
    }
}

object Level2Coder : FlexCoder<FlexBufferBenchmark.Level2> {
    override fun encode(builder: FlexBuffersBuilder, value: FlexBufferBenchmark.Level2, key: String?) {
        val m = builder.startMap()
        Level3Coder.encode(builder, value.level3, "level3")
        builder.set("value", value.value)
        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): FlexBufferBenchmark.Level2 = decodeMap(ref.toMap())

    fun decodeMap(map: Map): FlexBufferBenchmark.Level2 {
        return FlexBufferBenchmark.Level2(
            level3 = Level3Coder.decodeMap(map.getMap(0)),
            value = map.getInt(1)
        )
    }
}

object Level1Coder : FlexCoder<FlexBufferBenchmark.Level1> {
    override fun encode(builder: FlexBuffersBuilder, value: FlexBufferBenchmark.Level1, key: String?) {
        val m = builder.startMap()
        val vL2 = builder.startVector()
        for (l2 in value.level2) Level2Coder.encode(builder, l2, null)
        builder.endVector("level2", vL2)
        builder.set("name", value.name)
        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): FlexBufferBenchmark.Level1 = decodeMap(ref.toMap())

    fun decodeMap(map: Map): FlexBufferBenchmark.Level1 {
        val level2Vec = map.getVector(0)
        val level2 = ArrayList<FlexBufferBenchmark.Level2>(level2Vec.size)
        for (i in 0 until level2Vec.size) level2.add(Level2Coder.decodeMap(level2Vec.readMap(i)))
        return FlexBufferBenchmark.Level1(
            level2 = level2,
            name = map.getString(1)
        )
    }
}

object DeeplyNestedCoder : FlexCoder<FlexBufferBenchmark.DeeplyNested> {
    override fun encode(builder: FlexBuffersBuilder, value: FlexBufferBenchmark.DeeplyNested, key: String?) {
        val m = builder.startMap()
        Level1Coder.encode(builder, value.level1, "level1")
        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): FlexBufferBenchmark.DeeplyNested {
        val map = ref.toMap()
        return FlexBufferBenchmark.DeeplyNested(
            level1 = Level1Coder.decodeMap(map.getMap(0))
        )
    }
}

// ─── EncodingComplexCase ───
// Alphabetical indices:
// booleanField=0, byteArrayField=1, byteField=2, charField=3, charListField=4,
// doubleField=5, doubleListField=6, floatField=7, floatSetField=8, intField=9,
// intSetField=10, listOfLists=11, longField=12, longListField=13,
// mapOfIntToBoolean=14, mapOfStringToInt=15, mapOfStringToNestedData=16,
// mutableMapOfStringToList=17, nestedData=18, setOfSets=19,
// shortField=20, shortListField=21, stringField=22, stringSetField=23

object InnerNestedDataCoder : FlexCoder<InnerNestedData> {
    // innerList=0, innerValue=1
    override fun encode(builder: FlexBuffersBuilder, value: InnerNestedData, key: String?) {
        val m = builder.startMap()
        val vList = builder.startVector()
        for (s in value.innerList) builder.put(s)
        builder.endVector("innerList", vList)
        builder.set("innerValue", value.innerValue)
        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): InnerNestedData = decodeMap(ref.toMap())

    fun decodeMap(map: Map): InnerNestedData {
        val listVec = map.getVector(0)
        val list = ArrayList<String>(listVec.size)
        for (i in 0 until listVec.size) list.add(listVec.readString(i))
        return InnerNestedData(
            innerList = list,
            innerValue = map.getDouble(1)
        )
    }
}

object NestedDataCoder : FlexCoder<NestedData> {
    // innerNestedData=0, nestedInt=1, nestedString=2
    override fun encode(builder: FlexBuffersBuilder, value: NestedData, key: String?) {
        val m = builder.startMap()
        val vInner = builder.startVector()
        for (inner in value.innerNestedData) InnerNestedDataCoder.encode(builder, inner, null)
        builder.endVector("innerNestedData", vInner)
        builder.set("nestedInt", value.nestedInt)
        builder.set("nestedString", value.nestedString)
        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): NestedData = decodeMap(ref.toMap())

    fun decodeMap(map: Map): NestedData {
        val innerVec = map.getVector(0)
        val innerList = ArrayList<InnerNestedData>(innerVec.size)
        for (i in 0 until innerVec.size) innerList.add(InnerNestedDataCoder.decodeMap(innerVec.readMap(i)))
        return NestedData(
            innerNestedData = innerList,
            nestedInt = map.getInt(1),
            nestedString = map.getString(2)
        )
    }
}

object EncodingComplexCaseCoder : FlexCoder<EncodingComplexCase> {
    override fun encode(builder: FlexBuffersBuilder, value: EncodingComplexCase, key: String?) {
        val m = builder.startMap()

        builder.set("booleanField", value.booleanField)
        builder.set("byteArrayField", value.byteArrayField)
        builder.set("byteField", value.byteField)
        builder.set("charField", value.charField.code)

        builder.set("charListField", IntArray(value.charListField.size) { value.charListField[it].code })

        builder.set("doubleField", value.doubleField)
        builder.setDoubleList("doubleListField", value.doubleListField)
        builder.set("floatField", value.floatField)
        builder.setFloatList("floatSetField", value.floatSetField.toList())
        builder.set("intField", value.intField)
        builder.setIntList("intSetField", value.intSetField.toList())

        val vLL = builder.startVector()
        for (inner in value.listOfLists) builder.setIntList(null, inner)
        builder.endVector("listOfLists", vLL)

        builder.set("longField", value.longField)
        builder.setLongList("longListField", value.longListField)

        val mIB = builder.startMap()
        for ((k, v) in value.mapOfIntToBoolean.entries.sortedBy { it.key.toString() }) {
            builder.set(k.toString(), v)
        }
        builder.endMap(mIB, "mapOfIntToBoolean")

        val mSI = builder.startMap()
        for ((k, v) in value.mapOfStringToInt.entries.sortedBy { it.key }) builder.set(k, v)
        builder.endMap(mSI, "mapOfStringToInt")

        val mND = builder.startMap()
        for ((k, v) in value.mapOfStringToNestedData.entries.sortedBy { it.key }) {
            NestedDataCoder.encode(builder, v, k)
        }
        builder.endMap(mND, "mapOfStringToNestedData")

        val mSL = builder.startMap()
        for ((k, v) in value.mutableMapOfStringToList.entries.sortedBy { it.key }) {
            builder.setDoubleList(k, v)
        }
        builder.endMap(mSL, "mutableMapOfStringToList")

        NestedDataCoder.encode(builder, value.nestedData, "nestedData")

        val vSS = builder.startVector()
        for (s in value.setOfSets) builder.setFloatList(null, s.toList())
        builder.endVector("setOfSets", vSS)

        builder.set("shortField", value.shortField)
        builder.setIntList("shortListField", value.shortListField.map { it.toInt() })
        builder.set("stringField", value.stringField)

        val vSF = builder.startVector()
        for (s in value.stringSetField) builder.put(s)
        builder.endVector("stringSetField", vSF)

        builder.endMap(m, key, presorted = true)
    }

    override fun decode(ref: Reference): EncodingComplexCase {
        val map = ref.toMap()

        // charListField (index 4)
        val charVec = map.getVector(4)
        val charList = ArrayList<Char>(charVec.size)
        for (i in 0 until charVec.size) charList.add(charVec.readInt(i).toChar())

        // doubleListField (index 6)
        val doubleVec = map.getVector(6)
        val doubleList = ArrayList<Double>(doubleVec.size)
        for (i in 0 until doubleVec.size) doubleList.add(doubleVec.readDouble(i))

        // floatSetField (index 8)
        val floatSetVec = map.getVector(8)
        val floatSet = LinkedHashSet<Float>(floatSetVec.size)
        for (i in 0 until floatSetVec.size) floatSet.add(floatSetVec.readDouble(i).toFloat())

        // intSetField (index 10)
        val intSetVec = map.getVector(10)
        val intSet = LinkedHashSet<Int>(intSetVec.size)
        for (i in 0 until intSetVec.size) intSet.add(intSetVec.readInt(i))

        // listOfLists (index 11)
        val llVec = map.getVector(11)
        val listOfLists = ArrayList<List<Int>>(llVec.size)
        for (i in 0 until llVec.size) {
            val inner = llVec.readVector(i)
            val innerList = ArrayList<Int>(inner.size)
            for (j in 0 until inner.size) innerList.add(inner.readInt(j))
            listOfLists.add(innerList)
        }

        // longListField (index 13)
        val longVec = map.getVector(13)
        val longList = ArrayList<Long>(longVec.size)
        for (i in 0 until longVec.size) longList.add(longVec.readLong(i))

        // mapOfIntToBoolean (index 14)
        val mibRef = map.getMap(14)
        val mapOfIntToBoolean = LinkedHashMap<Int, Boolean>(mibRef.size)
        for (i in 0 until mibRef.size) mapOfIntToBoolean[mibRef.keyAsString(i).toInt()] = mibRef.getBoolean(i)

        // mapOfStringToInt (index 15)
        val msiRef = map.getMap(15)
        val mapOfStringToInt = LinkedHashMap<String, Int>(msiRef.size)
        for (i in 0 until msiRef.size) mapOfStringToInt[msiRef.keyAsString(i)] = msiRef.getInt(i)

        // mapOfStringToNestedData (index 16)
        val mndRef = map.getMap(16)
        val mapOfStringToNestedData = LinkedHashMap<String, NestedData>(mndRef.size)
        for (i in 0 until mndRef.size) mapOfStringToNestedData[mndRef.keyAsString(i)] = NestedDataCoder.decodeMap(mndRef.getMap(i))

        // mutableMapOfStringToList (index 17)
        val mslRef = map.getMap(17)
        val mutableMapOfStringToList = LinkedHashMap<String, List<Double>>(mslRef.size)
        for (i in 0 until mslRef.size) {
            val vec = mslRef.getVector(i)
            val list = ArrayList<Double>(vec.size)
            for (j in 0 until vec.size) list.add(vec.readDouble(j))
            mutableMapOfStringToList[mslRef.keyAsString(i)] = list
        }

        // setOfSets (index 19)
        val ssVec = map.getVector(19)
        val setOfSets = LinkedHashSet<Set<Float>>(ssVec.size)
        for (i in 0 until ssVec.size) {
            val inner = ssVec.readVector(i)
            val innerSet = LinkedHashSet<Float>(inner.size)
            for (j in 0 until inner.size) innerSet.add(inner.readDouble(j).toFloat())
            setOfSets.add(innerSet)
        }

        // shortListField (index 21)
        val shortVec = map.getVector(21)
        val shortList = ArrayList<Short>(shortVec.size)
        for (i in 0 until shortVec.size) shortList.add(shortVec.readInt(i).toShort())

        // stringSetField (index 23)
        val strSetVec = map.getVector(23)
        val stringSet = LinkedHashSet<String>(strSetVec.size)
        for (i in 0 until strSetVec.size) stringSet.add(strSetVec.readString(i))

        return EncodingComplexCase(
            booleanField = map.getBoolean(0),
            byteArrayField = map[1].toBlob().toByteArray(),
            byteField = map.getInt(2).toByte(),
            charField = map.getInt(3).toChar(),
            charListField = charList,
            doubleField = map.getDouble(5),
            doubleListField = doubleList,
            floatField = map.getFloat(7),
            floatSetField = floatSet,
            intField = map.getInt(9),
            intSetField = intSet,
            listOfLists = listOfLists,
            longField = map.getLong(12),
            longListField = longList,
            mapOfIntToBoolean = mapOfIntToBoolean,
            mapOfStringToInt = mapOfStringToInt,
            mapOfStringToNestedData = mapOfStringToNestedData,
            mutableMapOfStringToList = mutableMapOfStringToList,
            nestedData = NestedDataCoder.decodeMap(map.getMap(18)),
            setOfSets = setOfSets,
            shortField = map.getInt(20).toShort(),
            shortListField = shortList,
            stringField = map.getString(22),
            stringSetField = stringSet
        )
    }
}

fun registerAllCoders() {
    FlexCoderRegistry.register(FlatPrimitivesCoder)
    FlexCoderRegistry.register(CollectionHeavyCoder)
    FlexCoderRegistry.register(DeeplyNestedCoder)
    FlexCoderRegistry.register(EncodingComplexCaseCoder)
}
