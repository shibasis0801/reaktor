package com.myntra.appscore.batcave.types


import com.myntra.appscore.batcave.concurrency.dispatchMain
import kotlinx.coroutines.flow.MutableStateFlow


class ReactFlow<T>(val state: MutableStateFlow<T>) {
    private val observers = arrayListOf<((T) -> Any)>()

    init {
        dispatchMain {
            state.collect { data ->
                observers.forEach { it(data) }
            }
        }

    }

    fun listen(observer: ((T) -> Any)) {
        observers.add(observer)
    }

    fun update(value: T) {
        state.value = value
    }
}