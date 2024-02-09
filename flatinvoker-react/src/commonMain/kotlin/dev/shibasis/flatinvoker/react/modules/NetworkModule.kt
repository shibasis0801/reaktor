package dev.shibasis.flatinvoker.react.modules

import dev.shibasis.flatinvoker.react.network.httpClient
import dev.shibasis.flatinvoker.react.types.nativeFlow
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.Flow

object NetworkModule {
    fun get() = nativeFlow {
        for (i in 1..2) {
//            trySend(i)
            val response = httpClient.get("http://192.168.0.247:8000/search.json")
            trySend(response.body<String>())
        }
    }
}


/*

type Network {
    getResponse(url: string): Flow<string>
    getCachedResponse(url: string): string
}

*/