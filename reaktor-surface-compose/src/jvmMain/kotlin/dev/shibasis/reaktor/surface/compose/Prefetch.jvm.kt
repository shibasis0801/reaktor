package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.LocalPlatformPrefetchScheduler
import androidx.compose.ui.platform.PlatformPrefetchRequest
import androidx.compose.ui.platform.PlatformPrefetchRequestScope
import androidx.compose.ui.platform.PlatformPrefetchScheduler
import javax.swing.SwingUtilities

@OptIn(InternalComposeUiApi::class)
@Composable
internal actual fun Prefetching(content: @Composable () -> Unit) {
    if (!SwingUtilities.isEventDispatchThread()) return content()
    val scope = rememberCoroutineScope()
    val scheduler = remember(scope) { Prefetcher(FramePrefetch(scope) { SwingUtilities.invokeLater(it) }) }
    CompositionLocalProvider(LocalPlatformPrefetchScheduler provides scheduler, content = content)
}

@OptIn(InternalComposeUiApi::class)
private class Prefetcher(private val frames: FramePrefetch) : PlatformPrefetchScheduler, PlatformPrefetchRequestScope {
    override fun availableTimeNanos() = frames.left()
    override fun scheduleHighPriorityPrefetch(request: PlatformPrefetchRequest) = frames.schedule(true) { with(request) { execute() } }
    override fun scheduleLowPriorityPrefetch(request: PlatformPrefetchRequest) = frames.schedule(false) { with(request) { execute() } }
}
