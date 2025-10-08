package dev.shibasis.reaktor.core.framework

import dev.shibasis.reaktor.core.getShibasisUserAgent

actual sealed interface ControllerEventObserver {
    actual fun handle(event: ControllerEvent)
}


@JsExport
fun getPatnaikUserAgent(): String = getShibasisUserAgent()