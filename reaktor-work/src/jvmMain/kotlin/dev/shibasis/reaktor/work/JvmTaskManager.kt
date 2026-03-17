package dev.shibasis.reaktor.work

import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.ConfigurationScope

private object JvmAppContext : AppContext()

class JvmTaskManager(
    configure: ConfigurationScope.() -> Unit = {}
) : TaskManager<Unit>(Unit, configure) {
    override fun createAppContext(): AppContext = JvmAppContext
}
