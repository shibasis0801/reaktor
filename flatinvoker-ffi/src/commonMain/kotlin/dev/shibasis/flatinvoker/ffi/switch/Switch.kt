package dev.shibasis.flatinvoker.ffi.switch

import com.google.flatbuffers.kotlin.Vector
import dev.shibasis.flatinvoker.ffi.Invokable
import dev.shibasis.flatinvoker.ffi.payload.moduleName
import dev.shibasis.flatinvoker.ffi.payload.toFlexPayload
import kotlinx.coroutines.flow.Flow

class ModuleMap: MutableMap<String, Invokable> by hashMapOf()

object Switch {
    val moduleMap = ModuleMap()

    fun installModule(moduleName: String, invokable: Invokable) {
        moduleMap[moduleName] = invokable
    }

    fun installModules(vararg modules: Pair<String, Invokable>) {
        modules.forEach { (moduleName, invokable) ->
            moduleMap[moduleName] = invokable
        }
    }

    private fun fetchModule(flexBuffer: ByteArray): Pair<Vector, Invokable> {
        val payload = flexBuffer.toFlexPayload()
        val module = moduleMap[payload.moduleName] ?: throw IllegalArgumentException("Module not found")
        return payload to module
    }

    fun invokeSync(flexBuffer: ByteArray): Long {
        val (vector, invokable) = fetchModule(flexBuffer)
        return invokable.invokeSync(vector)
    }

    fun invokeAsync(flexBuffer: ByteArray): Flow<Long> {
        val (vector, invokable) = fetchModule(flexBuffer)
        return invokable.invokeAsync(vector)
    }
}

/*


*/