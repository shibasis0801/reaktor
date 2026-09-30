package dev.shibasis.reaktor.conductor.appserver

import java.util.concurrent.TimeUnit

internal fun stopNativeProcess(process: Process) {
    val children = process.descendants().use { it.toList() }
    children.asReversed().forEach { it.destroy() }
    process.destroy()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    children.filter { it.isAlive }.forEach { it.destroyForcibly() }
}
