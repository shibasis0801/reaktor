package dev.shibasis.reaktor.core.framework

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface DependencyModule {
    fun createId(): Int
    fun <T> storeDependency(id: Int, dependency: T)
    fun <T> fetchDependency(id: Int): T?
}


/*
Service locator to store dependencies.
Will see if DI is actually needed and if it can work without making code complex.
 */
object Feature: DependencyModule {
    private var moduleIdx = AtomicInt(0)
    // not thread safe, replace this.
    private val moduleMap = hashMapOf<Int, Any>()
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
}

// Create a slot for a Feature, you will need to set it somewhere.
class CreateSlot<T>: ReadWriteProperty<Any, T?> {
    private val id = Feature.createId()
    override fun getValue(thisRef: Any, property: KProperty<*>) = Feature.fetchDependency<T>(id)
    override fun setValue(thisRef: Any, property: KProperty<*>, value: T?) =
        Feature.storeDependency(id, value)
}
