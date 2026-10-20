package dev.shibasis.reaktor.blueprint

import androidx.compose.ui.ImageComposeScene
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS

internal class MetalTarget(width: Int, height: Int) : AutoCloseable {
    private val context: DirectContext
    private val surface: Surface

    init {
        val linker = Linker.nativeLinker()
        val arena = Arena.global()
        SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", arena)
        val metal = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", arena)
        val objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena)
        val device = linker.downcallHandle(metal.find("MTLCreateSystemDefaultDevice").get(), FunctionDescriptor.of(ADDRESS)).invokeWithArguments() as MemorySegment
        check(device.address() != 0L) { "No Metal device" }
        val selector = linker.downcallHandle(objc.find("sel_registerName").get(), FunctionDescriptor.of(ADDRESS, ADDRESS)).invokeWithArguments(arena.allocateFrom("newCommandQueue")) as MemorySegment
        val queue = linker.downcallHandle(objc.find("objc_msgSend").get(), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)).invokeWithArguments(device, selector) as MemorySegment
        check(queue.address() != 0L) { "No Metal command queue" }
        context = DirectContext.makeMetal(device.address(), queue.address())
        surface = Surface.makeRenderTarget(context, false, ImageInfo.makeN32Premul(width, height))
    }

    fun attach(scene: ImageComposeScene) {
        ImageComposeScene::class.java.getDeclaredField("surface").apply { isAccessible = true }.set(scene, surface)
    }

    fun finish() = context.flushAndSubmit(surface, true)

    override fun close() = context.close()
}
