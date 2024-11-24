package dev.shibasis.reaktor.core.framework

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Unfortunately we can't change default behaviour as other libraries may rely on it. So these wrappers.
val json = Json { encodeDefaults = true }
inline fun<reified T> toJson(data: T) = Json.encodeToString<T>(data)
inline fun<reified T> fromJson(string: String) = Json.decodeFromString<T>(string)