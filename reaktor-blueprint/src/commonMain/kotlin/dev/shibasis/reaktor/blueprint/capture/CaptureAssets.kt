package dev.shibasis.reaktor.blueprint.capture

import kotlinx.serialization.Serializable

@Serializable
data class CaptureAsset(val id: String, val reference: String, val mediaType: String)
