package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers

// If you need to extend another class, then store a reference to it inside your adapter.
open class Adapter<Controller>(
    controller: Controller,
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
): Component {
    val ref: WeakRef<Controller> = WeakRef(controller)
    val controller: Controller? get() = ref.get()

    operator fun<Result> invoke(function: Controller.() -> Result?): Result? = ref.get()?.function()

    suspend fun<Result> invokeSuspend(function: suspend Controller.() -> Result?): Result? = ref.get()?.function()

    fun handle(controller: Controller, event: ControllerEvent) {}

    override fun handle(event: ControllerEvent) {
        invoke { handle(this, event) }
    }

    val NULL_CONTROLLER = Error("Controller can't be null")
    fun <T> nullControllerResult(): Result<T> = Result.failure(NULL_CONTROLLER)
}
































