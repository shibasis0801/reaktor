package dev.shibasis.reaktor.media

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.media.camera.Camera
import dev.shibasis.reaktor.navigation.route.Props

@Composable
fun CameraScreen(props: Props) {
    Feature.Camera?.Render()
}
