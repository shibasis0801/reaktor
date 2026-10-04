package dev.shibasis.reaktor.devtools

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.darwin.CTL_KERN
import platform.darwin.KERN_PROC
import platform.darwin.KERN_PROC_PID
import platform.darwin.kinfo_proc
import platform.darwin.sysctl
import platform.posix.getpid
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun millisSinceProcessStart(): Double? = memScoped {
    val info = alloc<kinfo_proc>()
    val length = alloc<size_tVar>().apply { value = sizeOf<kinfo_proc>().convert() }
    val name = intArrayOf(CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()).toCValues()
    if (sysctl(name, 4u, info.ptr, length.ptr, null, 0u) != 0) return@memScoped null
    val started = info.kp_proc.p_un.__p_starttime
    NSDate().timeIntervalSince1970 * 1000.0 - (started.tv_sec * 1000.0 + started.tv_usec / 1000.0)
}
