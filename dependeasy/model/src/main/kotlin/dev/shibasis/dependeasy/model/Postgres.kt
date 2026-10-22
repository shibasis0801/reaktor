package dev.shibasis.dependeasy.model

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

object Postgres {
    fun connect(root: File, database: DatabaseMigrations.Postgres, environment: Environment): Connection = try {
        val name = database.connections.getValue(environment)
        val value = System.getenv(name) ?: database.sources.firstNotNullOfOrNull { source ->
            val file = root.resolve(source).canonicalFile
            require(file.toPath().startsWith(root.resolve("config").canonicalFile.toPath()))
            if (!file.isFile) null else file.readLines().firstNotNullOfOrNull { line ->
                Regex("^(?:export\\s+)?${Regex.escape(name)}=(.*)$").matchEntire(line)?.groupValues?.get(1)?.trim()?.let {
                    when {
                        it.startsWith('\'') && it.endsWith('\'') -> it.substring(1, it.length - 1).replace("'\\''", "'")
                        it.startsWith('"') && it.endsWith('"') -> it.substring(1, it.length - 1).replace("\\\"", "\"")
                        else -> it
                    }
                }
            }
        }
        require(!value.isNullOrBlank())
        val uri = URI(value.removePrefix("jdbc:"))
        require(uri.scheme in listOf("postgres", "postgresql") && uri.host != null && !uri.path.isNullOrBlank())
        fun decode(text: String) = URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8)
        val properties = Properties().apply {
            setProperty("sslmode", "require")
            setProperty("connectTimeout", "15")
            setProperty("socketTimeout", "30")
            setProperty("ApplicationName", "dependeasy-migrations")
            uri.rawUserInfo?.split(':', limit = 2)?.let { credentials ->
                setProperty("user", decode(credentials[0]))
                credentials.getOrNull(1)?.let { setProperty("password", decode(it)) }
            }
            uri.rawQuery?.split('&')?.forEach { pair ->
                val parts = pair.split('=', limit = 2)
                require(parts.size == 2)
                setProperty(decode(parts[0]), decode(parts[1]))
            }
        }
        val host = if (':' in uri.host) "[${uri.host}]" else uri.host
        DriverManager.getConnection("jdbc:postgresql://$host:${if (uri.port < 0) 5432 else uri.port}${uri.rawPath}", properties)
    } catch (_: Exception) { error("Cannot connect to the declared Postgres database; connection details withheld") }
}
