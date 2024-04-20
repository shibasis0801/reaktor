package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

interface AdapterContract<Controller>: Component {
    val ref: WeakRef<Controller>
    val controller: Controller?
        get() = ref.get()
    operator fun<Result> invoke(function: Controller.() -> Result?): Result? = ref.get()?.function()
    suspend fun<Result> invokeSuspend(function: suspend Controller.() -> Result?): Result? = ref.get()?.function()
    fun handle(controller: Controller, event: ControllerEvent) {}
    override fun handle(event: ControllerEvent) {
        invoke { handle(this, event) }
    }
}

open class Adapter<Controller>(controller: Controller): AdapterContract<Controller> {
    override val ref = WeakRef(controller)
    val scope = CoroutineScope(Dispatchers.Main)
}
