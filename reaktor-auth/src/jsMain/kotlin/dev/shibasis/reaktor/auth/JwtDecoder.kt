package dev.shibasis.reaktor.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.json

/**
 * Utilities for decoding JWT tokens on the client side
 *
 * IMPORTANT: This is ONLY for extracting user information to display in the UI.
 * JWT verification MUST be done on the server using JWKS.
 * Never trust client-side JWT decoding for authentication!
 */

/**
 * External declaration for JavaScript's atob() function
 * Decodes a base64-encoded string
 */
external fun atob(encoded: String): String

/**
 * Decoded Google JWT payload
 * Contains user information from Google Sign-In
 */
@Serializable
data class GoogleJwtPayload(
    val iss: String,                    // Issuer: "https://accounts.google.com"
    val azp: String? = null,            // Authorized party (client ID)
    val aud: String,                    // Audience (your client ID)
    val sub: String,                    // Subject (unique Google user ID)
    val email: String? = null,          // User's email
    val email_verified: Boolean? = null, // Whether email is verified
    val name: String? = null,           // Full name
    val given_name: String? = null,     // First name
    val family_name: String? = null,    // Last name
    val picture: String? = null,        // Profile picture URL
    val locale: String? = null,         // User's locale
    val iat: Long,                      // Issued at (Unix timestamp)
    val exp: Long                       // Expiration time (Unix timestamp)
)

/**
 * Decoded Apple JWT payload
 * Contains user information from Apple Sign-In
 *
 * NOTE: Apple JWT does NOT include name or profile picture!
 * Name is only provided in the AppleUserInfo object on first sign-in.
 */
@Serializable
data class AppleJwtPayload(
    val iss: String,                    // Issuer: "https://appleid.apple.com"
    val aud: String,                    // Audience (your client ID)
    val sub: String,                    // Subject (unique Apple user ID)
    val email: String? = null,          // User's email (may be private relay)
    val email_verified: Boolean? = null, // Whether email is verified (usually true for Apple)
    val is_private_email: Boolean? = null, // Whether using private relay email
    val real_user_status: Int? = null,  // 0: unsupported, 1: unknown, 2: likely real
    val iat: Long,                      // Issued at (Unix timestamp)
    val exp: Long,                      // Expiration time (Unix timestamp)
    val nonce: String? = null,          // Nonce if provided
    val nonce_supported: Boolean? = null // Whether nonce is supported
)

/**
 * Decodes a JWT token and returns the payload as a JSON string
 *
 * JWT structure: header.payload.signature
 * We only decode the payload (middle part)
 *
 * @param jwt The JWT token string
 * @return Decoded payload as JSON string, or null if decoding fails
 */
fun decodeJwt(jwt: String): String? {
    return try {
        val parts = jwt.split(".")
        if (parts.size != 3) {
            console.error("Invalid JWT format: expected 3 parts, got ${parts.size}")
            return null
        }

        // Get the payload (second part)
        val payload = parts[1]

        // Base64URL to Base64: replace URL-safe characters
        val base64 = payload
            .replace('-', '+')
            .replace('_', '/')

        // Add padding if needed (Base64 requires length to be multiple of 4)
        val padded = when (base64.length % 4) {
            2 -> base64 + "=="
            3 -> base64 + "="
            else -> base64
        }

        // Decode base64 to JSON string
        atob(padded)
    } catch (e: Exception) {
        console.error("Failed to decode JWT", e)
        null
    }
}

/**
 * Decodes a Google JWT token and extracts user information
 *
 * @param idToken The JWT ID token from Google
 * @return GoogleJwtPayload containing user info, or null if decoding fails
 */
fun decodeGoogleJwt(idToken: String): GoogleJwtPayload? {
    val payloadJson = decodeJwt(idToken) ?: return null

    return try {
        Json.decodeFromString<GoogleJwtPayload>(payloadJson)
    } catch (e: Exception) {
        console.error("Failed to parse Google JWT payload", e)
        null
    }
}

/**
 * Decodes an Apple JWT token and extracts user information
 *
 * NOTE: Apple JWT does NOT contain name or picture!
 * You must get name from AppleUserInfo on first sign-in.
 *
 * @param idToken The JWT ID token from Apple
 * @return AppleJwtPayload containing user info, or null if decoding fails
 */
fun decodeAppleJwt(idToken: String): AppleJwtPayload? {
    val payloadJson = decodeJwt(idToken) ?: return null

    return try {
        Json.decodeFromString<AppleJwtPayload>(payloadJson)
    } catch (e: Exception) {
        console.error("Failed to parse Apple JWT payload", e)
        null
    }
}

/**
 * Checks if a JWT token has expired
 *
 * @param jwt The JWT token string
 * @return true if expired, false if still valid, null if unable to determine
 */
fun isJwtExpired(jwt: String): Boolean? {
    val payloadJson = decodeJwt(jwt) ?: return null

    return try {
        val payload = JSON.parse<dynamic>(payloadJson)
        val exp = payload.exp as? Long ?: return null
        val now = (js("Date.now()") as Double) / 1000 // Current time in seconds

        now > exp
    } catch (e: Exception) {
        console.error("Failed to check JWT expiration", e)
        null
    }
}

/**
 * Extracts the subject (user ID) from a JWT without full parsing
 * Useful for quick user ID lookup
 *
 * @param jwt The JWT token string
 * @return User ID (sub claim), or null if extraction fails
 */
fun extractSubject(jwt: String): String? {
    val payloadJson = decodeJwt(jwt) ?: return null

    return try {
        val payload = JSON.parse<dynamic>(payloadJson)
        payload.sub as? String
    } catch (e: Exception) {
        console.error("Failed to extract subject from JWT", e)
        null
    }
}
