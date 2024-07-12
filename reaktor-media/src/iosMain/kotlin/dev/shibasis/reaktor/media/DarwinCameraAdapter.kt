package dev.shibasis.reaktor.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.framework.Feature
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureSession
import platform.UIKit.UIViewController
import platform.AVFoundation.AVCaptureDeviceDiscoverySession.Companion.discoverySessionWithDeviceTypes
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDeviceInput.Companion.deviceInputWithDevice
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTrueDepthCamera
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIView
import platform.darwin.NSObject

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

    fun cameraInput(cameraDevice: AVCaptureDevice): AVCaptureDeviceInput? {
        return try {
            deviceInputWithDevice(cameraDevice, null)
        } catch (exception: Exception) {
            null
        }
    }


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
