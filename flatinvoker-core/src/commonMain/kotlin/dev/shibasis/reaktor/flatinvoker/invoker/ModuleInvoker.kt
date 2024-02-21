package dev.shibasis.reaktor.flatinvoker.invoker

import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.ReadWriteBuffer
import com.google.flatbuffers.kotlin.Reference
import com.google.flatbuffers.kotlin.Vector
import dev.shibasis.reaktor.core.annotations.Expose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 *
 * Basic Design
 *
 * Expo JSI Modules + FlexBuffers + PlatformInvoker + Flow
 *
 * Return a flexbuf ModuleDescriptor to bind the module at C++
 *
 * Types are converted from jsi::Value/Array/Object to FlexBuffers
 *
 * Deserialization is done with Kotlinx-serialization to actual values.
 * Even primitives are currently converted into flexbuffers
 *
 * Further optimisations will be needed, but priority is functionality first
 */


@Expose
object NetworkModule {
    fun get() = 1
    fun getFlow() = flowOf(1)
}

interface ReaktorModule {
    val name: String
}

object NetworkImplementation: ReaktorModule {
    override val name = "NetworkModule"

    val timeout = 1000
    fun get() = 1
    fun getFlow() = flowOf(1)
}


data class ModuleDescriptor(
    val name: String,
    val syncMethods: Array<String>,
    val asyncMethods: Array<String>
) {
    fun toFlexBuffer(): ReadBuffer? {
        return null
    }

    fun Function(name: String, functionInvoker: (Vector) -> ReadBuffer) {

    }

    fun FlowableFunction(name: String, functionInvoker: (Vector) -> Flow<ReadBuffer>) {

    }

    companion object {
        fun fromFlexBuffer() {

        }
    }
}

fun Module(name: String, moduleBuilder: ModuleDescriptor.() -> Unit): ModuleDescriptor {
    return ModuleDescriptor("", arrayOf(), arrayOf())
}

interface FlexModule {
    fun getDescriptor(): ModuleDescriptor
}


object DatabaseModule: FlexModule {
    override fun getDescriptor() = Module("DatabaseModule") {
        Function("get") {
            val x = it[0].isInt
            ArrayReadWriteBuffer()
        }

        FlowableFunction("getFlow") {
            flowOf(ArrayReadWriteBuffer())
        }
    }
}

inline fun Reference.getString(): String {
    if (isString) return ""
    else throw Error()
}

inline fun Reference.getInt(): Int {
    if (isInt) return 1
    else throw Error()
}

inline fun<reified T> Reference.getArray(): Array<T> {
    return arrayOf()
}

inline fun<reified T> Reference.getArrayList(): ArrayList<T> {
    return arrayListOf()
}

inline fun<reified K, reified V> Reference.getMap(): Map<K, V> {
    return hashMapOf()
}



fun invoke(ref: Reference) {
    example(ref.getString(), ref.getInt(), ref.getArrayList(), ref.getMap())
}


fun example(name: String, age: Int, friends: ArrayList<String>, subjectMarks: Map<String, Double>) {
    /*
       vector<String>(0), vector<Int>(1), vector<ArrayList<String>>(2), vector<Map<String, Double>>(3)
     */
}
