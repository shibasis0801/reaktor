package dev.shibasis.reaktor.core.framework

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

abstract class BaseActivity: ComponentActivity() {
    val adapters = mutableListOf<Adapter<ComponentActivity>>()

    fun connect(adapterList: List<Adapter<ComponentActivity>>) {
        adapters.addAll(adapterList)
        adapterList.forEach(lifecycle::addObserver)
    }

    inline fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        crossinline block: suspend CoroutineScope.() -> Unit
    ): Job {
        return lifecycleScope.launch(context, start) {
            block()
        }
    }


    inline fun<T> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        crossinline block: suspend CoroutineScope.() -> T
    ): Deferred<T> {
        return lifecycleScope.async(context, start) {
            block()
        }
    }

    /**
     * Prefer this function to connect your adapters
     * onDestroy removes all adapters
     * You can add adapters in multiple steps
     * And can also do eager disconnect
     */
    fun connect(vararg adapters: Adapter<ComponentActivity>) = connect(adapters.toList())

    fun disconnect(adapterList: List<Adapter<ComponentActivity>>) {
        this.adapters.removeAll(adapterList)
        adapterList.forEach(lifecycle::removeObserver)
    }

    fun disconnect(vararg adapters: Adapter<ComponentActivity>) = disconnect(adapters.toList())

    override fun onDestroy() {
        super.onDestroy()
        disconnect(adapters)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        adapters.forEach { it.handle(ControllerEvent.BackPressed) }
    }
}
