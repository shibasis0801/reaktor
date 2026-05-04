package dev.shibasis.reaktor.db.graphql

import kotlin.test.Test
import kotlin.test.assertEquals

class GraphQlClientTest {
    @Test
    fun buildsApolloKmmGraphQlClient() {
        val endpoint = "https://example.com/graphql"
        val client = ApolloKmmGraphQlClient.connect(endpoint)

        try {
            assertEquals(endpoint, client.endpoint)
        } finally {
            client.close()
        }
    }
}
