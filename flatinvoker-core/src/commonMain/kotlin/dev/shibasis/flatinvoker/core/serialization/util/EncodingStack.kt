package dev.shibasis.flatinvoker.core.serialization.util

import kotlin.jvm.JvmInline
import kotlin.time.measureTime

/*
This is the relevant part of the serializer for Maps
override fun serialize(encoder: Encoder, value: Collection) {
        val size = value.collectionSize()
        encoder.encodeCollection(descriptor, size) {
            val iterator = value.collectionIterator()
            var index = 0
            iterator.forEach { (k, v) ->
                encodeSerializableElement(descriptor, index++, keySerializer, k)
                encodeSerializableElement(descriptor, index++, valueSerializer, v)
                // This pushes the following 4 elements in the serialization queue
                // [ index, key, index + 1, value ]
            }
        }
    }

{ 101: { key1: 11, key2: 12 }, 202: { key1: 21, key2: 22 } }
This map will result in the following structure
[ index, key, index + 1, value ]

[ 0, 101, 1, [ 0, key1, 1, 11, 2, key2, 3, 12 ], 2, 202, 3, [ 0, key1, 1, 21, 2, key2, 3, 22 ]]

And the following series of pseudo calls.

beginMap()
encodeElement(0)
encodeValue(101) <- Key
encodeElement(1)

beginMap()
encodeElement(0)
encodeValue(key1) <- Key
encodeElement(1)
encodeValue(11)
encodeElement(2)
encodeValue(key2) <- Key
encodeElement(3)
encodeValue(12)
endMap()

encodeElement(2)
encodeValue(202) <- Key
encodeElement(3)

beginMap()
encodeElement(0)
encodeValue(key1) <- Key
encodeElement(1)
encodeValue(21)
encodeElement(2)
encodeValue(key2) <- Key
encodeElement(3)
encodeValue(22)
endMap()

endMap()

We now need to find the sequence of calls where encodeValue actually sends the key.
Store them for using as keys when actual values arrive.

Values can be anything, them being a map should not have any special use.
But different methods are called for encoding different types.
So lets solve it for a simpler but equally applicable case.

{ key1: 101, key2: 202, key3: 303 }

 Function Call        |         Encoder Memory           |       Operation

encodeElement(0)      |     ( idx = 0 , field = n )      |       idx += 1
encodeValue(key1)     |     ( idx = 1 , field = n )      |       field = f
encodeElement(1)      |     ( idx = 1 , field = f )      |       idx += 1
encodeValue(101)      |     ( idx = 2 , field = f )      |       field = n
encodeElement(2)      |     ( idx = 2 , field = n )      |       idx += 1
encodeValue(key2)     |     ( idx = 3 , field = n )      |       field = f
encodeElement(3)      |     ( idx = 3 , field = f )      |       idx += 1
encodeValue(202)      |     ( idx = 4 , field = f )      |       field = n
encodeElement(4)      |     ( idx = 4 , field = n )      |       idx += 1
encodeValue(key3)     |     ( idx = 5 , field = n )      |       field = f
encodeElement(5)      |     ( idx = 5 , field = f )      |       idx += 1
encodeValue(303)      |     ( idx = 6 , field = f )      |       field = n

But encodeValue is only for primitives, there are other methods to encode composites/nulls.
So either we hook in every such method.

Or we hook in encodeElement which is guaranteed for any value of any type.
But it does make our code a bit more weird.

encodeElement it is.
Updated operation table

 Function Call        |         Encoder Memory           |       Operation

encodeElement(0)      |     ( idx = 0 , field = n )      |       idx += 1
encodeValue(key1)     |     ( idx = 1 , field = n )      |       field = f
encodeElement(1)      |     ( idx = 1 , field = f )      |       idx += 1
encodeValue(101)      |     ( idx = 2 , field = f )      |
encodeElement(2)      |     ( idx = 2 , field = f )      |       idx += 1, field = n
encodeValue(key2)     |     ( idx = 3 , field = n )      |       field = f
encodeElement(3)      |     ( idx = 3 , field = f )      |       idx += 1
encodeValue(202)      |     ( idx = 4 , field = f )      |
encodeElement(4)      |     ( idx = 4 , field = f )      |       idx += 1, field = n
encodeValue(key3)     |     ( idx = 5 , field = n )      |       field = f
encodeElement(5)      |     ( idx = 5 , field = f )      |       idx += 1
encodeValue(303)      |     ( idx = 6 , field = f )      |


So let's write down the conditions,

encodeElement:
if (idx % 2 == 0 && field != null)
    field = null
idx += 1

encodeValue:
if (idx % 2 == 1 && field == null)
    field = value




*/

enum class Composite {
    Map, Vector, Class
}

class CompositePosition(
    var type: Composite = Composite.Map,
    var position: Long = 0L,
    var fieldName: String? = null,
    var idx: Int = 0
)

// Uses Object Pooling and is much faster than ArrayDeque.
class CompositePositionStack(initialCapacity: Int = 16) {
    // todo profiler marked slow, array index bound check takes half the time of a get operation
    var stack: ArrayList<CompositePosition> = ArrayList()
    var size: Int = 0
    var capacity: Int = 0
    inline fun expandCapacityBy(additionalCapacity: Int) {
        capacity += additionalCapacity
        repeat(additionalCapacity) {
            stack.add(CompositePosition())
        }
    }
    init {
        expandCapacityBy(initialCapacity)
    }

    inline fun push(type: Composite, position: Long, fieldName: String? = null, idx: Int = 0) {
        if (size >= capacity) {
            expandCapacityBy(capacity)
        }
        val compositePosition = stack[size]
        compositePosition.type = type
        compositePosition.position = position
        compositePosition.fieldName = fieldName
        compositePosition.idx = idx
        size++
    }

    inline fun pop(): CompositePosition {
        return stack[--size]
    }

    inline fun top(): CompositePosition? {
        return if (size > 0) stack[size - 1] else null
    }

    inline fun isEmpty(): Boolean {
        return size == 0
    }

    inline fun clear() {
        size = 0
    }

}


// todo when first call arrives, need to push the appropriate stack entry
class EncodingStack {
    // As we need to encode nested structures, we push a stack frame as we enter a new structure (list/map/class/etc)
    val stack = CompositePositionStack()
    // Reference to the current structure in the stack
    var current: CompositePosition? = null

    inline fun push(composite: Composite, start: Long) {
        stack.push(composite, start)
        current = stack.top()
    }

    inline fun pop(): CompositePosition {
        val result = stack.pop()
        current = stack.top()
        return result
    }

    // todo profiler marked slow
    val field: String?
        get() = current?.fieldName

    /*
    encodeElement:
    if (idx % 2 == 0 && field != null)
        field = null
    idx += 1

    encodeValue:
    if (idx % 2 == 1 && field == null)
        field = value
     */
    inline fun onEncodeElement(name: String) {
        val active = current ?: return
        if (active.type == Composite.Vector) return

        if (active.type == Composite.Class) {
            active.fieldName = name
            return
        }

        if (active.idx % 2 == 0 && active.fieldName != null) {
            active.fieldName = null
        }
        active.idx += 1
    }

    // Should return a sealed class with actions to be taken back in the encoder
    inline fun onEncodeValue(value: Any): Boolean {
        val active = current ?: return false
        if (active.type != Composite.Map) return false


        if (active.idx % 2 == 1 && active.fieldName == null) {
            active.fieldName = value.toString()
            return true
        }

        return false
    }
}