package dev.shibasis.reaktor.auth.identity

import kotlinx.serialization.Serializable

/**
 * Provider-agnostic identity verification.
 *
 * Nothing outside an adapter may name a vendor. Product code, storage, and transport speak only
 * these types, so changing provider is a configuration change rather than a migration.
 */
interface IdentityVerifier {

    /** Stable id of the backing provider, e.g. for storage and support lookups. */
    val providerId: String

    /**
     * Begin a verification. [subject] is the caller's own user id, never a provider id.
     * [countryHint] only helps the provider pick document types; it is not a trust input.
     */
    suspend fun start(subject: String, countryHint: String? = null): Result<VerificationSession>

    /** Current outcome for a session. Callers must treat this as authoritative over any client claim. */
    suspend fun outcome(providerRef: String): Result<VerificationOutcome>

    /**
     * Verify that a provider callback genuinely came from the provider, returning its outcome.
     * Every adapter must implement this properly; there is no safe generic default, which is why
     * it has none.
     */
    suspend fun parseCallback(rawBody: String, headers: Map<String, String>): Result<VerificationOutcome>
}

@Serializable
data class VerificationSession(
    val providerRef: String,
    /** Where to send the user to complete the check. */
    val url: String,
    val expiresAtEpochMs: Long? = null,
)

@Serializable
enum class VerificationStatus {
    Pending,
    Verified,
    Failed,
    Expired,
}

/**
 * Derived facts only. Adapters must not surface document images, face templates, or raw provider
 * payloads through this type — storing those is what turns a verification feature into a
 * biometric-data liability.
 */
@Serializable
data class VerificationOutcome(
    val providerRef: String,
    val status: VerificationStatus,
    /** ISO yyyy-MM-dd, as printed on the document. */
    val dateOfBirth: String? = null,
    val documentName: String? = null,
    /** ISO 3166-1 alpha-2 issuing state. Not nationality, not residence. */
    val issuingCountry: String? = null,
    val failureReason: String? = null,
)
