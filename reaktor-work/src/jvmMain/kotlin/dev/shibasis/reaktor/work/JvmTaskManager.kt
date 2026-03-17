package dev.shibasis.reaktor.work

import dev.mattramotar.meeseeks.runtime.AppContext

private object JvmAppContext : AppContext()

class JvmTaskManager : TaskManager<Unit>(Unit) {
    override fun createAppContext(): AppContext = JvmAppContext
}
