package app.mehmaan.media.camera

import androidx.compose.runtime.Composable
import app.mehmaan.navigation.screen.Props
import dev.shibasis.reaktor.framework.Feature

@Composable
fun CameraScreen(props: Props) {
    Feature.Camera?.Render()
}
