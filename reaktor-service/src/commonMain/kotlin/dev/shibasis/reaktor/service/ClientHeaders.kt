package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.io.network.HttpObservation
import io.ktor.http.Url

fun installClientDescription(origins: () -> List<String>, description: () -> ClientDescription): () -> Unit =
    HttpObservation.intercept { request, proceed ->
        val trusted = runCatching { origins().any { address ->
            val origin = Url(address)
            origin.protocol == request.url.protocol && origin.host == request.url.host && origin.port == request.url.build().port
        } }.getOrDefault(false)
        if (trusted && request.headers[ClientDescription.Header] == null)
            runCatching { description().header() }.getOrNull()?.takeIf(String::isNotEmpty)?.let { request.headers.append(ClientDescription.Header, it) }
        proceed(request)
    }
