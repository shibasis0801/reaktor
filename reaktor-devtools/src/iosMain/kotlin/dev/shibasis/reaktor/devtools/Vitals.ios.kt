package dev.shibasis.reaktor.devtools

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.QuartzCore.CADisplayLink
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.NSObject
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_basic_info
import platform.darwin.mach_task_self_
import platform.darwin.sel_registerName
import platform.darwin.task_info

/**
 * Frame timing from `CADisplayLink`, which fires in step with the display.
 *
 * The Compose canvas on iOS draws on the same link, so a gap measured here is a gap the user saw.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
private class DisplayLinkTarget(
    private val onFrame: (frameNanos: Long, intervalNanos: Long) -> Unit,
) : NSObject() {
    private var previous = 0.0

    @kotlinx.cinterop.ObjCAction
    fun step(link: CADisplayLink) {
        val timestamp = link.timestamp
        if (previous != 0.0) {
            val nanos = ((timestamp - previous) * 1_000_000_000).toLong()
            onFrame(nanos, nanos)
        }
        previous = timestamp
    }
}

private object DarwinFrameVitals : FrameVitalsSource {
    override fun start(onFrame: (frameNanos: Long, intervalNanos: Long) -> Unit): Cancellable {
        val target = DisplayLinkTarget(onFrame)
        val link = CADisplayLink.displayLinkWithTarget(target, sel_registerName("step:"))
        link.addToRunLoop(platform.Foundation.NSRunLoop.mainRunLoop, platform.Foundation.NSRunLoopCommonModes)
        return Cancellable { link.invalidate() }
    }
}

/**
 * Resident memory from `task_info`.
 *
 * `phys_footprint` is the number Xcode's memory gauge shows and the one the jetsam killer reads,
 * which makes it the only figure worth reporting: a Kotlin/Native heap number would be a fraction
 * of what actually gets the app terminated.
 */
private object DarwinMemoryVitals : MemoryVitalsSource {
    override fun read(): MemoryReading? = memScoped {
        val info = alloc<mach_task_basic_info>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<mach_task_basic_info>() / 4).toUInt()
        val result = task_info(
            mach_task_self_,
            MACH_TASK_BASIC_INFO.toUInt(),
            info.ptr.reinterpret(),
            count.ptr,
        )
        if (result != 0) return@memScoped null
        MemoryReading(
            usedBytes = info.resident_size.toLong(),
            totalBytes = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong(),
            nativeBytes = info.resident_size.toLong(),
        )
    }
}

actual fun frameVitalsSource(): FrameVitalsSource = DarwinFrameVitals

actual fun memoryVitalsSource(): MemoryVitalsSource = DarwinMemoryVitals

actual fun crashStore(applicationId: String): CrashStore =
    DarwinCrashStore(NSTemporaryDirectory() + "reaktor-devtools/" + applicationId)

private class DarwinCrashStore(private val directory: String) : CrashStore {
    private val manager = NSFileManager.defaultManager

    override fun write(report: String) {
        manager.createDirectoryAtPath(directory, true, null, null)
        val path = "$directory/crash-${(NSDate().timeIntervalSince1970 * 1000).toLong()}.txt"
        (report as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    override fun readAll(): List<String> {
        val names = manager.contentsOfDirectoryAtPath(directory, null).orEmpty()
            .filterIsInstance<String>()
            .filter { it.startsWith("crash-") }
            .sorted()
        return names.mapNotNull { name ->
            NSString.stringWithContentsOfFile("$directory/$name", NSUTF8StringEncoding, null)
        }
    }

    override fun clear() {
        manager.contentsOfDirectoryAtPath(directory, null).orEmpty()
            .filterIsInstance<String>()
            .filter { it.startsWith("crash-") }
            .forEach { manager.removeItemAtPath("$directory/$it", null) }
    }
}

/**
 * Catches unhandled Kotlin exceptions.
 *
 * Objective-C exceptions and Unix signals need `NSSetUncaughtExceptionHandler` and a signal
 * handler respectively, which are separate mechanisms with their own hazards; this covers the
 * Kotlin half, which is where the app's own code fails.
 */
actual fun installCrashHandler(agent: DevToolsAgent, store: CrashStore): Cancellable? {
    val previous = kotlin.native.setUnhandledExceptionHook { throwable ->
        store.write(
            buildCrashReport(
                kind = throwable::class.qualifiedName ?: "Throwable",
                message = throwable.message.orEmpty(),
                stack = throwable.stackTraceToString(),
                threadName = "native",
                epochMillis = (NSDate().timeIntervalSince1970 * 1000).toLong(),
                context = agent.crashContext(),
            )
        )
        throw throwable
    }
    return Cancellable { previous?.let { kotlin.native.setUnhandledExceptionHook(it) } }
}
