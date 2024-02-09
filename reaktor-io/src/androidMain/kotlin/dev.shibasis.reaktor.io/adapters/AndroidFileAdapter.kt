package dev.shibasis.reaktor.io.adapters

import androidx.activity.ComponentActivity
import dev.shibasis.reaktor.io.adapters.FileAdapter

class AndroidFileAdapter(activity: ComponentActivity): FileAdapter<ComponentActivity>(activity) {
    override fun getCacheDirectory(): String {
        return controller?.cacheDir?.absolutePath ?: ""
    }
}