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
    @FastNative external fun nativeCreate(): Long
    actual inline fun Create() = nativeCreate()
    }
    @FastNative external fun jniParseJson(pointer: FlexPointer, data: String)
    actual inline fun ParseJson(pointer: FlexPointer, data: String) = jniParseJson(pointer, data)
    @FastNative external fun jniDestroy(pointer: FlexPointer)
    actual inline fun Destroy(pointer: FlexPointer) = jniDestroy(pointer)
    @FastNative external fun jniFinish(pointer: FlexPointer)
    actual inline fun Finish(pointer: FlexPointer) = jniFinish(pointer)
    @FastNative external fun jniGetBuffer(pointer: FlexPointer): ByteBuffer
    actual inline fun GetBuffer(pointer: FlexPointer): ByteArray {
        return jniGetBuffer(pointer).array()
    }
    @FastNative external fun jniNull(pointer: FlexPointer, key: String?)
    actual inline fun Null(pointer: FlexPointer, key: String?) = jniNull(pointer, key)
    @FastNative external fun jniInt(pointer: FlexPointer, key: String?, value: Long)
    actual inline fun Int(pointer: FlexPointer, key: String?, value: Long) = jniInt(pointer, key, value)
    @FastNative external fun jniFloat(pointer: FlexPointer, key: String?, value: Float)
    actual inline fun Float(pointer: FlexPointer, key: String?, value: Float) = jniFloat(pointer, key, value)
    @FastNative external fun jniDouble(pointer: FlexPointer, key: String?, value: Double)
    actual inline fun Double(pointer: FlexPointer, key: String?, value: Double) = jniDouble(pointer, key, value)
    @FastNative external fun jniBool(pointer: FlexPointer, key: String?, value: Boolean)
    actual inline fun Bool(pointer: FlexPointer, key: String?, value: Boolean) = jniBool(pointer, key, value)
    @FastNative external fun jniString(pointer: FlexPointer, key: String?, value: String)
    actual inline fun String(pointer: FlexPointer, key: String?, value: String) = jniString(pointer, key, value)
    @FastNative external fun jniBlob(pointer: FlexPointer, key: String?, value: ByteArray)
    actual inline fun Blob(pointer: FlexPointer, key: String?, value: ByteArray) = jniBlob(pointer, key, value)
    @FastNative external fun jniStartMap(pointer: FlexPointer, key: String?): Long
    actual inline fun StartMap(pointer: FlexPointer, key: String?) = jniStartMap(pointer, key)
    @FastNative external fun jniEndMap(pointer: FlexPointer, mapStart: ULong)
    actual inline fun EndMap(pointer: FlexPointer, mapStart: ULong) = jniEndMap(pointer, mapStart)
    @FastNative external fun jniStartVector(pointer: FlexPointer, key: String?): ULong
    actual inline fun StartVector(pointer: FlexPointer, key: String?) = jniStartVector(pointer, key)
    @FastNative external fun jniEndVector(pointer: FlexPointer, vectorStart: ULong)
    actual inline fun EndVector(pointer: FlexPointer, vectorStart: ULong) = jniEndVector(pointer, vectorStart)
}