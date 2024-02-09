package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient

// todo will need refactor later to allow client code to customize internals, allow plugins, etc
expect val httpClient: HttpClient
