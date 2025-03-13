package dev.shibasis.reaktor.core.framework

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

// Unfortunately we can't change default behaviour as other libraries may rely on it. So these wrappers.

fun JsonBuilder.defaults() {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

var json = Json {
    defaults()
}