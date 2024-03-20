package dev.shibasis.flatinvoker.core

import com.facebook.jni.DestructorThread
import dalvik.annotation.optimization.FastNative
import io.ktor.util.moveToByteArray
import java.nio.ByteBuffer




/*
If we wish to pass an array from C++ to Java without a copy
Then we need to allocate the array on cpp in heap. (KeepAlive)
Then wrap it in a direct byte buffer
When the dbb is used and no longer needed
We need to remove the memory in cpp heap
For that we need to maintain the pointer here
Reinterpret cast back in cpp
And call delete[]

For example: NetworkModule


// first call
SQLDelight.contains(request) => NO
buffer = FlexBuffer.Parse(response)
SQLDelight.store(buffer)
attempt zero-copy pass to JSI

// next call
SQLDelight.contains(request) => YES
zero-copy pass to cpp.
attempt zero-copy pass to JSI
*/

class CppArray(ref: Any): DestructorThread.Destructor(ref) {
    override fun destruct() {
        TODO("Not yet implemented")
    }

}


/*
On JVM, we can use the C++ as well as the Java implementation.
Which is faster needs to be benchmarked. (without jni overhead c++ is faster, but combined unsure)

For consistency, kotlin on ios, android and in cpp, we use the C++ implementation
*/
actual object FlexBuffer {
    init {
        System.loadLibrary("FlatInvokerCore")
    }
    @FastNative external fun jniCreate(): Long
    @FastNative external fun jniParseJson(pointer: Long, data: String): Long
    @FastNative external fun jniDestroy(pointer: Long)
    @FastNative external fun jniFinish(pointer: Long): Long
    @FastNative external fun jniGetBuffer(pointer: Long): ByteBuffer
    @FastNative external fun jniNull(pointer: Long, key: String?)
    @FastNative external fun jniInt(pointer: Long, key: String?, value: Long)
    @FastNative external fun jniFloat(pointer: Long, key: String?, value: Float)
    @FastNative external fun jniDouble(pointer: Long, key: String?, value: Double)
    @FastNative external fun jniBool(pointer: Long, key: String?, value: Boolean)
    @FastNative external fun jniString(pointer: Long, key: String?, value: String)
    @FastNative external fun jniBlob(pointer: Long, key: String?, value: ByteArray)
    @FastNative external fun jniStartMap(pointer: Long, key: String?): Long
    @FastNative external fun jniEndMap(pointer: Long, mapStart: Long)
    @FastNative external fun jniStartVector(pointer: Long, key: String?): Long
    @FastNative external fun jniEndVector(pointer: Long, vectorStart: Long)
    actual inline fun Create(): Long {
        return jniCreate()
    }

    actual inline fun ParseJson(pointer: Long, data: String): Long {
        return jniParseJson(pointer, data)
    }

    actual inline fun Destroy(pointer: Long) {
        jniDestroy(pointer)
    }

    actual inline fun Finish(pointer: Long): Long {
        return jniFinish(pointer)
    }

    actual inline fun GetBuffer(pointer: Long): ByteArray {
        // todo Need to understand bytebuffers in more details
        // it seems that the underlying array is not directly accessible without a copy.
        // that is, zero copy direct byte buffers are one way.
        // https://www.baeldung.com/java-bytebuffer

        return jniGetBuffer(pointer).moveToByteArray()
    }

    actual inline fun Null(pointer: Long, key: String?) {
        jniNull(pointer, key)
    }

    actual inline fun Int(pointer: Long, key: String?, value: Long) {
        jniInt(pointer, key, value)
    }

    actual inline fun Float(pointer: Long, key: String?, value: Float) {
        jniFloat(pointer, key, value)
    }

    actual inline fun Double(pointer: Long, key: String?, value: Double) {
        jniDouble(pointer, key, value)
    }

    actual inline fun Bool(pointer: Long, key: String?, value: Boolean) {
        jniBool(pointer, key, value)
    }

    actual inline fun String(pointer: Long, key: String?, value: String) {
        jniString(pointer, key, value)
    }

    actual inline fun Blob(pointer: Long, key: String?, value: ByteArray) {
        // should send bytebuffer instead
        jniBlob(pointer, key, value)
    }

    actual inline fun StartMap(pointer: Long, key: String?): ULong {
        return jniStartMap(pointer, key).toULong()
    }

    actual inline fun EndMap(pointer: Long, mapStart: ULong) {
        jniEndMap(pointer, mapStart.toLong())
    }

    actual inline fun StartVector(pointer: Long, key: String?): ULong {
        return jniStartVector(pointer, key).toULong()
    }

    actual inline fun EndVector(pointer: Long, vectorStart: ULong) {
        jniEndVector(pointer, vectorStart.toLong())
    }
}