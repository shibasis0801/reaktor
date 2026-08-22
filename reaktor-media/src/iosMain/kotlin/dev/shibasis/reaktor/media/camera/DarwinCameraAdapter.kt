package dev.shibasis.reaktor.media.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.framework.Async
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.media.gallery.MediaPick
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession.Companion.discoverySessionWithDeviceTypes
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDeviceInput.Companion.deviceInputWithDevice
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.position
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
class DarwinCameraAdapter(
    viewController: UIViewController,
) : CameraAdapter<UIViewController>(viewController) {

    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    val previewLayer = AVCaptureVideoPreviewLayer(session = session)

    private var currentInput: AVCaptureDeviceInput? = null
    private var rotationListener: NSObject? = null
    // AVCapturePhotoOutput holds its delegate weakly; without this the capture callback never fires.
    private var captureDelegate: AVCapturePhotoCaptureDelegateProtocol? = null
    private val sessionLock = Mutex()

    private fun device(facing: CameraFacing): AVCaptureDevice? {
        val position = when (facing) {
            CameraFacing.Back -> AVCaptureDevicePositionBack
            CameraFacing.Front -> AVCaptureDevicePositionFront
        }
        val devices = discoverySessionWithDeviceTypes(
            listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera),
            AVMediaTypeVideo,
            position,
        ).devices.filterIsInstance<AVCaptureDevice>()
        // Wide-angle at the requested position, else whatever this device actually has (iPads and
        // the simulator do not always offer both).
        return devices.firstOrNull { it.position == position } ?: devices.firstOrNull()
    }

    override suspend fun start(facing: CameraFacing): CameraStart {
        val permission = Feature.Permission ?: return CameraStart.PermissionFailure
        if (!permission.request(Permission.CAMERA)) return CameraStart.PermissionFailure

        return sessionLock.withLock {
            val device = device(facing) ?: return@withLock CameraStart.CameraFailure
            val input = runCatching { deviceInputWithDevice(device, null) }.getOrNull()
                ?: return@withLock CameraStart.CameraFailure

            withContext(Dispatchers.Async) {
                session.beginConfiguration()
                session.setSessionPreset(AVCaptureSessionPresetPhoto)
                currentInput?.let(session::removeInput)
                if (session.canAddInput(input)) {
                    session.addInput(input)
                    currentInput = input
                }
                if (!session.outputs.contains(photoOutput) && session.canAddOutput(photoOutput)) {
                    session.addOutput(photoOutput)
                }
                session.commitConfiguration()
                if (!session.isRunning()) session.startRunning()
            }

            if (currentInput !== input) {
                Logger.e { "AVCaptureSession refused the $facing input" }
                return@withLock CameraStart.CameraFailure
            }

            listenForRotation()
            setFacing(facing)
            CameraStart.Success
        }
    }

    @OptIn(BetaInteropApi::class)
    override suspend fun capturePhoto(): MediaPick? = suspendCancellableCoroutine { continuation ->
        val delegate = object : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
            override fun captureOutput(
                output: AVCapturePhotoOutput,
                didFinishProcessingPhoto: AVCapturePhoto,
                error: NSError?,
            ) {
                captureDelegate = null
                if (error != null) {
                    Logger.e { "AVCapture failed: ${error.localizedDescription}" }
                    if (continuation.isActive) continuation.resume(null)
                    return
                }
                val data = didFinishProcessingPhoto.fileDataRepresentation()
                if (data == null) {
                    Logger.e { "AVCapture produced no file representation" }
                    if (continuation.isActive) continuation.resume(null)
                    return
                }
                if (continuation.isActive) {
                    continuation.resume(MediaPick(bytes = data.toByteArray(), mimeType = JPEG))
                }
            }
        }

        captureDelegate = delegate
        photoOutput.capturePhotoWithSettings(AVCapturePhotoSettings(), delegate)

        continuation.invokeOnCancellation { captureDelegate = null }
    }

    override suspend fun stop() {
        sessionLock.withLock {
            withContext(Dispatchers.Async) {
                if (session.isRunning()) session.stopRunning()
            }
            rotationListener?.let {
                NSNotificationCenter.defaultCenter.removeObserver(
                    observer = it,
                    name = UIDeviceOrientationDidChangeNotification,
                    `object` = null,
                )
            }
            rotationListener = null
            captureDelegate = null
        }
    }

    @OptIn(BetaInteropApi::class)
    private fun listenForRotation() {
        if (rotationListener != null) return

        val listener = object : NSObject() {
            @Suppress("unused")
            fun onChange(arg: NSNotification) {
                val connection = previewLayer.connection ?: return
                connection.setVideoOrientation(
                    when (UIDevice.currentDevice.orientation) {
                        UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown ->
                            AVCaptureVideoOrientationPortraitUpsideDown
                        // The device reports the direction its top edge points, which is the
                        // opposite of the video orientation needed to keep the image upright.
                        UIDeviceOrientation.UIDeviceOrientationLandscapeLeft ->
                            AVCaptureVideoOrientationLandscapeRight
                        UIDeviceOrientation.UIDeviceOrientationLandscapeRight ->
                            AVCaptureVideoOrientationLandscapeLeft
                        else -> AVCaptureVideoOrientationPortrait
                    },
                )
            }
        }

        NSNotificationCenter.defaultCenter.addObserver(
            observer = listener,
            selector = NSSelectorFromString("onChange:"),
            name = UIDeviceOrientationDidChangeNotification,
            `object` = null,
        )
        rotationListener = listener
    }

    @Composable
    override fun Render(modifier: Modifier) {
        val active by facing.collectAsState()

        LaunchedEffect(active) { start(active) }
        DisposableEffect(Unit) {
            onDispose { previewLayer.removeFromSuperlayer() }
        }

        UIKitView(
            modifier = modifier,
            background = Color.Black,
            factory = {
                val container = UIView()
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
            },
        )
    }
}

private const val JPEG = "image/jpeg"

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { out ->
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, size.toULong()) }
    }
}
