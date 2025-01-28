package dev.shibasis.reaktor.navigation.structs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.reflect.KProperty

class Observable<T>(data: T) {
    private val listeners = arrayListOf<(T) -> Unit>()
    var value: T = data
        set(data) {
            if (field == data) return
            field = data
            listeners.forEach { it(data) }
        }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return value
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
    }

    fun addListener(listener: (T) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (T) -> Unit) {
        listeners.remove(listener)
    }

    fun<R> map(transform: (T) -> R): Observable<R> {
        val observable = Observable(transform(value))
        addListener { observable.value = transform(it) }
        return observable
    }
    // add others as needed. flows are good but async, and async isn't always appropriate.
}

@Composable
fun<T> Observable<T>.collectAsState(): State<T> {
    val state = remember { mutableStateOf(value) }
    val listener = { it: T -> state.value = it }
    DisposableEffect(this) {
        addListener(listener)
        onDispose { removeListener(listener) }
    }
    return state
}

