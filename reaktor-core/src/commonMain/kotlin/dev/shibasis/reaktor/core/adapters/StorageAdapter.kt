package dev.shibasis.reaktor.core.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.Feature

abstract class StorageAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    abstract fun test(): Int
//    fun getHomeDirectory(): String = ""
}

private val storageId = Feature.createId()
var Feature.Storage: StorageAdapter<*>?
    get() = fetchDependency(storageId)
    set(storageAdapter) = storeDependency(storageId, storageAdapter)