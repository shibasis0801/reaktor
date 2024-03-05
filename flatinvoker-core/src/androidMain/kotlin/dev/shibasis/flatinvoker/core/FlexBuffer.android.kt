package dev.shibasis.flatinvoker.core


/*
On JVM, we can use the C++ as well as the Java implementation.
Which is faster needs to be benchmarked. (without jni overhead c++ is faster, but combined unsure)

For consistency, kotlin on ios, android and in cpp, we use the C++ implementation
*/
actual object FlexBuffer {
    actual inline fun Create(): FlexPointer {
        return 0u
    }
    actual inline fun ParseJson(pointer: FlexPointer, data: String) {

    }

    actual inline fun Destroy(pointer: FlexPointer) {

    }

    actual inline fun Finish(pointer: FlexPointer) {

    }

    actual inline fun GetBuffer(pointer: FlexPointer): ByteArray {
        return byteArrayOf()
    }

    actual inline fun Null(pointer: FlexPointer, key: String?) {

    }
    actual inline fun Int(pointer: FlexPointer, key: String?, value: Long) {

    }

    actual inline fun Float(pointer: FlexPointer, key: String?, value: Float) {

    }

    actual inline fun Double(pointer: FlexPointer, key: String?, value: Double) {

    }

    actual inline fun Bool(pointer: FlexPointer, key: String?, value: Boolean) {

    }

    actual inline fun String(pointer: FlexPointer, key: String?, value: String) {

    }

    actual inline fun Blob(pointer: FlexPointer, key: String?, value: ByteArray) {

    }

    actual inline fun StartMap(pointer: FlexPointer, key: String?): ULong {
        return 0U
    }

    actual inline fun EndMap(pointer: FlexPointer, mapStart: ULong) {

    }

    actual inline fun StartVector(pointer: FlexPointer, key: String?): ULong {
        return 0U
    }

    actual inline fun EndVector(pointer: FlexPointer, vectorStart: ULong) {

    }
}