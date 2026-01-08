package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// If you need to extend another class, then store a reference to it inside your adapter.
open class Adapter<Controller>(
    controller: Controller
): Component {
    val ref: WeakRef<Controller> = WeakRef(controller)
    val controller: Controller? get() = ref.get()

    operator fun<Result> invoke(function: Controller.() -> Result?): Result? = ref.get()?.function()

    fun<Data> invokeResult(function: Controller.() -> Result<Data>): Result<Data> = ref.get()?.function() ?: Result.failure(NULL_CONTROLLER)

    suspend fun<Result> suspended(function: suspend Controller.() -> Result?): Result? = ref.get()?.function()

    suspend fun<Data> suspendedResult(function: suspend Controller.() -> Result<Data>): Result<Data> = ref.get()?.function() ?: Result.failure(NULL_CONTROLLER)

    fun handle(controller: Controller, event: ControllerEvent) {}

    override fun handle(event: ControllerEvent) {
        invoke { handle(this, event) }
    }

    val NULL_CONTROLLER = Error("Controller can't be null")
    fun <T> nullControllerResult(): Result<T> = Result.failure(NULL_CONTROLLER)
}
































