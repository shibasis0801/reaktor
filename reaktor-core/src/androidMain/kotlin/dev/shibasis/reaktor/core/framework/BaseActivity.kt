package dev.shibasis.reaktor.core.framework

import android.app.Activity
import androidx.activity.ComponentActivity

abstract class BaseActivity: ComponentActivity() {
    val adapters = mutableListOf<Adapter<ComponentActivity>>()

    fun connect(adapterList: List<Adapter<ComponentActivity>>) {
        adapters.addAll(adapterList)
        adapterList.forEach(lifecycle::addObserver)
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
