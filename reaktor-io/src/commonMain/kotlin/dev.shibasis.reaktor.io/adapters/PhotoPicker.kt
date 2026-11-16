package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * A photo as the gallery or the camera handed it over: the file's own bytes, in whatever format
 * that is (a JPEG from most cameras, HEIC from an iPhone's). Usually several megabytes: pass it
 * through `downscalePicture` before keeping it.
 */
class PickedPhoto(val bytes: ByteArray)

/**
 * The system's own photo chooser, for one photo, as one suspend call.
 *
 * It needs no permission on any platform, which is why it is here and not in `reaktor-media`: the
 * chooser runs outside the app and hands back only what the person picked, so the app never sees
 * the rest of their photos. `reaktor-media` owns in-app cameras and galleries, and brings
 * `RECORD_AUDIO` and CameraX with it; an app that wants one receipt photo shouldn't inherit those.
 *
 * | Platform | Chooser                                                        |
 * |----------|----------------------------------------------------------------|
 * | Android  | the Photo Picker (`PickVisualMedia`), the file chooser where a phone has none |
 * | iOS      | `PHPickerViewController`; `UIImagePickerController` before iOS 14 |
 * | Desktop, browser | not written                                             |
 *
 * Bound to the screen that presents it, as [DocumentPicker] is: on Android build it in
 * `Activity.onCreate`.
 */
interface PhotoPicker {
    /** Null when the person backed out or the photo couldn't be read. One at a time. */
    suspend fun pick(): PickedPhoto?
}

/**
 * The system's own camera, for one photo: the camera app on Android, the camera sheet on iOS.
 * The app never runs a camera itself, so on Android it needs no `CAMERA` permission.
 *
 * Two things the app has to get right, because no library can do them for it:
 * - **Android:** an app that *declares* `CAMERA` in its manifest (its own, or a library's merged
 *   in) and hasn't been granted it is refused the camera app too. Don't declare it for this.
 * - **iOS:** `NSCameraUsageDescription` must be in Info.plist, or iOS ends the app as the camera opens.
 */
interface CameraCapture {
    /** False where there is no camera to open: a tablet without one, the iOS simulator. */
    val available: Boolean

    /** Null when the person backed out, there is no camera, or the photo couldn't be read. */
    suspend fun capture(): PickedPhoto?
}

var Feature.PhotoPicker by CreateSlot<PhotoPicker>()
var Feature.Camera by CreateSlot<CameraCapture>()
