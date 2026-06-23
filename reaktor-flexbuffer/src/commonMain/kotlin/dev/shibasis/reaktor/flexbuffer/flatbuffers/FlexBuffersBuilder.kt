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

import kotlin.jvm.JvmField

/**
 * FlexBuffer writer over a directly-owned, growable [ByteArray].
 *
 * There is no buffer interface in the write path: every scalar lands via the
 * platform memory layer (single store), padding is one 8-byte zero store,
 * bulk arrays are width-specialized loops, and integers use C++-compatible
 * sign-extended minimal widths (a `-5` costs one byte, not eight).
 *
 * Key-block fast path: a generated coder registers its pre-encoded, sorted key
 * set once per buffer via [keyBlock]; every map of that type then reuses both
 * the key bytes and a shared key vector — zero per-field key hashing, zero
 * per-map key-vector writes after the first. This goes beyond the C++ builder,
 * which re-resolves keys through a std::set per field.
 */
@ExperimentalUnsignedTypes
public class FlexBuffersBuilder(
  initialCapacity: Int = 1024,
  private val shareFlag: Int = SHARE_KEYS,
) {

  @JvmField internal var ba: ByteArray = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
  @JvmField internal var pos: Int = 0

  public val writePosition: Int
    get() = pos

  private val stringValuePool: HashMap<String, Value> = HashMap()
  private val stringKeyPool: HashMap<String, Int> = HashMap()
  private val stack = ValueStack()
  private var finished: Boolean = false

  // Reusable scratch Value to avoid heap allocation in hot encode paths.
  // Only used as a temporary return value — never stored across calls.
  private val scratch = Value()

  // ─── Key-block cache (identity-keyed; one slot per @Struct type in the tree) ───
  private var kbBlobs = arrayOfNulls<ByteArray>(8)
  private var kbOffsets = arrayOfNulls<IntArray>(8)
  private var kbVectorLoc = IntArray(8)
  private var kbVectorWidth = IntArray(8)
  private var kbCount = 0

  private fun growKeyBlockCache() {
    val cap = kbBlobs.size * 2
    kbBlobs = kbBlobs.copyOf(cap)
    kbOffsets = kbOffsets.copyOf(cap)
    kbVectorLoc = kbVectorLoc.copyOf(cap)
    kbVectorWidth = kbVectorWidth.copyOf(cap)
  }

  // ─── Raw write primitives ───

  private fun grow(needed: Int) {
    var cap = ba.size * 2
    if (cap < needed) cap = needed
    ba = ba.copyOf(cap)
  }

  private inline fun ensure(n: Int) {
    if (pos + n > ba.size) grow(pos + n)
  }

  private inline fun putByteRaw(v: Int) {
    ba[pos] = v.toByte()
    pos++
  }

  /** Width-dispatched value write at the current position. Caller must [ensure]. */
  private inline fun putWRaw(value: Long, w: Int) {
    ba.stW(pos, value, w)
    pos += w
  }

  private inline fun putW(value: Long, w: Int) {
    ensure(w)
    putWRaw(value, w)
  }

  // Align to prepare for writing a scalar with a certain size.
  // Padding is a single 8-byte zero store: cheaper than any per-byte loop and
  // correct because pad < 8 and the next write lands at pos.
  private fun align(alignment: BitWidth): Int {
    val byteWidth = 1 shl alignment.value
    val pad = (-pos) and (byteWidth - 1)
    if (pad != 0) {
      ensure(8)
      ba.st64(pos, 0L)
      pos += pad
    }
    return byteWidth
  }

  private fun writeOffset(toWrite: Int, byteWidth: Int) {
    ensure(byteWidth)
    putWRaw((pos - toWrite).toLong(), byteWidth)
  }

  private fun writeDoubleW(toWrite: Double, byteWidth: Int) {
    ensure(byteWidth)
    if (byteWidth == 8) ba.stF64(pos, toWrite) else ba.stF32(pos, toWrite.toFloat())
    pos += byteWidth
  }

  /**
   * Reset the FlexBuffersBuilder by purging all data that it holds. Buffer might keep its capacity
   * after a reset.
   */
  public fun clear() {
    pos = 0
    stringValuePool.clear()
    stringKeyPool.clear()
    stack.clear()
    finished = false
    kbCount = 0
  }

  private fun finalizeBuffer() {
    if (finished) return
    // If you hit this, you likely have objects that were never included
    // in a parent. You need to have exactly one root to finish a buffer.
    if (stack.size != 1) error("There is must be only on object as root. Current ${stack.size}.")
    // Write root value.
    val byteWidth = align(stack.elemWidth(0, pos, 0))
    writeAnyAt(0, byteWidth)
    // Write root type, then root byte width.
    ensure(2)
    putByteRaw(stack.storedPackedType(0).toInt())
    putByteRaw(byteWidth)
    finished = true
  }

  /**
   * Finish writing the message into the buffer. After that no other element must be inserted into
   * the buffer. Also, you must call this function before start using the FlexBuffer message
   *
   * @return [ReadBuffer] view of the FlexBuffer message (no copy)
   */
  public fun finish(): ReadBuffer {
    finalizeBuffer()
    return ArrayReadBuffer(ba, 0, pos)
  }

  /** Finish and copy out the message bytes. */
  public fun finishToByteArray(): ByteArray {
    finalizeBuffer()
    return ba.copyOf(pos)
  }

  /** Finish and decode in place: the backing array plus the message end position. */
  public fun finishedBytes(): ByteArray {
    finalizeBuffer()
    return ba
  }

  public fun finishedLimit(): Int {
    finalizeBuffer()
    return pos
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
   *
   * Stored sign-extended at minimal width, matching the C++ builder: negative values
   * no longer force 8-byte slots.
   */
  public operator fun set(key: String? = null, value: Long): Unit = run {
    stack.push(T_INT, putKey(key), value.signedWidthInUBits(), value.toULong())
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
    stack.push(T_UINT, putKey(key), value.widthInUBits(), value)
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
    setTypedVector(key, value.size, T_VECTOR_INT, value.widthInUBits()) { w ->
      ensure(value.size * w)
      var p = pos
      when (w) {
        1 -> for (v in value) { ba.st8(p, v); p++ }
        2 -> for (v in value) { ba.st16(p, v); p += 2 }
        4 -> for (v in value) { ba.st32(p, v); p += 4 }
        else -> for (v in value) { ba.st64(p, v.toLong()); p += 8 }
      }
      pos = p
    }

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
    setTypedVector(key, value.size, T_VECTOR_INT, value.widthInUBits()) { w ->
      ensure(value.size * w)
      var p = pos
      when (w) {
        1 -> for (v in value) { ba.st8(p, v.toInt()); p++ }
        2 -> for (v in value) { ba.st16(p, v.toInt()); p += 2 }
        4 -> for (v in value) { ba.st32(p, v.toInt()); p += 4 }
        else -> for (v in value) { ba.st64(p, v.toLong()); p += 8 }
      }
      pos = p
    }

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
    setTypedVector(key, value.size, T_VECTOR_INT, value.widthInUBits()) { w ->
      ensure(value.size * w)
      var p = pos
      when (w) {
        1 -> for (v in value) { ba.st8(p, v.toInt()); p++ }
        2 -> for (v in value) { ba.st16(p, v.toInt()); p += 2 }
        4 -> for (v in value) { ba.st32(p, v.toInt()); p += 4 }
        else -> for (v in value) { ba.st64(p, v); p += 8 }
      }
      pos = p
    }

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
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_32) {
      ensure(value.size * 4)
      var p = pos
      for (v in value) { ba.stF32(p, v); p += 4 }
      pos = p
    }

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
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_64) {
      ensure(value.size * 8)
      var p = pos
      for (v in value) { ba.stF64(p, v); p += 8 }
      pos = p
    }

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
    setTypedVector(key, value.size, T_VECTOR_INT, listIntWidthInUBits(value)) { w ->
      val n = value.size
      ensure(n * w)
      var p = pos
      when (w) {
        1 -> for (i in 0 until n) { ba.st8(p, value[i]); p++ }
        2 -> for (i in 0 until n) { ba.st16(p, value[i]); p += 2 }
        4 -> for (i in 0 until n) { ba.st32(p, value[i]); p += 4 }
        else -> for (i in 0 until n) { ba.st64(p, value[i].toLong()); p += 8 }
      }
      pos = p
    }

  public fun setLongList(key: String? = null, value: List<Long>): Int =
    setTypedVector(key, value.size, T_VECTOR_INT, listLongWidthInUBits(value)) { w ->
      val n = value.size
      ensure(n * w)
      var p = pos
      when (w) {
        1 -> for (i in 0 until n) { ba.st8(p, value[i].toInt()); p++ }
        2 -> for (i in 0 until n) { ba.st16(p, value[i].toInt()); p += 2 }
        4 -> for (i in 0 until n) { ba.st32(p, value[i].toInt()); p += 4 }
        else -> for (i in 0 until n) { ba.st64(p, value[i]); p += 8 }
      }
      pos = p
    }

  public fun setIntCollection(key: String? = null, value: Collection<Int>): Int =
    setTypedVector(
      key,
      value.size,
      T_VECTOR_INT,
      collectionWidthInUBits(value) { it.toLong() },
    ) { w ->
      ensure(value.size * w)
      var p = pos
      when (w) {
        1 -> value.forEach { ba.st8(p, it); p++ }
        2 -> value.forEach { ba.st16(p, it); p += 2 }
        4 -> value.forEach { ba.st32(p, it); p += 4 }
        else -> value.forEach { ba.st64(p, it.toLong()); p += 8 }
      }
      pos = p
    }

  public fun setLongCollection(key: String? = null, value: Collection<Long>): Int =
    setTypedVector(
      key,
      value.size,
      T_VECTOR_INT,
      collectionWidthInUBits(value) { it },
    ) { w ->
      ensure(value.size * w)
      var p = pos
      when (w) {
        1 -> value.forEach { ba.st8(p, it.toInt()); p++ }
        2 -> value.forEach { ba.st16(p, it.toInt()); p += 2 }
        4 -> value.forEach { ba.st32(p, it.toInt()); p += 4 }
        else -> value.forEach { ba.st64(p, it); p += 8 }
      }
      pos = p
    }

  public fun setDoubleList(key: String? = null, value: List<Double>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_64) {
      val n = value.size
      ensure(n * 8)
      var p = pos
      for (i in 0 until n) { ba.stF64(p, value[i]); p += 8 }
      pos = p
    }

  public fun setDoubleCollection(key: String? = null, value: Collection<Double>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_64) {
      ensure(value.size * 8)
      var p = pos
      value.forEach { ba.stF64(p, it); p += 8 }
      pos = p
    }

  public fun setFloatList(key: String? = null, value: List<Float>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_32) {
      val n = value.size
      ensure(n * 4)
      var p = pos
      for (i in 0 until n) { ba.stF32(p, value[i]); p += 4 }
      pos = p
    }

  public fun setFloatCollection(key: String? = null, value: Collection<Float>): Int =
    setTypedVector(key, value.size, T_VECTOR_FLOAT, W_32) {
      ensure(value.size * 4)
      var p = pos
      value.forEach { ba.stF32(p, it); p += 4 }
      pos = p
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
  public fun endMap(start: Int, key: String? = null, presorted: Boolean = false): Int =
    endMapDynamicKeyed(start, putKey(key), presorted)

  /** [endMap] with the map's own key supplied as a precomputed offset. */
  public fun endMapDynamicKeyed(start: Int, keyOffset: Int, presorted: Boolean = false): Int {
    if (!presorted && !isKeySorted(start)) {
      stack.sortByKeys(start, ba)
    }
    val length = stack.size - start
    createKeyVector(start, length)
    val keyVecLoc = keyScratch.iValue.toInt()
    val keyVecWidth = 1 shl keyScratch.minBitWidth.value
    val vec = putMapVector(keyOffset, start, length, keyVecLoc, keyVecWidth)
    stack.clearFrom(start)
    stack.push(vec.type, vec.key, vec.minBitWidth, vec.iValue)
    return vec.iValue.toInt()
  }

  // ─── Key-block fast path (generated coders) ───

  /**
   * Resolves a String key to its buffer offset (shared via the key pool when enabled).
   * -1 for null keys. Public so generated `encode(builder, value, key)` overloads can
   * bridge into the keyed fast path.
   */
  public fun resolveKey(key: String?): Int = putKey(key)

  /**
   * Registers a pre-encoded key block: [blob] holds the type's keys, sorted, each
   * null-terminated; [starts] holds each key's offset within [blob]. The blob is
   * written to the buffer once per encode; the returned array maps key index →
   * absolute buffer offset. Subsequent calls with the same [blob] instance are a
   * single identity compare.
   */
  public fun keyBlock(blob: ByteArray, starts: IntArray): IntArray {
    val n = kbCount
    for (i in 0 until n) {
      if (kbBlobs[i] === blob) return kbOffsets[i]!!
    }
    ensure(blob.size)
    val base = pos
    blob.copyInto(ba, base)
    pos += blob.size
    val abs = IntArray(starts.size)
    for (i in starts.indices) abs[i] = base + starts[i]
    if (n == kbBlobs.size) growKeyBlockCache()
    kbBlobs[n] = blob
    kbOffsets[n] = abs
    kbVectorLoc[n] = -1
    kbCount = n + 1
    return abs
  }

  /** Pushes a null with a precomputed key offset (from [keyBlock]). */
  public fun putNullKeyed(keyOffset: Int): Unit = run {
    stack.push(T_NULL, keyOffset, W_8, 0UL)
  }

  /** Pushes a boolean with a precomputed key offset (from [keyBlock]). */
  public fun setKeyed(keyOffset: Int, value: Boolean): Unit = run {
    stack.push(T_BOOL, keyOffset, W_8, if (value) 1UL else 0UL)
  }

  /** Pushes a signed integer with a precomputed key offset (from [keyBlock]). */
  public fun setKeyed(keyOffset: Int, value: Long): Unit = run {
    stack.push(T_INT, keyOffset, value.signedWidthInUBits(), value.toULong())
  }

  /** Int-specialized push: width computed without Long math (JS stays unemulated). */
  public fun setKeyed(keyOffset: Int, value: Int): Unit = run {
    stack.push(T_INT, keyOffset, value.signedWidthInUBits(), value.toLong().toULong())
  }

  /** Pushes a float with a precomputed key offset (from [keyBlock]). */
  public fun setKeyed(keyOffset: Int, value: Float): Unit = run {
    stack.push(T_FLOAT, keyOffset, W_32, 0UL, value.toDouble())
  }

  /** Pushes a double with a precomputed key offset (from [keyBlock]). */
  public fun setKeyed(keyOffset: Int, value: Double): Unit = run {
    stack.push(T_FLOAT, keyOffset, W_64, 0UL, value)
  }

  /** Writes a string value with a precomputed key offset (from [keyBlock]). */
  public fun setKeyed(keyOffset: Int, value: String) {
    writeString(keyOffset, value)
    stack.push(scratch.type, scratch.key, scratch.minBitWidth, scratch.iValue)
  }

  /** Writes a blob value with a precomputed key offset (from [keyBlock]). */
  public fun setKeyed(keyOffset: Int, value: ByteArray) {
    val element = writeBlob(keyOffset, value, T_BLOB, false)
    stack.push(element.type, element.key, element.minBitWidth, element.iValue)
  }

  /** Ends a vector whose key offset was precomputed via [keyBlock]. */
  public fun endVectorKeyed(keyOffset: Int, position: Int): Int =
    endAnyVector(position) { createVector(keyOffset, position, stack.size - position) }

  /** [setIntList] with a precomputed key offset. */
  public fun setIntListKeyed(keyOffset: Int, value: List<Int>): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_INT, listIntWidthInUBits(value)) { w ->
      val n = value.size
      ensure(n * w)
      var p = pos
      when (w) {
        1 -> for (i in 0 until n) { ba.st8(p, value[i]); p++ }
        2 -> for (i in 0 until n) { ba.st16(p, value[i]); p += 2 }
        4 -> for (i in 0 until n) { ba.st32(p, value[i]); p += 4 }
        else -> for (i in 0 until n) { ba.st64(p, value[i].toLong()); p += 8 }
      }
      pos = p
    }

  /** [setLongList] with a precomputed key offset. */
  public fun setLongListKeyed(keyOffset: Int, value: List<Long>): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_INT, listLongWidthInUBits(value)) { w ->
      val n = value.size
      ensure(n * w)
      var p = pos
      when (w) {
        1 -> for (i in 0 until n) { ba.st8(p, value[i].toInt()); p++ }
        2 -> for (i in 0 until n) { ba.st16(p, value[i].toInt()); p += 2 }
        4 -> for (i in 0 until n) { ba.st32(p, value[i].toInt()); p += 4 }
        else -> for (i in 0 until n) { ba.st64(p, value[i]); p += 8 }
      }
      pos = p
    }

  /** [setDoubleList] with a precomputed key offset. */
  public fun setDoubleListKeyed(keyOffset: Int, value: List<Double>): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_FLOAT, W_64) {
      val n = value.size
      ensure(n * 8)
      var p = pos
      for (i in 0 until n) { ba.stF64(p, value[i]); p += 8 }
      pos = p
    }

  /** [setFloatList] with a precomputed key offset. */
  public fun setFloatListKeyed(keyOffset: Int, value: List<Float>): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_FLOAT, W_32) {
      val n = value.size
      ensure(n * 4)
      var p = pos
      for (i in 0 until n) { ba.stF32(p, value[i]); p += 4 }
      pos = p
    }

  /** [set] for [IntArray] with a precomputed key offset. */
  public fun setKeyed(keyOffset: Int, value: IntArray): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_INT, value.widthInUBits()) { w ->
      ensure(value.size * w)
      var p = pos
      when (w) {
        1 -> for (v in value) { ba.st8(p, v); p++ }
        2 -> for (v in value) { ba.st16(p, v); p += 2 }
        4 -> for (v in value) { ba.st32(p, v); p += 4 }
        else -> for (v in value) { ba.st64(p, v.toLong()); p += 8 }
      }
      pos = p
    }

  /** [set] for [LongArray] with a precomputed key offset. */
  public fun setKeyed(keyOffset: Int, value: LongArray): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_INT, value.widthInUBits()) { w ->
      ensure(value.size * w)
      var p = pos
      when (w) {
        1 -> for (v in value) { ba.st8(p, v.toInt()); p++ }
        2 -> for (v in value) { ba.st16(p, v.toInt()); p += 2 }
        4 -> for (v in value) { ba.st32(p, v.toInt()); p += 4 }
        else -> for (v in value) { ba.st64(p, v); p += 8 }
      }
      pos = p
    }

  /** [set] for [FloatArray] with a precomputed key offset. */
  public fun setKeyed(keyOffset: Int, value: FloatArray): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_FLOAT, W_32) {
      ensure(value.size * 4)
      var p = pos
      for (v in value) { ba.stF32(p, v); p += 4 }
      pos = p
    }

  /** [set] for [DoubleArray] with a precomputed key offset. */
  public fun setKeyed(keyOffset: Int, value: DoubleArray): Int =
    setTypedVectorKeyed(keyOffset, value.size, T_VECTOR_FLOAT, W_64) {
      ensure(value.size * 8)
      var p = pos
      for (v in value) { ba.stF64(p, v); p += 8 }
      pos = p
    }

  private inline fun setTypedVectorKeyed(
    keyOffset: Int,
    length: Int,
    vecType: FlexBufferType,
    bitWidth: BitWidth,
    crossinline writeBlock: (Int) -> Unit,
  ): Int {
    val byteWidth = align(bitWidth)
    putW(length.toLong(), byteWidth)
    val vloc: Int = pos
    writeBlock(byteWidth)
    stack.push(vecType, keyOffset, bitWidth, vloc.toULong())
    return vloc
  }

  /**
   * Ends a presorted map for a [keyBlock]-registered type. The key vector is written
   * once per (type, buffer) and shared by every map of that type — later maps skip
   * the key-vector write and its width loop entirely.
   *
   * @param blockBlob the same blob instance passed to [keyBlock] (identity key)
   * @param keyOffsets the array returned by [keyBlock]
   */
  public fun endMapKeyed(start: Int, keyOffset: Int, blockBlob: ByteArray, keyOffsets: IntArray): Int {
    val length = stack.size - start
    if (length != keyOffsets.size) {
      error("endMapKeyed: pushed $length values for a ${keyOffsets.size}-key block — generated coders must emit every field (nulls included)")
    }
    var slot = -1
    for (i in 0 until kbCount) {
      if (kbBlobs[i] === blockBlob) {
        slot = i
        break
      }
    }
    val kvLoc: Int
    val kvWidth: Int
    if (slot >= 0 && kbVectorLoc[slot] >= 0) {
      kvLoc = kbVectorLoc[slot]
      kvWidth = kbVectorWidth[slot]
    } else {
      createKeyVectorFromOffsets(keyOffsets, length)
      kvLoc = keyScratch.iValue.toInt()
      kvWidth = 1 shl keyScratch.minBitWidth.value
      if (slot >= 0) {
        kbVectorLoc[slot] = kvLoc
        kbVectorWidth[slot] = kvWidth
      }
    }
    val vec = putMapVector(keyOffset, start, length, kvLoc, kvWidth)
    stack.clearFrom(start)
    stack.push(vec.type, vec.key, vec.minBitWidth, vec.iValue)
    return vec.iValue.toInt()
  }

  // ─── End key-block fast path ───

  private fun isKeySorted(start: Int): Boolean {
    val end = stack.size
    if (end - start <= 1) return true
    for (i in start until end - 1) {
      if (stack.compareKeys(i, i + 1, ba) >= 0) return false
    }
    return true
  }

  private inline fun setTypedVector(
    key: String? = null,
    length: Int,
    vecType: FlexBufferType,
    bitWidth: BitWidth,
    crossinline writeBlock: (Int) -> Unit,
  ): Int {
    val keyPos = putKey(key)
    val byteWidth = align(bitWidth)
    // write the size
    putW(length.toLong(), byteWidth)

    // Then the actual data.
    val vloc: Int = pos
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

  private fun putKey(key: String? = null): Int {
    if (key == null) return -1
    return if ((shareFlag and SHARE_KEYS) != 0) {
      val cached = stringKeyPool[key]
      if (cached != null) return cached
      val keyPos = writeKeyBytes(key)
      stringKeyPool[key] = keyPos
      keyPos
    } else {
      writeKeyBytes(key)
    }
  }

  private fun writeKeyBytes(key: String): Int {
    val start = pos
    val encodedKeySize = fastEncodedLength(key)
    ensure(encodedKeySize + 1)
    fastEncodeUtf8(key, ba, pos)
    pos += encodedKeySize
    ba[pos] = ZeroByte
    pos++
    return start
  }

  private fun writeString(key: Int, s: String): Value {
    val encodedSize = fastEncodedLength(s)
    val bitWidth = encodedSize.toULong().widthInUBits()
    val byteWidth = align(bitWidth)

    putW(encodedSize.toLong(), byteWidth)

    ensure(encodedSize + 1)
    val sloc: Int = pos
    if (encodedSize > 0) {
      fastEncodeUtf8(s, ba, pos)
      pos += encodedSize
    }
    ba[pos] = ZeroByte
    pos++
    return scratch.also { it.type = T_STRING; it.key = key; it.minBitWidth = bitWidth; it.iValue = sloc.toULong() }
  }

  private fun writeBlob(
    key: Int,
    blob: ByteArray,
    type: FlexBufferType,
    trailing: Boolean,
  ): Value {
    val bitWidth = blob.size.toULong().widthInUBits()
    val byteWidth = align(bitWidth)

    putW(blob.size.toLong(), byteWidth)

    ensure(blob.size + 1)
    val sloc: Int = pos
    blob.copyInto(ba, pos)
    pos += blob.size
    if (trailing) {
      ba[pos] = ZeroByte
      pos++
    }
    return scratch.also { it.type = type; it.key = key; it.minBitWidth = bitWidth; it.iValue = sloc.toULong() }
  }

  private fun calculateKeyVectorBitWidth(start: Int, length: Int): BitWidth {
    val bitWidth = length.toULong().widthInUBits()
    var width = bitWidth
    val prefixElems = 1
    for (i in start until stack.size) {
      val ew = elemWidth(T_KEY, W_8, stack.key(i).toLong(), pos, i + prefixElems)
      width = width.max(ew)
    }
    return width
  }

  private inline fun <T> collectionWidthInUBits(
    values: Collection<T>,
    crossinline valueBlock: (T) -> Long,
  ): BitWidth {
    var bitWidth = W_8.max(values.size.toULong().widthInUBits())
    values.forEach { bitWidth = bitWidth.max(valueBlock(it).signedWidthInUBits()) }
    return bitWidth
  }

  // Reusable scratch for key vector results to avoid allocation in endMap
  private val keyScratch = Value()

  private fun createKeyVector(start: Int, length: Int) {
    val bitWidth = calculateKeyVectorBitWidth(start, length)
    val byteWidth = align(bitWidth)
    putW(length.toLong(), byteWidth)
    val vloc = pos
    ensure(length * byteWidth)
    for (i in start until stack.size) {
      val keyPos = stack.key(i)
      if (keyPos == -1) error("invalid position $keyPos for key")
      putWRaw((pos - keyPos).toLong(), byteWidth)
    }
    keyScratch.also { it.type = T_VECTOR_KEY; it.key = -1; it.minBitWidth = bitWidth; it.iValue = vloc.toULong() }
  }

  private fun createKeyVectorFromOffsets(keyOffsets: IntArray, length: Int) {
    var bitWidth = length.toULong().widthInUBits()
    val prefixElems = 1
    for (i in 0 until length) {
      val ew = elemWidth(T_KEY, W_8, keyOffsets[i].toLong(), pos, i + prefixElems)
      bitWidth = bitWidth.max(ew)
    }
    val byteWidth = align(bitWidth)
    putW(length.toLong(), byteWidth)
    val vloc = pos
    ensure(length * byteWidth)
    for (i in 0 until length) {
      putWRaw((pos - keyOffsets[i]).toLong(), byteWidth)
    }
    keyScratch.also { it.type = T_VECTOR_KEY; it.key = -1; it.minBitWidth = bitWidth; it.iValue = vloc.toULong() }
  }

  private fun createVector(key: Int, start: Int, length: Int): Value {
    return createAnyVector(key, start, length, T_VECTOR, true)
  }

  private fun createTypedVector(key: Int, start: Int, length: Int): Value {
    val elementType: FlexBufferType = stack.type(start)
    for (i in start + 1 until length) {
      if (elementType != stack.type(i))
        error("TypedVector does not support array of different element types")
    }
    if (!elementType.isTypedVectorElementType())
      error("TypedVector does not support this element type")
    return createAnyVector(key, start, length, elementType.toTypedVector(), false)
  }

  /** Writes the stack slice [start, stack.size) as a vector, with optional type bytes. */
  private fun createAnyVector(
    key: Int,
    start: Int,
    length: Int,
    type: FlexBufferType,
    writeTypeBytes: Boolean,
  ): Value {
    var bitWidth = W_8.max(length.toULong().widthInUBits())
    val prefixElems = 1
    for (i in start until stack.size) {
      val ew = stack.elemWidth(i, pos, i + prefixElems)
      bitWidth = bitWidth.max(ew)
    }
    val byteWidth = align(bitWidth)
    putW(length.toLong(), byteWidth)

    val vloc: Int = pos
    ensure(length * byteWidth + length)
    for (i in start until stack.size) {
      writeAnyAtRaw(i, byteWidth)
    }

    if (writeTypeBytes) {
      val bw = BitWidth(bitWidth.value)
      for (i in start until stack.size) {
        ba[pos] = stack.storedPackedType(i, bw)
        pos++
      }
    }
    return scratch.also { it.type = type; it.key = key; it.minBitWidth = bitWidth; it.iValue = vloc.toULong() }
  }

  /** Writes the stack slice as a map vector with a key-vector prefix. */
  private fun putMapVector(
    key: Int,
    start: Int,
    length: Int,
    keyVecLoc: Int,
    keyVecWidth: Int,
  ): Value {
    var bitWidth = W_8.max(length.toULong().widthInUBits())
    var prefixElems = 3
    // The key vector offset and its width are part of the map header; both must fit.
    bitWidth = bitWidth.max(elemWidth(T_VECTOR_KEY, W_8, keyVecLoc.toLong(), pos, 0))
    bitWidth = bitWidth.max(keyVecWidth.toULong().widthInUBits())
    for (i in start until stack.size) {
      val ew = stack.elemWidth(i, pos, i + prefixElems)
      bitWidth = bitWidth.max(ew)
    }
    val byteWidth = align(bitWidth)
    ensure(byteWidth * 3)
    putWRaw((pos - keyVecLoc).toLong(), byteWidth)
    putWRaw(keyVecWidth.toLong(), byteWidth)
    putWRaw(length.toLong(), byteWidth)

    val vloc: Int = pos
    ensure(length * byteWidth + length)
    for (i in start until stack.size) {
      writeAnyAtRaw(i, byteWidth)
    }

    val bw = BitWidth(bitWidth.value)
    for (i in start until stack.size) {
      ba[pos] = stack.storedPackedType(i, bw)
      pos++
    }
    return scratch.also { it.type = T_MAP; it.key = key; it.minBitWidth = bitWidth; it.iValue = vloc.toULong() }
  }

  /** Writes stack entry [i] as a [byteWidth]-wide slot. Ensures capacity itself. */
  private fun writeAnyAt(i: Int, byteWidth: Int) {
    ensure(byteWidth)
    writeAnyAtRaw(i, byteWidth)
  }

  /** Writes stack entry [i] as a [byteWidth]-wide slot. Caller must [ensure]. */
  private inline fun writeAnyAtRaw(i: Int, byteWidth: Int) {
    val t = stack.type(i)
    when (t) {
      T_NULL, T_BOOL, T_INT, T_UINT -> putWRaw(stack.iValueBits(i), byteWidth)
      T_FLOAT -> {
        if (byteWidth == 8) {
          ba.stF64(pos, stack.dValue(i))
          pos += 8
        } else {
          ba.stF32(pos, stack.dValue(i).toFloat())
          pos += byteWidth
        }
      }
      else -> putWRaw((pos - stack.iValueBits(i).toInt()).toLong(), byteWidth)
    }
  }

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
    fun iValueBits(i: Int) = iVals[i]
    fun dValue(i: Int) = dVals[i]

    fun elemWidth(i: Int, bufSize: Int, elemIndex: Int): BitWidth =
      dev.shibasis.reaktor.flexbuffer.flatbuffers.elemWidth(type(i), minBitWidth(i), iVals[i], bufSize, elemIndex)

    fun storedPackedType(i: Int, parentBW: BitWidth = W_8): Byte {
      val storedBW = if (type(i).isInline()) minBitWidth(i).max(parentBW) else minBitWidth(i)
      return (storedBW.value or (types[i] shl 2)).toByte()
    }

    fun clearFrom(start: Int) { size = start }
    fun clear() { size = 0 }

    fun compareKeys(a: Int, b: Int, ba: ByteArray): Int =
      compareKeyToRaw(a, keys[b], ba)

    private fun compareKeyToRaw(a: Int, rawKeyPos: Int, ba: ByteArray): Int {
      var ia = keys[a]
      var ib = rawKeyPos
      var c1: Byte
      var c2: Byte
      do {
        c1 = ba[ia]
        c2 = ba[ib]
        if (c1.toInt() == 0) return c1 - c2
        ia++; ib++
      } while (c1 == c2)
      return c1 - c2
    }

    fun sortByKeys(start: Int, ba: ByteArray) {
      val end = size - 1
      if (end <= start) return
      quickSortByKeys(start, end, ba)
    }

    private fun quickSortByKeys(left: Int, right: Int, ba: ByteArray) {
      if (right - left <= 12) {
        insertionSortByKeys(left, right, ba)
        return
      }

      var i = left
      var j = right
      val pivotKey = keys[(left + right) ushr 1]
      while (i <= j) {
        while (compareKeyToRaw(i, pivotKey, ba) < 0) i++
        while (compareKeyToRaw(j, pivotKey, ba) > 0) j--
        if (i <= j) {
          swap(i, j)
          i++
          j--
        }
      }
      if (left < j) quickSortByKeys(left, j, ba)
      if (i < right) quickSortByKeys(i, right, ba)
    }

    private fun insertionSortByKeys(left: Int, right: Int, ba: ByteArray) {
      for (i in left + 1..right) {
        var j = i
        while (j > left && compareKeys(j - 1, j, ba) > 0) {
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
