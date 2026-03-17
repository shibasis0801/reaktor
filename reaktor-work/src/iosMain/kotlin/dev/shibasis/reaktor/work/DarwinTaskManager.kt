package dev.shibasis.reaktor.work

import dev.mattramotar.meeseeks.runtime.AppContext

private object DarwinAppContext : AppContext()

class DarwinTaskManager : TaskManager<Unit>(Unit) {
    override fun createAppContext(): AppContext = DarwinAppContext
}
