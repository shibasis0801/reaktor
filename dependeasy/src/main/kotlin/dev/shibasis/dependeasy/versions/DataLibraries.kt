package dev.shibasis.dependeasy.versions

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

object DataLibraries {
    const val OkHttp = "com.squareup.okhttp3:okhttp:${Versions.OkHttp}"
    const val SQLiteJdbc = "org.xerial:sqlite-jdbc:3.49.1.0"
    const val Apollo = "com.apollographql.apollo:apollo-runtime:${Versions.Apollo}"
    const val Exposed = "org.jetbrains.exposed:exposed-core:${Versions.Exposed}"
    const val ExposedJdbc = "org.jetbrains.exposed:exposed-jdbc:${Versions.Exposed}"
    const val ExposedJson = "org.jetbrains.exposed:exposed-json:${Versions.Exposed}"
    const val Gson = "com.google.code.gson:gson:2.8.9"
    const val H2 = "com.h2database:h2:2.2.224"
    const val Jwt = "com.appstractive:jwt-kt:1.2.1"
    const val Koin = "io.insert-koin:koin-core:${Versions.Koin}"
    const val MockWebServer = "com.squareup.okhttp3:mockwebserver:5.4.0"
    const val Neo4j = "org.neo4j.driver:neo4j-java-driver:5.28.9"
    const val Postgis = "io.github.sebasbaumh:postgis-java-ng:23.2.0"
    const val Postgres = "org.postgresql:postgresql:42.7.13"
    const val SqlDelight = "app.cash.sqldelight:runtime:${Versions.SQLDelight}"
    const val SqlDelightAndroid = "app.cash.sqldelight:android-driver:${Versions.SQLDelight}"
    const val SqlDelightJdbc = "app.cash.sqldelight:jdbc-driver:${Versions.SQLDelight}"
    const val SqlDelightNative = "app.cash.sqldelight:native-driver:${Versions.SQLDelight}"
    const val SqlDelightSQLite = "app.cash.sqldelight:sqlite-driver:${Versions.SQLDelight}"
    const val SqlDelightWeb = "app.cash.sqldelight:web-worker-driver:${Versions.SQLDelight}"
}
