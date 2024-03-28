package dev.shibasis.flatinvoker.core


expect object FlexBuffer {
    fun Create(): Long
    fun ParseJson(pointer: Long, data: String): Long
    fun Destroy(pointer: Long)
    fun Finish(pointer: Long): Long
    fun GetBuffer(pointer: Long): ByteArray
    fun Null(pointer: Long, key: String?)
    fun Int(pointer: Long, key: String?, value: Long)
    fun Float(pointer: Long, key: String?, value: Float)
    fun Double(pointer: Long, key: String?, value: Double)
    fun Bool(pointer: Long, key: String?, value: Boolean)
    fun String(pointer: Long, key: String?, value: String)
    fun Blob(pointer: Long, key: String?, value: ByteArray)
    fun StartMap(pointer: Long, key: String?): Long
    fun EndMap(pointer: Long, mapStart: Long)
    fun StartVector(pointer: Long, key: String?): Long
    fun EndVector(pointer: Long, vectorStart: Long)
}