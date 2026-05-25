/*
 * Copyright 2021 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("NOTHING_TO_INLINE")

package dev.shibasis.reaktor.flexbuffer.flatbuffers

@ExperimentalUnsignedTypes
public class FlexBuffersBuilder(
  public val buffer: ReadWriteBuffer,
  private val shareFlag: Int = SHARE_KEYS,
) {

  public constructor(
    initialCapacity: Int = 1024,
    shareFlag: Int = SHARE_KEYS,
  ) : this(ArrayReadWriteBuffer(initialCapacity), shareFlag)

  private val stringValuePool: HashMap<String, Value> = HashMap()
  private val stringKeyPool: HashMap<String, Int> = HashMap()
  private val stack = ValueStack()
  private var finished: Boolean = false

  // Reusable scratch Value to avoid heap allocation in hot encode paths.
  // Only used as a temporary return value — never stored across calls.
  private val scratch = Value()

  internal class ValueStack {
    private var types = IntArray(64)
    private var keys = IntArray(64)
    private var minBWs = IntArray(64)
    private var iVals = LongArray(64)
    private var dVals = DoubleArray(64)
    var size = 0
      private set

    private fun grow(needed: Int) {
      if (needed <= types.size) return
      val cap = maxOf(needed, types.size * 2)
      types = types.copyOf(cap)
      keys = keys.copyOf(cap)
      minBWs = minBWs.copyOf(cap)
      iVals = iVals.copyOf(cap)
      dVals = dVals.copyOf(cap)
    }

    fun push(type: FlexBufferType, key: Int, minBW: BitWidth, iVal: ULong, dVal: Double = 0.0) {
      grow(size + 1)
      types[size] = type.value
      keys[size] = key
      minBWs[size] = minBW.value
      iVals[size] = iVal.toLong()
      dVals[size] = dVal
      size++
    }

    fun type(i: Int) = FlexBufferType(types[i])
    fun key(i: Int) = keys[i]
    fun minBitWidth(i: Int) = BitWidth(minBWs[i])
    fun iValue(i: Int) = iVals[i].toULong()
    fun dValue(i: Int) = dVals[i]

    fun elemWidth(i: Int, bufSize: Int, elemIndex: Int): BitWidth =
      dev.shibasis.reaktor.flexbuffer.flatbuffers.elemWidth(type(i), minBitWidth(i), iVals[i], bufSize, elemIndex)

    fun storedPackedType(i: Int, parentBW: BitWidth = W_8): Byte {
      val storedBW = if (type(i).isInline()) minBitWidth(i).max(parentBW) else minBitWidth(i)
      return (storedBW.value or (types[i] shl 2)).toByte()
    }

    fun writeAny(i: Int, buffer: ReadWriteBuffer, byteWidth: ByteWidth) {
      when (type(i)) {
        T_NULL, T_BOOL, T_INT, T_UINT -> writeInt(iValue(i), buffer, byteWidth)
        T_FLOAT -> writeDouble(dVals[i], buffer, byteWidth)
        else -> writeOffset(iVals[i].toInt(), buffer, byteWidth)
      }
    }

    fun clearFrom(start: Int) { size = start }
    fun clear() { size = 0 }

    fun compareKeys(a: Int, b: Int, buffer: ReadWriteBuffer): Int =
      compareKeyToRaw(a, keys[b], buffer)

    private fun compareKeyToRaw(a: Int, rawKeyPos: Int, buffer: ReadWriteBuffer): Int {
      var ia = keys[a]
      var ib = rawKeyPos
      var c1: Byte
      var c2: Byte
      do {
        c1 = buffer[ia]
        c2 = buffer[ib]
        if (c1.toInt() == 0) return c1 - c2
        ia++; ib++
      } while (c1 == c2)
      return c1 - c2
    }

    fun sortByKeys(start: Int, buffer: ReadWriteBuffer) {
      val end = size - 1
      if (end <= start) return
      quickSortByKeys(start, end, buffer)
    }

    private fun quickSortByKeys(left: Int, right: Int, buffer: ReadWriteBuffer) {
      if (right - left <= 12) {
        insertionSortByKeys(left, right, buffer)
        return
      }

      var i = left
      var j = right
      val pivotKey = keys[(left + right) ushr 1]
      while (i <= j) {
        while (compareKeyToRaw(i, pivotKey, buffer) < 0) i++
        while (compareKeyToRaw(j, pivotKey, buffer) > 0) j--
        if (i <= j) {
          swap(i, j)
          i++
          j--
        }
      }
      if (left < j) quickSortByKeys(left, j, buffer)
      if (i < right) quickSortByKeys(i, right, buffer)
    }

    private fun insertionSortByKeys(left: Int, right: Int, buffer: ReadWriteBuffer) {
      for (i in left + 1..right) {
        var j = i
        while (j > left && compareKeys(j - 1, j, buffer) > 0) {
          swap(j - 1, j)
          j--
        }
      }
    }

    private fun swap(a: Int, b: Int) {
      if (a == b) return
      var t = types[a]; types[a] = types[b]; types[b] = t
      t = keys[a]; keys[a] = keys[b]; keys[b] = t
      t = minBWs[a]; minBWs[a] = minBWs[b]; minBWs[b] = t
      val i = iVals[a]; iVals[a] = iVals[b]; iVals[b] = i
      val d = dVals[a]; dVals[a] = dVals[b]; dVals[b] = d
    }

    companion object {
      private fun writeInt(value: ULong, buffer: ReadWriteBuffer, byteWidth: ByteWidth) {
        buffer.requestAdditionalCapacity(byteWidth.value)
        when (byteWidth.value) {
          1 -> buffer.put(value.toUByte())
          2 -> buffer.put(value.toUShort())
          4 -> buffer.put(value.toUInt())
          8 -> buffer.put(value)
        }
      }
      private fun writeDouble(value: Double, buffer: ReadWriteBuffer, byteWidth: ByteWidth) {
        buffer.requestAdditionalCapacity(byteWidth.value)
        when (byteWidth.value) { 4 -> buffer.put(value.toFloat()); 8 -> buffer.put(value) }
      }
      private fun writeOffset(value: Int, buffer: ReadWriteBuffer, byteWidth: ByteWidth) {
        val relOff = (buffer.writePosition - value).toULong()
        buffer.requestAdditionalCapacity(byteWidth.value)
        when (byteWidth.value) {
          1 -> buffer.put(relOff.toUByte())
          2 -> buffer.put(relOff.toUShort())
          4 -> buffer.put(relOff.toUInt())
          8 -> buffer.put(relOff)
        }
      }
    }
  }

  /**
   * Reset the FlexBuffersBuilder by purging all data that it holds. Buffer might keep its capacity
   * after a reset.
   */
  public fun clear() {
    buffer.clear()
    stringValuePool.clear()
    stringKeyPool.clear()
    stack.clear()
    finished = false
  }

  /**
   * Finish writing the message into the buffer. After that no other element must be inserted into
   * the buffer. Also, you must call this function before start using the FlexBuffer message
   *
   * @return [ReadBuffer] containing the FlexBuffer message
   */
  public fun finish(): ReadBuffer {
    // If you hit this, you likely have objects that were never included
    // in a parent. You need to have exactly one root to finish a buffer.
    // Check your Start/End calls are matched, and all objects are inside
    // some other object.
    if (stack.size != 1) error("There is must be only on object as root. Current ${stack.size}.")
    // Write root value.
    val byteWidth = align(stack.elemWidth(0, buffer.writePosition, 0))
    buffer.requestAdditionalCapacity(byteWidth.value + 2)
    stack.writeAny(0, buffer, byteWidth)
    // Write root type.
    buffer.put(stack.storedPackedType(0))
    // Write root size. Normally determined by parent, but root has no parent :)
    buffer.put(byteWidth.value.toByte())
    this.finished = true
    return buffer // TODO: make a read-only shallow copy
  }

  /**
   * Insert a single [Boolean] into the buffer
   *
   * @param value true or false
   */
  public fun put(value: Boolean): Unit = run { this[null] = value }

  /**
   * Insert a null reference into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public fun putNull(key: String? = null): Unit = run {
    stack.push(T_NULL, putKey(key), W_8, 0UL)
  }

  /**
   * Insert a single [Boolean] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public operator fun set(key: String? = null, value: Boolean): Unit = run {
    stack.push(T_BOOL, putKey(key), W_8, if (value) 1UL else 0UL)
  }

  /** Insert a single [Byte] into the buffer */
  public fun put(value: Byte): Unit = set(null, value.toLong())

  /**
   * Insert a single [Byte] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public operator fun set(key: String? = null, value: Byte): Unit = set(key, value.toLong())

  /** Insert a single [Short] into the buffer. */
  public fun put(value: Short): Unit = set(null, value.toLong())

  /**
   * Insert a single [Short] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public inline operator fun set(key: String? = null, value: Short): Unit = set(key, value.toLong())

  /** Insert a single [Int] into the buffer. */
  public fun put(value: Int): Unit = set(null, value.toLong())

  /**
   * Insert a single [Int] into the buffer. A key must be present if element is inserted into a map.
   */
  public inline operator fun set(key: String? = null, value: Int): Unit = set(key, value.toLong())

  /** Insert a single [Long] into the buffer. */
  public fun put(value: Long): Unit = set(null, value)

  /**
   * Insert a single [Long] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public operator fun set(key: String? = null, value: Long): Unit = run {
    // Hoist the conversion — JIT/AOT may not CSE inline value-class round-trips reliably.
    val v = value.toULong()
    stack.push(T_INT, putKey(key), v.widthInUBits(), v)
  }

  /** Insert a single [UByte] into the buffer */
  public fun put(value: UByte): Unit = set(null, value.toULong())

  /**
   * Insert a single [UByte] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public inline operator fun set(key: String? = null, value: UByte): Unit =
    set(key, value.toULong())

  /** Insert a single [UShort] into the buffer. */
  public fun put(value: UShort): Unit = set(null, value.toULong())

  /**
   * Insert a single [UShort] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  private inline operator fun set(key: String? = null, value: UShort): Unit =
    set(key, value.toULong())

  /** Insert a single [UInt] into the buffer. */
  public fun put(value: UInt): Unit = set(null, value.toULong())

  /**
   * Insert a single [UInt] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  private inline operator fun set(key: String? = null, value: UInt): Unit =
    set(key, value.toULong())

  /** Insert a single [ULong] into the buffer. */
  public fun put(value: ULong): Unit = set(null, value)

  /**
   * Insert a single [ULong] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public operator fun set(key: String? = null, value: ULong): Unit = run {
    val w = value.widthInUBits()
    stack.push(T_UINT, putKey(key), w, value)
  }

  /** Insert a single [Float] into the buffer. */
  public fun put(value: Float): Unit = run { this[null] = value }

  /**
   * Insert a single [Float] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public operator fun set(key: String? = null, value: Float): Unit = run {
    stack.push(T_FLOAT, putKey(key), W_32, 0UL, value.toDouble())
  }

  /** Insert a single [Double] into the buffer. */
  public fun put(value: Double): Unit = run { this[null] = value }

  /**
   * Insert a single [Double] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public operator fun set(key: String? = null, value: Double): Unit = run {
    stack.push(T_FLOAT, putKey(key), W_64, 0UL, value)
  }

  /** Insert a single [String] into the buffer. */
  public fun put(value: String): Int = set(null, value)

  /**
   * Insert a single [String] into the buffer. A key must be present if element is inserted into a
   * map.
   */
  public operator fun set(key: String? = null, value: String): Int {
    val iKey = putKey(key)
    if (shareFlag and SHARE_STRINGS != 0) {
      val cached = stringValuePool.getOrPut(value) {
        writeString(iKey, value)
        // Must copy scratch into a new Value for the pool since scratch is reused
        Value(scratch.type, scratch.key, scratch.minBitWidth, scratch.iValue)
      }
      stack.push(cached.type, iKey, cached.minBitWidth, cached.iValue)
      return cached.iValue.toInt()
    } else {
      writeString(iKey, value)
      stack.push(scratch.type, scratch.key, scratch.minBitWidth, scratch.iValue)
      return scratch.iValue.toInt()
    }
  }

  /**
   * Adds a [ByteArray] into the message as a [Blob].
   *
   * @param value byte array
   * @return position in buffer as the start of byte array
   */
  public fun put(value: ByteArray): Int = set(null, value)

  /**
   * Adds a [ByteArray] into the message as a [Blob]. A key must be present if element is inserted
   * into a map.
   *
   * @param value byte array
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: ByteArray): Int {
    val element = writeBlob(putKey(key), value, T_BLOB, false)
    stack.push(element.type, element.key, element.minBitWidth, element.iValue)
    return element.iValue.toInt()
  }

  /**
   * Adds a [IntArray] into the message as a typed vector of fixed size.
   *
   * @param value [IntArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: IntArray): Int = set(null, value)

  /**
   * Adds a [IntArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [IntArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: IntArray): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, value.widthInUBits()) { writeIntArray(value, it) }

  /**
   * Adds a [ShortArray] into the message as a typed vector of fixed size.
   *
   * @param value [ShortArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: ShortArray): Int = set(null, value)

  /**
   * Adds a [ShortArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [ShortArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: ShortArray): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, value.widthInUBits()) { writeIntArray(value, it) }

  /**
   * Adds a [LongArray] into the message as a typed vector of fixed size.
   *
   * @param value [LongArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: LongArray): Int = set(null, value)

  /**
   * Adds a [LongArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [LongArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: LongArray): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, value.widthInUBits()) { writeIntArray(value, it) }

  /**
   * Adds a [FloatArray] into the message as a typed vector of fixed size.
   *
   * @param value [FloatArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: FloatArray): Int = set(null, value)

  /**
   * Adds a [FloatArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [FloatArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: FloatArray): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_32) { writeFloatArray(value) }

  /**
   * Adds a [DoubleArray] into the message as a typed vector of fixed size.
   *
   * @param value [DoubleArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: DoubleArray): Int = set(null, value)

  /**
   * Adds a [DoubleArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [DoubleArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: DoubleArray): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_64) { writeFloatArray(value) }

  /**
   * Adds a [UByteArray] into the message as a typed vector of fixed size.
   *
   * @param value [UByteArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: UByteArray): Int = set(null, value)

  /**
   * Adds a [UByteArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [UByteArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: UByteArray): Int =
    setTypedVec(key) { value.forEach { put(it) } }

  /**
   * Adds a [UShortArray] into the message as a typed vector of fixed size.
   *
   * @param value [UShortArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: UShortArray): Int = set(null, value)

  /**
   * Adds a [UShortArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [UShortArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: UShortArray): Int =
    setTypedVec(key) { value.forEach { put(it) } }

  /**
   * Adds a [UIntArray] into the message as a typed vector of fixed size.
   *
   * @param value [UIntArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: UIntArray): Int = set(null, value)

  /**
   * Adds a [UIntArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [UIntArray]
   * @return position in buffer as the start of byte array
   */
  public fun set(key: String? = null, value: UIntArray): Int =
    setTypedVec(key) { value.forEach { put(it) } }

  /**
   * Adds a [ULongArray] into the message as a typed vector of fixed size.
   *
   * @param value [ULongArray]
   * @return position in buffer as the start of byte array
   */
  public fun put(value: ULongArray): Int = set(null, value)

  /**
   * Adds a [ULongArray] into the message as a typed vector of fixed size. A key must be present if
   * element is inserted into a map.
   *
   * @param value [ULongArray]
   * @return position in buffer as the start of byte array
   */
  public operator fun set(key: String? = null, value: ULongArray): Int =
    setTypedVec(key) { value.forEach { put(it) } }

  public fun setIntList(key: String? = null, value: List<Int>): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, listIntWidthInUBits(value)) {
      writeIntegerArray(0, value.size, it) { i -> value[i].toULong() }
    }

  public fun setLongList(key: String? = null, value: List<Long>): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, listLongWidthInUBits(value)) {
      writeIntegerArray(0, value.size, it) { i -> value[i].toULong() }
    }

  public fun setIntCollection(key: String? = null, value: Collection<Int>): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, collectionWidthInUBits(value) { it.toULong() }) {
      writeIntegerCollection(value, it) { item -> item.toULong() }
    }

  public fun setLongCollection(key: String? = null, value: Collection<Long>): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, collectionWidthInUBits(value) { it.toULong() }) {
      writeIntegerCollection(value, it) { item -> item.toULong() }
    }

  public fun setDoubleList(key: String? = null, value: List<Double>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_64) {
      buffer.requestAdditionalCapacity(Double.SIZE_BYTES * value.size)
      value.forEach { buffer.put(it) }
    }

  public fun setDoubleCollection(key: String? = null, value: Collection<Double>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_64) {
      buffer.requestAdditionalCapacity(Double.SIZE_BYTES * value.size)
      value.forEach { buffer.put(it) }
    }

  public fun setFloatList(key: String? = null, value: List<Float>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_32) {
      buffer.requestAdditionalCapacity(Float.SIZE_BYTES * value.size)
      value.forEach { buffer.put(it) }
    }

  public fun setFloatCollection(key: String? = null, value: Collection<Float>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_32) {
      buffer.requestAdditionalCapacity(Float.SIZE_BYTES * value.size)
      value.forEach { buffer.put(it) }
    }

  /**
   * Creates a new vector will all elements inserted in [block].
   *
   * @param block where elements will be inserted
   * @return position in buffer as the start of byte array
   */
  public inline fun putVector(crossinline block: FlexBuffersBuilder.() -> Unit): Int {
    val pos = startVector()
    this.block()
    return endVector(pos)
  }

  /**
   * Creates a new typed vector will all elements inserted in [block].
   *
   * @param block where elements will be inserted
   * @return position in buffer as the start of byte array
   */
  public inline fun putTypedVector(crossinline block: FlexBuffersBuilder.() -> Unit): Int {
    val pos = startVector()
    this.block()
    return endTypedVector(pos)
  }

  /** Helper function to return position for starting a new vector. */
  public fun startVector(): Int = stack.size

  /**
   * Finishes a vector element. The initial position of the vector must be passed
   *
   * @param position position at the start of the vector
   */
  public fun endVector(position: Int): Int = endVector(null, position)

  /**
   * Finishes a vector element. The initial position of the vector must be passed
   *
   * @param position position at the start of the vector
   */
  public fun endVector(key: String? = null, position: Int): Int =
    endAnyVector(position) { createVector(putKey(key), position, stack.size - position) }

  /**
   * Finishes a typed vector element. The initial position of the vector must be passed
   *
   * @param position position at the start of the vector
   */
  public fun endTypedVector(position: Int): Int = endTypedVector(position, null)

  /** Helper function to return position for starting a new vector. */
  public fun startMap(): Int = stack.size

  /**
   * Creates a new map will all elements inserted in [block].
   *
   * @param block where elements will be inserted
   * @return position in buffer as the start of byte array
   */
  public inline fun putMap(
    key: String? = null,
    crossinline block: FlexBuffersBuilder.() -> Unit,
  ): Int {
    val pos = startMap()
    this.block()
    return endMap(pos, key)
  }

  /**
   * Finishes a map, but writing the information in the buffer
   *
   * @param key key used to store element in map
   * @return Reference to the map
   */
  public fun endMap(start: Int, key: String? = null, presorted: Boolean = false): Int {
    if (!presorted && !isKeySorted(start)) {
      stack.sortByKeys(start, buffer)
    }
    val length = stack.size - start
    val keys = createKeyVector(start, length)
    val vec = putMap(putKey(key), start, length, keys)
    stack.clearFrom(start)
    stack.push(vec.type, vec.key, vec.minBitWidth, vec.iValue)
    return vec.iValue.toInt()
  }

  private fun isKeySorted(start: Int): Boolean {
    val end = stack.size
    if (end - start <= 1) return true
    for (i in start until end - 1) {
      if (stack.compareKeys(i, i + 1, buffer) >= 0) return false
    }
    return true
  }

  private inline fun setTypedVector(
    key: String? = null,
    length: Int,
    vecType: FlexBufferType,
    bitWidth: BitWidth,
    crossinline writeBlock: (ByteWidth) -> Unit,
  ): Int {
    val keyPos = putKey(key)
    val byteWidth = align(bitWidth)
    // Write vector. First the keys width/offset if available, and size.
    // write the size
    writeInt(length, byteWidth)

    // Then the actual data.
    val vloc: Int = buffer.writePosition
    writeBlock(byteWidth)
    stack.push(vecType, keyPos, bitWidth, vloc.toULong())
    return vloc
  }

  private inline fun setTypedVec(
    key: String? = null,
    crossinline block: FlexBuffersBuilder.() -> Unit,
  ): Int {
    val pos = startVector()
    this.block()
    return endTypedVector(pos, key)
  }

  public fun endTypedVector(position: Int, key: String? = null): Int =
    endAnyVector(position) { createTypedVector(putKey(key), position, stack.size - position) }

  private inline fun endAnyVector(start: Int, crossinline creationBlock: () -> Value): Int {
    val vec = creationBlock()
    stack.clearFrom(start)
    stack.push(vec.type, vec.key, vec.minBitWidth, vec.iValue)
    return vec.iValue.toInt()
  }

  private inline fun putKey(key: String? = null): Int {
    if (key == null) return -1
    return if ((shareFlag and SHARE_KEYS) != 0) {
      stringKeyPool.getOrPut(key) {
        val pos: Int = buffer.writePosition
        val encodedKeySize = fastEncodedLength(key)
        buffer.requestAdditionalCapacity(encodedKeySize + 1)
        buffer.put(key, encodedKeySize)
        buffer.put(ZeroByte)
        pos
      }
    } else {
      val pos: Int = buffer.writePosition
      val encodedKeySize = fastEncodedLength(key)
      buffer.requestAdditionalCapacity(encodedKeySize + 1)
      buffer.put(key, encodedKeySize)
      buffer.put(ZeroByte)
      pos
    }
  }

  private fun writeString(key: Int, s: String): Value {
    val encodedSize = fastEncodedLength(s)
    val bitWidth = encodedSize.toULong().widthInUBits()
    val byteWidth = align(bitWidth)

    writeInt(encodedSize, byteWidth)

    buffer.requestAdditionalCapacity(encodedSize + 1)
    val sloc: Int = buffer.writePosition
    if (encodedSize > 0) buffer.put(s, encodedSize)
    buffer.put(ZeroByte)
    return scratch.also { it.type = T_STRING; it.key = key; it.minBitWidth = bitWidth; it.iValue = sloc.toULong() }
  }

  private fun writeDouble(toWrite: Double, byteWidth: ByteWidth) {
    buffer.requestAdditionalCapacity(byteWidth.value)
    when (byteWidth.value) {
      4 -> buffer.put(toWrite.toFloat())
      8 -> buffer.put(toWrite)
      else -> Unit
    }
  }

  private fun writeOffset(toWrite: Int, byteWidth: ByteWidth) {
    buffer.requestAdditionalCapacity(byteWidth.value)
    val relativeOffset = (buffer.writePosition - toWrite)
    if (byteWidth.value != 8 && relativeOffset >= 1L shl byteWidth.value * 8)
      error("invalid offset $relativeOffset, writer pos ${buffer.writePosition}")
    writeInt(relativeOffset, byteWidth)
  }

  private inline fun writeBlob(
    key: Int,
    blob: ByteArray,
    type: FlexBufferType,
    trailing: Boolean,
  ): Value {
    val bitWidth = blob.size.toULong().widthInUBits()
    val byteWidth = align(bitWidth)

    writeInt(blob.size, byteWidth)

    val sloc: Int = buffer.writePosition
    buffer.requestAdditionalCapacity(blob.size + trailing.compareTo(false))
    buffer.put(blob, 0, blob.size)
    if (trailing) {
      buffer.put(ZeroByte)
    }
    return scratch.also { it.type = type; it.key = key; it.minBitWidth = bitWidth; it.iValue = sloc.toULong() }
  }

  private fun writeIntArray(value: IntArray, byteWidth: ByteWidth) =
    writeIntegerArray(0, value.size, byteWidth) { value[it].toULong() }

  private fun writeIntArray(value: ShortArray, byteWidth: ByteWidth) =
    writeIntegerArray(0, value.size, byteWidth) { value[it].toULong() }

  private fun writeIntArray(value: LongArray, byteWidth: ByteWidth) =
    writeIntegerArray(0, value.size, byteWidth) { value[it].toULong() }

  private fun writeFloatArray(value: FloatArray) {
    buffer.requestAdditionalCapacity(Float.SIZE_BYTES * value.size)
    value.forEach { buffer.put(it) }
  }

  private fun writeFloatArray(value: DoubleArray) {
    buffer.requestAdditionalCapacity(Double.SIZE_BYTES * value.size)
    value.forEach { buffer.put(it) }
  }

  private inline fun writeIntegerArray(
    start: Int,
    size: Int,
    byteWidth: ByteWidth,
    crossinline valueBlock: (Int) -> ULong,
  ) {
    buffer.requestAdditionalCapacity(size * byteWidth.value)
    return when (byteWidth.value) {
      1 ->
        for (i in start until start + size) {
          buffer.put(valueBlock(i).toUByte())
        }
      2 ->
        for (i in start until start + size) {
          buffer.put(valueBlock(i).toUShort())
        }
      4 ->
        for (i in start until start + size) {
          buffer.put(valueBlock(i).toUInt())
        }
      8 ->
        for (i in start until start + size) {
          buffer.put(valueBlock(i))
        }
      else -> Unit
    }
  }

  private inline fun <T> writeIntegerCollection(
    values: Collection<T>,
    byteWidth: ByteWidth,
    crossinline valueBlock: (T) -> ULong,
  ) {
    buffer.requestAdditionalCapacity(values.size * byteWidth.value)
    when (byteWidth.value) {
      1 -> values.forEach { buffer.put(valueBlock(it).toUByte()) }
      2 -> values.forEach { buffer.put(valueBlock(it).toUShort()) }
      4 -> values.forEach { buffer.put(valueBlock(it).toUInt()) }
      8 -> values.forEach { buffer.put(valueBlock(it)) }
    }
  }

  private fun writeInt(value: Int, byteWidth: ByteWidth) {
    buffer.requestAdditionalCapacity(byteWidth.value)
    when (byteWidth.value) {
      1 -> buffer.put(value.toUByte())
      2 -> buffer.put(value.toUShort())
      4 -> buffer.put(value.toUInt())
      8 -> buffer.put(value.toULong())
      else -> Unit
    }
  }

  private fun writeInt(value: ULong, byteWidth: ByteWidth) {
    buffer.requestAdditionalCapacity(byteWidth.value)
    when (byteWidth.value) {
      1 -> buffer.put(value.toUByte())
      2 -> buffer.put(value.toUShort())
      4 -> buffer.put(value.toUInt())
      8 -> buffer.put(value)
      else -> Unit
    }
  }

  // Align to prepare for writing a scalar with a certain size.
  // returns the amounts of bytes needed to be written.
  private fun align(alignment: BitWidth): ByteWidth {
    val byteWidth = 1 shl alignment.value
    var padBytes = paddingBytes(buffer.writePosition, byteWidth)
    // Only reserve the extra padding bytes we are about to write.
    // Requesting buffer.capacity + padBytes forces an unnecessary resize whenever
    // padBytes > 0, even if the backing array already has ample free space.
    buffer.requestAdditionalCapacity(padBytes)
    while (padBytes-- != 0) {
      buffer.put(ZeroByte)
    }
    return ByteWidth(byteWidth)
  }

  private fun calculateKeyVectorBitWidth(start: Int, length: Int): BitWidth {
    val bitWidth = length.toULong().widthInUBits()
    var width = bitWidth
    val prefixElems = 1
    for (i in start until stack.size) {
      val ew = elemWidth(T_KEY, W_8, stack.key(i).toLong(), buffer.writePosition, i + prefixElems)
      width = width.max(ew)
    }
    return width
  }

  private inline fun <T> collectionWidthInUBits(
    values: Collection<T>,
    crossinline valueBlock: (T) -> ULong,
  ): BitWidth {
    var bitWidth = W_8.max(values.size.toULong().widthInUBits())
    values.forEach { bitWidth = bitWidth.max(valueBlock(it).widthInUBits()) }
    return bitWidth
  }

  // Reusable scratch for key vector results to avoid allocation in endMap
  private val keyScratch = Value()

  private fun createKeyVector(start: Int, length: Int): Value {
    val bitWidth = calculateKeyVectorBitWidth(start, length)
    val byteWidth = align(bitWidth)
    writeInt(length, byteWidth)
    val vloc = buffer.writePosition.toULong()
    for (i in start until stack.size) {
      val pos = stack.key(i)
      if (pos == -1) error("invalid position $pos for key")
      writeOffset(pos, byteWidth)
    }
    return keyScratch.also { it.type = T_VECTOR_KEY; it.key = -1; it.minBitWidth = bitWidth; it.iValue = vloc }
  }

  private inline fun createVector(key: Int, start: Int, length: Int, keys: Value? = null): Value {
    return createAnyVector(key, start, length, T_VECTOR, keys) {
      buffer.requestAdditionalCapacity(stack.size)
      for (i in start until stack.size) {
        buffer.put(stack.storedPackedType(i, it))
      }
    }
  }

  private fun putMap(key: Int, start: Int, length: Int, keys: Value? = null): Value {
    return createAnyVector(key, start, length, T_MAP, keys) {
      buffer.requestAdditionalCapacity(stack.size)
      for (i in start until stack.size) {
        buffer.put(stack.storedPackedType(i, it))
      }
    }
  }

  private inline fun createTypedVector(
    key: Int,
    start: Int,
    length: Int,
    keys: Value? = null,
  ): Value {
    val elementType: FlexBufferType = stack.type(start)
    for (i in start + 1 until length) {
      if (elementType != stack.type(i))
        error("TypedVector does not support array of different element types")
    }
    if (!elementType.isTypedVectorElementType())
      error("TypedVector does not support this element type")
    return createAnyVector(key, start, length, elementType.toTypedVector(), keys)
  }

  private inline fun createAnyVector(
    key: Int,
    start: Int,
    length: Int,
    type: FlexBufferType,
    keys: Value? = null,
    crossinline typeBlock: (BitWidth) -> Unit = {},
  ): Value {
    var bitWidth = W_8.max(length.toULong().widthInUBits())
    var prefixElems = 1
    if (keys != null) {
      bitWidth = bitWidth.max(keys.elemWidth(buffer.writePosition, 0))
      prefixElems += 2
    }
    for (i in start until stack.size) {
      val ew = stack.elemWidth(i, buffer.writePosition, i + prefixElems)
      bitWidth = bitWidth.max(ew)
    }
    val byteWidth = align(bitWidth)
    if (keys != null) {
      writeOffset(keys.iValue.toInt(), byteWidth)
      writeInt(1 shl keys.minBitWidth.value, byteWidth)
    }
    writeInt(length, byteWidth)

    val vloc: Int = buffer.writePosition
    for (i in start until stack.size) {
      stack.writeAny(i, buffer, byteWidth)
    }

    typeBlock(bitWidth)
    return scratch.also { it.type = type; it.key = key; it.minBitWidth = bitWidth; it.iValue = vloc.toULong() }
  }

  // A lambda to sort map keys
  public companion object {
    /** No keys or strings will be shared */
    public const val SHARE_NONE: Int = 0

    /**
     * Keys will be shared between elements. Identical keys will only be serialized once, thus
     * possibly saving space. But serialization performance might be slower and consumes more
     * memory.
     */
    public const val SHARE_KEYS: Int = 1

    /**
     * Strings will be shared between elements. Identical strings will only be serialized once, thus
     * possibly saving space. But serialization performance might be slower and consumes more
     * memory. This is ideal if you expect many repeated strings on the message.
     */
    public const val SHARE_STRINGS: Int = 2

    /** Strings and keys will be shared between elements. */
    public const val SHARE_KEYS_AND_STRINGS: Int = 3
  }
}
