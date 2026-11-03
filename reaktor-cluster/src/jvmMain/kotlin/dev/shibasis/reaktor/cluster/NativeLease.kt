package dev.shibasis.reaktor.cluster

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path

class NativeLease private constructor(private val descriptor: Int) : AutoCloseable {
    private var closed = false
    override fun close() {
        if (!closed) { closed = true; nativeClose.invokeWithArguments(descriptor) }
    }

    companion object {
        private val linker = Linker.nativeLinker()
        private val symbols = linker.defaultLookup()
        private val darwin = System.getProperty("os.name").startsWith("Mac")
        private val nativeOpen = linker.downcallHandle(symbols.find("open").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2))
        private val nativeClose = linker.downcallHandle(symbols.find("close").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT))
        private val nativeFlock = linker.downcallHandle(symbols.find("flock").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
        private val nativeErrno = linker.downcallHandle(symbols.find(if (darwin) "__error" else "__errno_location").orElseThrow(), FunctionDescriptor.of(ADDRESS))

        fun tryAcquire(path: Path, create: Boolean = true): NativeLease? {
            if (create) Files.createDirectories(path.toAbsolutePath().parent)
            return Arena.ofConfined().use { arena ->
                val descriptor = nativeOpen.invokeWithArguments(arena.allocateFrom(path.toAbsolutePath().toString()),
                    if (create) (if (darwin) 0x202 else 0x42) else 0, 384) as Int
                if (descriptor < 0) throw IOException("Cannot open lease $path")
                if (nativeFlock.invokeWithArguments(descriptor, 6) as Int == 0) NativeLease(descriptor)
                else {
                    val errno = (nativeErrno.invokeWithArguments() as MemorySegment).reinterpret(4).get(JAVA_INT, 0)
                    nativeClose.invokeWithArguments(descriptor)
                    if (errno == 11 || errno == 35) null else throw IOException("Cannot inspect lease $path (errno $errno)")
                }
            }
        }

        fun inspect(path: Path): Lease {
            if (Files.notExists(path)) return Lease(path.toString(), "Free")
            if (!Files.exists(path)) return Lease(path.toString(), "Unknown", truth = "Unknown")
            return try {
                val held = tryAcquire(path, create = false)
                if (held == null) Lease(path.toString(), "Held") else { held.close(); Lease(path.toString(), "Free") }
            } catch (_: Exception) { Lease(path.toString(), "Unknown", truth = "Unknown") }
        }
    }
}
