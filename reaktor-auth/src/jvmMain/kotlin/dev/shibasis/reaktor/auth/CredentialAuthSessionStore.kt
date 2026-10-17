package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.security.CredentialStore
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Each application and environment has an independent, atomically replaced credential record. */
class CredentialAuthSessionStore(private val store: CredentialStore, private val appId: String) : AuthSessionStore {
    init { require(appId.isNotBlank() && appId.length <= 128 && appId.none(Char::isISOControl)) }
    private fun key(environment: Environment) = "session/$appId/${environment.name}"
    override suspend fun read(environment: Environment): StoredAuthSession? = withContext(Dispatchers.IO) {
        val bytes = store.read(key(environment)) ?: return@withContext null
        try { Json.decodeFromString<StoredAuthSession>(bytes.decodeToString()).also {
            check(it.context.appId == appId && it.context.audience == appId) { "Stored session belongs to a different application" }
        } } finally { bytes.fill(0) }
    }
    override suspend fun write(environment: Environment, session: StoredAuthSession) = withContext(Dispatchers.IO) {
        require(session.context.appId == appId && session.context.audience == appId) { "Session does not match this credential store" }
        val bytes = Json.encodeToString(session).encodeToByteArray()
        try { store.write(key(environment), bytes) } finally { bytes.fill(0) }
    }
    override suspend fun clear(environment: Environment) = withContext(Dispatchers.IO) { store.delete(key(environment)) }
}
