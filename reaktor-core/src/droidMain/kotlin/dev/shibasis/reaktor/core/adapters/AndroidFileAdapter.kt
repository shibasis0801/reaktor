package dev.shibasis.reaktor.core.adapters

import androidx.activity.ComponentActivity

class AndroidFileAdapter(activity: ComponentActivity): FileAdapter<ComponentActivity>(activity) {
    override fun getCacheDirectory(): String {
        return controller?.cacheDir?.absolutePath ?: ""
    }
}