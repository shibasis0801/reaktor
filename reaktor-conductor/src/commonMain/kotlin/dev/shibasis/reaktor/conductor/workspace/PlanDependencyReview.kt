package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class PlanDependencyReview(val item: String, val dependency: String, val reason: String, val replacement: String? = null)
