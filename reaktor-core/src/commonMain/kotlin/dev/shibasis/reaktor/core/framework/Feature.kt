package dev.shibasis.reaktor.core.framework

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface DependencyModule: AutoCloseable {
    fun createId(): Int
    fun <T> storeDependency(id: Int, dependency: T)
    fun <T> fetchDependency(id: Int): T?
}

// Create a slot for a Feature, you will need to set it somewhere.
class CreateSlot<T>(
    private val dependencyModule: DependencyModule = Feature
): ReadWriteProperty<Any, T?> {
    val id = dependencyModule.createId()
    override fun getValue(thisRef: Any, property: KProperty<*>) = dependencyModule.fetchDependency<T>(id)
    override fun setValue(thisRef: Any, property: KProperty<*>, value: T?) =
        dependencyModule.storeDependency(id, value)
}

/*
Default global dependency module.
Will see if DI is actually needed and if it can work without making code complex.
 */
object Feature: DependencyModule {
    private var moduleIdx = AtomicInt(0)
    // not thread safe, replace this.
    private var moduleMap = hashMapOf<Int, Any>()
    override fun createId() = moduleIdx.getAndIncrement()
    override fun <T> storeDependency(id: Int, dependency: T) {
        moduleMap[id] = dependency as Any
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> fetchDependency(id: Int): T? {
        return moduleMap[id]?.let {
            it as? T ?: throw ClassCastException("The module is not of the expected type.")
        }
    }

    override fun close() {
        moduleMap = hashMapOf()
    }
}