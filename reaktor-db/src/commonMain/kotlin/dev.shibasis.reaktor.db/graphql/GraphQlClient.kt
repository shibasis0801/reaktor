package dev.shibasis.reaktor.db.graphql

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.Subscription
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.Flow

interface GraphQlClient {
    val endpoint: String

    suspend fun <D : Query.Data> query(query: Query<D>): ApolloResponse<D>

    suspend fun <D : Mutation.Data> mutation(mutation: Mutation<D>): ApolloResponse<D>

    fun <D : Subscription.Data> subscription(subscription: Subscription<D>): Flow<ApolloResponse<D>>

    fun close()
}

class ApolloKmmGraphQlClient(
    override val endpoint: String,
    createApolloClient: () -> ApolloClient = {
        ApolloClient.Builder()
            .serverUrl(endpoint)
            .build()
    },
) : GraphQlClient {
    private val apolloClientDelegate = lazy(LazyThreadSafetyMode.NONE, createApolloClient)

    val apolloClient: ApolloClient by apolloClientDelegate

    constructor(endpoint: String, apolloClient: ApolloClient) : this(endpoint, { apolloClient })

    override suspend fun <D : Query.Data> query(query: Query<D>): ApolloResponse<D> =
        apolloClient.query(query).execute()

    override suspend fun <D : Mutation.Data> mutation(mutation: Mutation<D>): ApolloResponse<D> =
        apolloClient.mutation(mutation).execute()

    override fun <D : Subscription.Data> subscription(
        subscription: Subscription<D>,
    ): Flow<ApolloResponse<D>> = apolloClient.subscription(subscription).toFlow()

    override fun close() {
        if (apolloClientDelegate.isInitialized()) {
            apolloClient.close()
        }
    }

    companion object {
        fun connect(
            endpoint: String,
            configure: ApolloClient.Builder.() -> Unit = {},
        ): ApolloKmmGraphQlClient {
            return ApolloKmmGraphQlClient(endpoint) {
                ApolloClient.Builder()
                    .serverUrl(endpoint)
                    .apply(configure)
                    .build()
            }
        }
    }
}

fun Feature.apolloGraphQl(
    endpoint: String,
    configure: ApolloClient.Builder.() -> Unit = {},
): GraphQlClient = ApolloKmmGraphQlClient.connect(endpoint, configure)
    .also { GraphQl = it }

fun requireGraphQlClient(): GraphQlClient =
    Feature.GraphQl ?: error("Feature.GraphQl is not initialized")

var Feature.GraphQl by CreateSlot<GraphQlClient>()
