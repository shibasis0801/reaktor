package dev.shibasis.reaktor.work

import dev.mattramotar.meeseeks.runtime.AppContext

private object JsAppContext : AppContext()

class JsTaskManager : TaskManager<Unit>(Unit) {
    override fun createAppContext(): AppContext = JsAppContext
}
