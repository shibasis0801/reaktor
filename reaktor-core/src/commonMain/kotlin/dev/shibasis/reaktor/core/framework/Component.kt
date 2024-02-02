package dev.shibasis.reaktor.core.framework


/*
A component receives events from Controllers (activity, application, viewcontroller, window, etc)
It is sealed so that you don't subclass it (visibility modifiers weren't working)
You should always inherit Component instead of ComponentContract
Without componentContract you would have to mandatorily implement handle function
*/
expect sealed interface ControllerEventObserver {
    fun handle(event: ControllerEvent)
}

interface Component: ControllerEventObserver {
    override fun handle(event: ControllerEvent) {}
}