package dev.shibasis.reaktor.core.util

import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.darwin.NSObject

class LocationManager: NSObject(), CLLocationManagerDelegateProtocol {
    val manager = CLLocationManager()

    init {
        manager.delegate = this
    }


    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
//        super.locationManagerDidChangeAuthorization(manager)
    }
}