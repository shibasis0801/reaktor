package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

interface AdapterContract<Controller>: Component {
    val ref: WeakRef<Controller>
    operator fun<Result> invoke(function: Controller.() -> Result?): Result? = ref.get()?.function()
    suspend fun<Result> invokeSuspend(function: suspend Controller.() -> Result?): Result? = ref.get()?.function()
    fun handle(controller: Controller, event: ControllerEvent)
    override fun handle(event: ControllerEvent) {
        invoke { handle(this, event) }
    }
}

open class Adapter<Controller>(controller: Controller): AdapterContract<Controller> {
    override val ref = WeakRef(controller)
    constructor(controllerRef: WeakRef<Controller>): this(controllerRef.get() ?: throw NullPointerException("Controller is null"))
    val controller: Controller?
        get() = ref.get()

    val scope = CoroutineScope(Dispatchers.Main)
    override fun handle(controller: Controller, event: ControllerEvent) {}
}
