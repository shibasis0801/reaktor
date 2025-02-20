package dev.shibasis.reaktor.auth.jwt

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import dev.shibasis.reaktor.auth.GoogleOAuthConfig
import dev.shibasis.reaktor.core.framework.Adapter
import org.springframework.stereotype.Component

@Component
class TokenVerifierService(
    googleOAuthConfig: GoogleOAuthConfig
): Adapter<Unit>(Unit) {
    private val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory())
        .setAudience(listOf(googleOAuthConfig.clientId))
        .build()

    fun verify(idToken: String): GoogleIdToken.Payload? {
        return try {
            verifier.verify(idToken).payload
        } catch (_: Throwable) {
            null
        }
    }
}