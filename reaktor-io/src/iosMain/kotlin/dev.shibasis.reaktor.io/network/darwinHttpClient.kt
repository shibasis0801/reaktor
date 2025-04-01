package dev.shibasis.reaktor.io.network

import io.ktor.client.*
import io.ktor.client.engine.darwin.*

actual val http = HttpClient(Darwin) {
    middleware()
    engine {
        configureRequest {
            setAllowsCellularAccess(true)
            setAllowsConstrainedNetworkAccess(true)
            setAllowsExpensiveNetworkAccess(true)
        }
    }
}