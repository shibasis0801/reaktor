@file:Suppress("KotlinJniMissingFunction")

package dev.shibasis.flatinvoker.core

import com.facebook.jni.DestructorThread
import com.google.flatbuffers.FlexBuffersBuilder
import dalvik.annotation.optimization.FastNative
import io.ktor.util.moveToByteArray
import java.nio.ByteBuffer
import java.util.Stack
import kotlin.time.measureTime


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
        measureTime {

        }
    }

}


/*
On JVM, we can use the C++ as well as the Java implementation.
Which is faster needs to be benchmarked. (without jni overhead c++ is faster, but combined unsure)

For consistency, kotlin on ios, android and in cpp, we use the C++ implementation

Individual functions seem to be 1-3 times slower than pure kotlin. (ok ok)
todo Try to see if CriticalNative works.
*/
actual object FlexBuffer {
    init {
        System.loadLibrary("FlatInvokerCore")
    }
    @FastNative actual external fun Create(): Long
    @FastNative actual external fun ParseJson(pointer: Long, data: String): Long
    @FastNative actual external fun Destroy(pointer: Long)
    @FastNative actual external fun Finish(pointer: Long): Long
    @FastNative external fun jniGetBuffer(pointer: Long): ByteBuffer
    @FastNative actual external fun Null(pointer: Long, key: String?)
    @FastNative actual external fun Int(pointer: Long, key: String?, value: Long)
    @FastNative actual external fun Float(pointer: Long, key: String?, value: Float)
    @FastNative actual external fun Double(pointer: Long, key: String?, value: Double)
    @FastNative actual external fun Bool(pointer: Long, key: String?, value: Boolean)
    @FastNative actual external fun String(pointer: Long, key: String?, value: String)
    @FastNative actual external fun Blob(pointer: Long, key: String?, value: ByteArray)
    @FastNative actual external fun StartMap(pointer: Long, key: String?): Long
    @FastNative actual external fun EndMap(pointer: Long, mapStart: Long)
    @FastNative actual external fun StartVector(pointer: Long, key: String?): Long
    @FastNative actual external fun EndVector(pointer: Long, vectorStart: Long)

    actual inline fun GetBuffer(pointer: Long): ByteArray {
        // todo Need to understand bytebuffers in more details
        // it seems that the underlying array is not directly accessible without a copy.
        // that is, zero copy direct byte buffers are one way.
        // https://www.baeldung.com/java-bytebuffer

        return jniGetBuffer(pointer).moveToByteArray()
    }

///////////////////////////////-- Pure Java Implementation --///////////////////////////////////
// Twice as slow as C++ (with JNI overhead)

//    var builder = FlexBuffersBuilder(1024)
//
//    actual inline fun Create(): Long {
//        builder = FlexBuffersBuilder(1024)
//        return 0
//    }
//
//    actual inline fun ParseJson(pointer: Long, data: String): Long {
//        // Not directly supported by FlexBuffersBuilder
////        throw UnsupportedOperationException()
//        return 0
//
//    }
//
//    actual inline fun Destroy(pointer: Long) {
//        // Not directly supported by FlexBuffersBuilder
////        throw UnsupportedOperationException()
//    }
//
//    actual inline fun Finish(pointer: Long): Long {
//        builder.finish()
//        return 0
//    }
//
//    actual inline fun GetBuffer(pointer: Long): ByteArray {
//        return builder.buffer.data()
//    }
//
//    actual inline fun Null(pointer: Long, key: String?) {
////        throw NullPointerException()
//    }
//
//    actual inline fun Int(pointer: Long, key: String?, value: Long) {
//        builder.putInt(key, value)
//    }
//
//    actual inline fun Float(pointer: Long, key: String?, value: Float) {
//        builder.putFloat(key, value)
//    }
//
//    actual inline fun Double(pointer: Long, key: String?, value: Double) {
//        builder.putFloat(key, value)
//    }
//
//    actual inline fun Bool(pointer: Long, key: String?, value: Boolean) {
//        builder.putBoolean(key, value)
//    }
//
//    actual inline fun String(pointer: Long, key: String?, value: String) {
//        builder.putString(key, value)
//    }
//
//    actual inline fun Blob(pointer: Long, key: String?, value: ByteArray) {
//        builder.putBlob(key, value)
//    }
//
//    val mapStack = Stack<String>()
//    actual inline fun StartMap(pointer: Long, key: String?): Long {
//        mapStack.push(key)
//        return builder.startMap().toLong()
//    }
//
//    actual inline fun EndMap(pointer: Long, mapStart: Long) {
//        builder.endMap(mapStack.pop(), mapStart.toInt())
//    }
//
//    val vectorStack = Stack<String>()
//    actual inline fun StartVector(pointer: Long, key: String?): Long {
//        vectorStack.push(key)
//        return builder.startVector().toLong()
//    }
//
//    actual inline fun EndVector(pointer: Long, vectorStart: Long) {
//        builder.endVector(vectorStack.pop(), vectorStart.toInt(), false, false)
//    }
}