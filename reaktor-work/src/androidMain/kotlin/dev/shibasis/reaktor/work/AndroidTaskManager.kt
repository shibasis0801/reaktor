package dev.shibasis.reaktor.work

import android.content.Context
import dev.mattramotar.meeseeks.runtime.AppContext
import dev.mattramotar.meeseeks.runtime.ConfigurationScope

class AndroidTaskManager(
    context: Context,
    configure: ConfigurationScope.() -> Unit = {}
) : TaskManager<Context>(context.applicationContext, configure) {
    override fun createAppContext(): AppContext = controller!!
}
