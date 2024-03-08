package dev.shibasis.flatinvoker.core


expect object FlexBuffer {
    inline fun Create(): Long
    inline fun ParseJson(pointer: Long, data: String)
    inline fun Destroy(pointer: Long)
    inline fun Finish(pointer: Long)
    inline fun GetBuffer(pointer: Long): ByteArray
    inline fun Null(pointer: Long, key: String?)
    inline fun Int(pointer: Long, key: String?, value: Long)
    inline fun Float(pointer: Long, key: String?, value: Float)
    inline fun Double(pointer: Long, key: String?, value: Double)
    inline fun Bool(pointer: Long, key: String?, value: Boolean)
    inline fun String(pointer: Long, key: String?, value: String)
    inline fun Blob(pointer: Long, key: String?, value: ByteArray)
    inline fun StartMap(pointer: Long, key: String?): ULong
    inline fun EndMap(pointer: Long, mapStart: ULong)
    inline fun StartVector(pointer: Long, key: String?): ULong
    inline fun EndVector(pointer: Long, vectorStart: ULong)
}