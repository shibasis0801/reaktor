package dev.shibasis.reaktor.auth.identity

/**
 * Offline verification of a document that carries its own issuer signature — an Aadhaar secure QR,
 * an ICAO 9303 passport chip.
 *
 * Deliberately not an [IdentityVerifier]: there is no provider, no session, and no callback, so
 * forcing it through that interface would only add ceremony. What it shares is the output type.
 *
 * Implementations must verify the issuer signature before returning anything. A payload that
 * parses is not a payload that is genuine, and this runs server-side for that reason — a client
 * that checks its own signature and reports the result is trivially bypassed.
 */
interface SignedDocumentVerifier {

    /** Mechanism id recorded against the result, e.g. "aadhaar_qr". */
    val method: String

    /**
     * @param payload raw scanned bytes, exactly as read from the document.
     * @return the derived facts on a valid signature, or a failure. Implementations must not
     *   retain the payload, the embedded photograph, or any identifier the document withholds.
     */
    suspend fun verify(payload: ByteArray): Result<SignedDocumentFacts>
}

data class SignedDocumentFacts(
    /** ISO yyyy-MM-dd. Null when the document carries only a year of birth. */
    val dateOfBirth: String? = null,
    /** Present when the document records a year but no full date; callers decide how to read it. */
    val birthYear: Int? = null,
    val name: String? = null,
    val issuingCountry: String? = null,
)
