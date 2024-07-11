package app.mehmaan.media.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import app.mehmaan.core.adapters.Permission
import app.mehmaan.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureSession
import platform.UIKit.UIViewController
import platform.AVFoundation.AVCaptureDeviceDiscoverySession.Companion.discoverySessionWithDeviceTypes
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDeviceInput.Companion.deviceInputWithDevice
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTrueDepthCamera
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.AVFoundation.fileDataRepresentation
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIImage
import platform.UIKit.UIView
import platform.darwin.NSObject

object CameraDelegate : NSObject(), AVCapturePhotoCaptureDelegateProtocol {

}

// Support multiple cameras, and multiple areas can call this module
// https://github.com/JetBrains/compose-multiplatform/blob/master/examples/imageviewer/shared/src/iosMain/kotlin/example/imageviewer/view/CameraView.ios.kt
class DarwinCameraAdapter(
    viewController: UIViewController
): CameraAdapter<UIViewController>(viewController) {
    val captureSession = AVCaptureSession()
    val previewLayer = AVCaptureVideoPreviewLayer(session = captureSession)
    val photoOutput = AVCapturePhotoOutput().apply {
        setHighResolutionCaptureEnabled(true)
    }

    fun frontCamera(): AVCaptureDevice {
        return discoverySessionWithDeviceTypes(
            listOf(AVCaptureDeviceTypeBuiltInTrueDepthCamera),
            AVMediaTypeVideo,
            AVCaptureDevicePositionFront
        ).devices.first() as AVCaptureDevice
    }

    @OptIn(ExperimentalForeignApi::class)
    fun cameraInput(cameraDevice: AVCaptureDevice): AVCaptureDeviceInput? {
        return try {
            deviceInputWithDevice(cameraDevice, null)
        } catch (exception: Exception) {
            null
        }
    }


    @OptIn(ExperimentalForeignApi::class)
    fun listenForRotation() {
        val RotationListener = object : NSObject() {
            fun onChange(arg: NSNotification) {
                val connection = previewLayer.connection ?: return

                when(UIDevice.currentDevice.orientation) {
                    UIDeviceOrientation.UIDeviceOrientationPortrait -> {
                        connection.setVideoOrientation(AVCaptureVideoOrientationPortrait)
                    }
                    UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> {
                        connection.setVideoOrientation(AVCaptureVideoOrientationPortraitUpsideDown)
                    }
                    UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> {
                        connection.setVideoOrientation(AVCaptureVideoOrientationLandscapeLeft)
                    }
                    UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> {
                        connection.setVideoOrientation(AVCaptureVideoOrientationLandscapeRight)
                    }
                    else -> {
                        connection.setVideoOrientation(AVCaptureVideoOrientationPortrait)
                    }
                }

            }
        }

        val notificationName = platform.UIKit.UIDeviceOrientationDidChangeNotification
        NSNotificationCenter.defaultCenter.addObserver(
            observer = RotationListener,
            selector = NSSelectorFromString(
                RotationListener::onChange.name + ":"
            ),
            name = notificationName,
            `object` = null
        )

//            NSNotificationCenter.defaultCenter.removeObserver(
//                observer = listener,
//                name = notificationName,
//                `object` = null
//            )
    }


    fun setupCamera(): CameraStart {
        val input = cameraInput(frontCamera())
        captureSession.setSessionPreset(AVCaptureSessionPresetPhoto)

        if (input != null && captureSession.canAddInput(input)) {
            captureSession.addInput(input)
        }
        if (captureSession.canAddOutput(photoOutput)) {
            captureSession.addOutput(photoOutput)
        }
        captureSession.startRunning()
        listenForRotation()
        return CameraStart.Success
    }

    override suspend fun start(): CameraStart {
        val permission = Feature.Permission ?: return CameraStart.PermissionFailure

        if (permission.request(Permission.CAMERA)) {
            return setupCamera()
        }

        return CameraStart.PermissionFailure
    }

    @OptIn(ExperimentalForeignApi::class)
    @Composable
    override fun Render() {
        LaunchedEffect(Unit) {
            start()
        }
        UIKitView(
            modifier = Modifier.fillMaxSize(),
            background = Color.Black,
            factory = {
                val container = UIView()
                previewLayer.setOrientation(AVCaptureVideoOrientationLandscapeRight)
                previewLayer.setVideoGravity(AVLayerVideoGravityResizeAspectFill)
                previewLayer.setFrame(container.bounds)
                container.layer.addSublayer(previewLayer)
                container
            },
            onResize = { view, rect ->
                CATransaction.begin()
                CATransaction.setValue(true, kCATransactionDisableActions)
                view.layer.setFrame(rect)
                previewLayer.setFrame(rect)
                CATransaction.commit()
            }
        )
    }
}
