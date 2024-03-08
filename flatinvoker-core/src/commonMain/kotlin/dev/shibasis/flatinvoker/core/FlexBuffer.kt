package dev.shibasis.flatinvoker.core


typealias FlexPointer = Long
expect object FlexBuffer {
    inline fun Create(): FlexPointer
    inline fun ParseJson(pointer: FlexPointer, data: String)
    inline fun Destroy(pointer: FlexPointer)
    inline fun Finish(pointer: FlexPointer)
    inline fun GetBuffer(pointer: FlexPointer): ByteArray
    inline fun Null(pointer: FlexPointer, key: String?)
    inline fun Int(pointer: FlexPointer, key: String?, value: Long)
    inline fun Float(pointer: FlexPointer, key: String?, value: Float)
    inline fun Double(pointer: FlexPointer, key: String?, value: Double)
    inline fun Bool(pointer: FlexPointer, key: String?, value: Boolean)
    inline fun String(pointer: FlexPointer, key: String?, value: String)
    inline fun Blob(pointer: FlexPointer, key: String?, value: ByteArray)
    inline fun StartMap(pointer: FlexPointer, key: String?): ULong
    inline fun EndMap(pointer: FlexPointer, mapStart: ULong)
    inline fun StartVector(pointer: FlexPointer, key: String?): ULong
    inline fun EndVector(pointer: FlexPointer, vectorStart: ULong)
}