package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over

internal val AuthRuntimeGraph.api: AuthService get() = AuthService() over LocalLink(service)
