package dev.shibasis.reaktor.work

import android.content.Context
import dev.mattramotar.meeseeks.runtime.AppContext

class AndroidTaskManager(
    context: Context
) : TaskManager<Context>(context.applicationContext) {
    override fun createAppContext(): AppContext = controller!!
}
