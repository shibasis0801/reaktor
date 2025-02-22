package dev.shibasis.reaktor.core.framework

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Unfortunately we can't change default behaviour as other libraries may rely on it. So these wrappers.
val json = Json {
    encodeDefaults = true
    classDiscriminator = "type"
}