package dev.shibasis.reaktor.work

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.ConfigurationScope

private object DarwinAppContext : AppContext()

class DarwinTaskManager(
    configure: ConfigurationScope.() -> Unit = {}
) : TaskManager<Unit>(Unit, configure) {
    override fun createAppContext(): AppContext = DarwinAppContext
}
