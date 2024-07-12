package dev.shibasis.reaktor.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
fun isIOSVersionAtLeast(major: Int, minor: Int = 0, patch: Int = 0): Boolean {
    val version = NSProcessInfo.processInfo.operatingSystemVersion
    return version.useContents {
        when {
            majorVersion.toInt() < major -> false
            majorVersion.toInt() > major -> true
            minorVersion.toInt() < minor -> false
            minorVersion.toInt() > minor -> true
            patchVersion.toInt() >= patch -> true
            else -> false
        }
    }
}

//class ImagePickerViewController : UIViewController,
//    UIImagePickerControllerDelegateProtocol,
//    UINavigationControllerDelegateProtocol,
//    PHPickerViewControllerDelegateProtocol {
//
//    fun openImagePicker() {
//        if (isIOSVersionAtLeast(14)) {
//            val configuration = PHPickerConfiguration()
//            val picker = PHPickerViewController(configuration = configuration)
//            picker.delegate = this
//            presentModalViewController(picker, true)
//        } else {
//            val picker = UIImagePickerController()
//            picker.delegate = this
//            presentModalViewController(picker, true)
//        }
//    }
//
//    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
//        TODO("Not yet implemented")
//    }
//
//}

class DarwinGalleryAdapter() {

}