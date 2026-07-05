package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.api.TokenResponse
import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.AuthGrantType
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.authAppId
import dev.shibasis.reaktor.auth.jwt.authSessionId
import dev.shibasis.reaktor.auth.runtime.AuthTokenGrants
import dev.shibasis.reaktor.auth.runtime.TOKEN_EXCHANGE_GRANT
import dev.shibasis.reaktor.auth.runtime.audienceAllowed
import dev.shibasis.reaktor.auth.runtime.bearerOrRawToken
import dev.shibasis.reaktor.auth.runtime.clampedAccessTokenTtl
import dev.shibasis.reaktor.auth.runtime.normalizedAudience
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditSink
import dev.shibasis.reaktor.auth.runtime.ports.AuthPersonalTokens
import dev.shibasis.reaktor.auth.runtime.ports.AuthServiceAccounts
import dev.shibasis.reaktor.auth.runtime.scopeList
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

class AuthTokenGrantNode(
    graph: Graph,
) : BasicNode(graph), AuthTokenGrants {
    val personalTokensPort by consumes<AuthPersonalTokens>()
    val serviceAccountsPort by consumes<AuthServiceAccounts>()
    val jwtMinterPort by consumes<JwtMinter>()
    val jwtVerifierPort by consumes<JwtVerifier>()
    val auditPort by consumes<AuthAuditSink>()
    val tokenGrantsPort by provides<AuthTokenGrants>(this)

    override suspend fun issue(request: TokenRequest): TokenResponse =
        when (request.grantType) {
            AuthGrantType.CLIENT_CREDENTIALS.wireName -> clientCredentials(request)
            TOKEN_EXCHANGE_GRANT -> tokenExchange(request)
            AuthGrantType.PAT.wireName, AuthGrantType.PERSONAL_ACCESS_TOKEN.wireName -> patAccessToken(request)
            else -> {
                auditPort.suspended {
                    auditFailure(
                        request = request,
                        grantType = request.grantType,
                        reason = "unsupported_grant",
                        audience = request.audience,
                    )
                }
                TokenResponse("", statusCode = StatusCode.BAD_REQUEST)
            }
        }

    private suspend fun clientCredentials(request: TokenRequest): TokenResponse {
        val audience = request.audience.normalizedAudience()
        val ttlSeconds = request.ttlSeconds.clampedAccessTokenTtl()
        if (request.clientId.isNullOrBlank() && request.clientAssertion.isNullOrBlank()) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = AuthGrantType.CLIENT_CREDENTIALS.wireName,
                    reason = "missing_client_authentication",
                    audience = audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.BAD_REQUEST)
        }
        val result = serviceAccountsPort {
            issueClientCredentialsToken(
                clientId = request.clientId,
                clientSecret = request.clientSecret,
                clientAssertion = request.clientAssertion,
                clientAssertionType = request.clientAssertionType,
                audience = audience,
                requestedScopes = request.scopes,
                ttlSeconds = ttlSeconds,
            )
        }
        if (result == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = AuthGrantType.CLIENT_CREDENTIALS.wireName,
                    reason = "invalid_client_credentials",
                    audience = audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }

        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_MINT,
                subjectPrincipalId = result.principalId,
                appId = result.appId,
                credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                grantType = AuthGrantType.CLIENT_CREDENTIALS.wireName,
                tokenId = result.serviceAccountId,
                audience = result.audience,
                scopes = result.scopes,
            )
        }

        return TokenResponse(
            accessToken = result.accessToken,
            expiresInSeconds = result.expiresInSeconds,
            scopes = result.scopes,
        )
    }

    private suspend fun tokenExchange(request: TokenRequest): TokenResponse {
        val actor = serviceAccountsPort {
            authenticateClient(
                clientId = request.clientId,
                clientSecret = request.clientSecret,
                clientAssertion = request.clientAssertion,
                clientAssertionType = request.clientAssertionType,
            )
        }
        if (actor == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = TOKEN_EXCHANGE_GRANT,
                    reason = "invalid_actor_credentials",
                    audience = request.audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }

        val subjectToken = request.subjectToken?.trim()?.takeIf { it.isNotEmpty() }
        if (subjectToken == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    actorPrincipalId = serviceAccountsPort { subjectOf(actor) },
                    appId = actor.appId,
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = TOKEN_EXCHANGE_GRANT,
                    reason = "missing_subject_token",
                    audience = request.audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.BAD_REQUEST)
        }
        val subjectClaims = jwtVerifierPort { verifyReaktorSignature(subjectToken) }.getOrNull()
        if (subjectClaims == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    actorPrincipalId = serviceAccountsPort { subjectOf(actor) },
                    appId = actor.appId,
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = TOKEN_EXCHANGE_GRANT,
                    reason = "invalid_subject_token",
                    audience = request.audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }
        val subject = subjectClaims.subject
        if (subject == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    actorPrincipalId = serviceAccountsPort { subjectOf(actor) },
                    appId = actor.appId,
                    sessionId = subjectClaims.authSessionId(),
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = TOKEN_EXCHANGE_GRANT,
                    reason = "subject_token_missing_subject",
                    audience = request.audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }

        val subjectAppId = subjectClaims.authAppId()
        if (subjectAppId != actor.appId) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    actorPrincipalId = serviceAccountsPort { subjectOf(actor) },
                    subjectPrincipalId = subject,
                    appId = actor.appId,
                    sessionId = subjectClaims.authSessionId(),
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = TOKEN_EXCHANGE_GRANT,
                    reason = "subject_app_mismatch",
                    audience = request.audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.FORBIDDEN)
        }

        val audience = request.audience.normalizedAudience()
        if (!serviceAccountsPort { audienceAllowed(actor, audience) }) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    actorPrincipalId = serviceAccountsPort { subjectOf(actor) },
                    subjectPrincipalId = subject,
                    appId = actor.appId,
                    sessionId = subjectClaims.authSessionId(),
                    credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                    grantType = TOKEN_EXCHANGE_GRANT,
                    reason = "audience_not_allowed",
                    audience = audience,
                    scopes = request.scopes,
                )
            }
            return TokenResponse("", statusCode = StatusCode.FORBIDDEN)
        }

        val ttlSeconds = request.ttlSeconds.clampedAccessTokenTtl()
        val scopes = serviceAccountsPort { scopesFor(actor, request.scopes) }
        val actorSubject = serviceAccountsPort { subjectOf(actor) }
        val token = jwtMinterPort {
            mintDelegatedToken(
                subject = subject,
                actorSubject = actorSubject,
                audience = audience,
                scopes = scopes,
                ttlSeconds = ttlSeconds,
                appId = actor.appId,
                contextId = request.contextId,
            )
        }
        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_EXCHANGE,
                actorPrincipalId = actorSubject,
                subjectPrincipalId = subject,
                appId = actor.appId,
                sessionId = subjectClaims.authSessionId(),
                credentialType = AuthCredentialType.CLIENT_CREDENTIALS.wireName,
                grantType = TOKEN_EXCHANGE_GRANT,
                audience = audience,
                scopes = scopes,
            )
        }
        return TokenResponse(
            accessToken = token,
            expiresInSeconds = ttlSeconds,
            scopes = scopes,
            audience = audience,
            contextId = request.contextId,
        )
    }

    private suspend fun patAccessToken(request: TokenRequest): TokenResponse {
        val rawToken = request.bearerOrRawToken()
        if (rawToken == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.PAT.wireName,
                    reason = "missing_pat",
                    audience = request.audience,
                )
            }
            return TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }

        val pat = personalTokensPort.suspended { verifyPersonalAccessToken(rawToken) }
        if (pat == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.PAT.wireName,
                    reason = "invalid_pat",
                    audience = request.audience,
                )
            }
            return TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }

        val audience = request.audience.normalizedAudience()
        if (!pat.audienceAllowed(audience)) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    subjectPrincipalId = pat.principalId,
                    appId = pat.appId,
                    contextId = pat.contextId,
                    credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.PAT.wireName,
                    tokenId = pat.id,
                    reason = "audience_not_allowed",
                    audience = audience,
                    scopes = pat.scopeList(),
                )
            }
            return TokenResponse("", statusCode = StatusCode.FORBIDDEN)
        }

        val ttlSeconds = request.ttlSeconds.clampedAccessTokenTtl()
        val token = personalTokensPort { createAccessToken(pat = pat, audience = audience, ttlSeconds = ttlSeconds) }
        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_EXCHANGE,
                subjectPrincipalId = pat.principalId,
                appId = pat.appId,
                contextId = pat.contextId,
                credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                grantType = AuthGrantType.PAT.wireName,
                tokenId = pat.id,
                audience = audience,
                scopes = pat.scopeList(),
            )
        }
        return TokenResponse(
            accessToken = token,
            expiresInSeconds = ttlSeconds,
            tokenId = pat.id,
            scopes = pat.scopeList(),
            audience = audience,
            contextId = pat.contextId,
        )
    }
}
