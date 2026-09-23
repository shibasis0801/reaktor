package dev.shibasis.reaktor.core.framework

import dev.shibasis.reaktor.core.structs.ConcurrentHashMap
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface DependencyModule: AutoCloseable {
    fun createId(): Int
    fun <T> storeDependency(id: Int, dependency: T)
    fun <T> fetchDependency(id: Int): T?
}

/*
Default global dependency module.
Will see if DI is actually needed and if it can work without making code complex.
todo errors must be shown on a error screen like react native
 */
object Feature: DependencyModule {
    private var moduleIdx = AtomicInt(0)
    private var moduleMap = ConcurrentHashMap<Int, Any>()
    override fun createId() = moduleIdx.getAndIncrement()
    override fun <T> storeDependency(id: Int, dependency: T) {
        // Null clears the slot rather than crashing on the cast. `CreateSlot` is a
        // `ReadWriteProperty<Any, T?>`, so `Feature.Something = null` type-checks everywhere and
        // used to throw a NullPointerException out of this line — which meant a slot could be
        // filled and never emptied. A test that installs an adapter cannot then take it away, so
        // every later test in the process runs against it; a shell tearing down cannot let go of
        // one either.
        if (dependency == null) {
            moduleMap.remove(id)
            return
        }
        moduleMap[id] = dependency as Any
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> fetchDependency(id: Int): T? {
        return moduleMap[id]?.let {
            it as? T ?: throw ClassCastException("The module is not of the expected type.")
        }
    }

    override fun close() {
        moduleMap = ConcurrentHashMap()
    }
}

// Create a slot for a Feature, you will need to set it somewhere.
class CreateSlot<T>(
    private val dependencyModule: DependencyModule = Feature
): ReadWriteProperty<Any, T?> {
    val id = dependencyModule.createId()
    override fun getValue(thisRef: Any, property: KProperty<*>) =
        dependencyModule.fetchDependency<T>(id)
    override fun setValue(thisRef: Any, property: KProperty<*>, value: T?) =
        dependencyModule.storeDependency(id, value)
}
