package dev.shibasis.reaktor.auth.spring

import dev.shibasis.reaktor.auth.api.AppServer
import dev.shibasis.reaktor.auth.api.AuthServer
import dev.shibasis.reaktor.auth.jwt.UserAuthenticationProvider
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.graph.di.SpringDependencyAdapter
import dev.shibasis.reaktor.db.service.ExposedAdapter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
open class AuthSpringConfiguration {
    @Bean
    open fun reaktorAuthRuntime(
        applicationContext: ConfigurableApplicationContext,
        exposedAdapter: ExposedAdapter,
        @Qualifier("authProviders") authProviderList: ObjectProvider<List<UserAuthenticationProvider>>,
        authProviderBeans: ObjectProvider<UserAuthenticationProvider>,
        @Value("\${reaktor.jwt.ec-jwk:}") ecJwkJson: String,
        @Value("\${reaktor.jwt.ec-jwk-prev:}") ecJwkPrevJson: String,
        @Value("\${reaktor.auth.allow-ephemeral-signing-key:false}") allowEphemeralSigningKey: Boolean,
    ): AuthRuntimeGraph {
        val providers = authProviderList.ifAvailable()
            ?: authProviderBeans.orderedStream().toList()
        return AuthRuntimeGraph.create(
            adapter = exposedAdapter,
            config = AuthRuntimeConfig(
                userAuthenticationProviders = providers,
                ecJwkJson = ecJwkJson,
                ecJwkPrevJson = ecJwkPrevJson,
                // Secure-by-default at the production entry point: a missing signing key fails fast
                // unless the deploy explicitly opts into ephemeral keys (local/dev only).
                allowEphemeralSigningKey = allowEphemeralSigningKey,
                // Env read lives here at the composition root, not hidden inside a graph node.
                patBootstrapToken = System.getenv("REAKTOR_PAT_BOOTSTRAP_TOKEN")?.takeIf { it.isNotBlank() },
            ),
            dependencyAdapter = SpringDependencyAdapter(applicationContext),
        )
    }

    @Bean
    open fun authServer(runtime: AuthRuntimeGraph): AuthServer =
        AuthServer(runtime.service)

    @Bean
    open fun appServer(runtime: AuthRuntimeGraph): AppServer =
        AppServer(runtime.appService)

    @Bean
    open fun reaktorSecurityWebFilter(runtime: AuthRuntimeGraph): ReaktorSecurityWebFilter =
        ReaktorSecurityWebFilter(runtime.jwt.verifier)

    @Bean
    open fun jwksController(runtime: AuthRuntimeGraph): JwksController =
        JwksController(runtime.jwt.signingKeys)
}

private fun <T : Any> ObjectProvider<T>.ifAvailable(): T? =
    runCatching { getIfAvailable() }.getOrNull()
