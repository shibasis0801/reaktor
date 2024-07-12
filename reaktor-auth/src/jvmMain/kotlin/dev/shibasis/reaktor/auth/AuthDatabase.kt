package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.auth.apps.RBACApp
import dev.shibasis.reaktor.auth.apps.Walnut
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction

object AuthDatabase {
    init {
        Database.connect(
            url = "jdbcUrl",
            driver = "driver",
            user = "user",
            password = "password"
        )

        transaction {
            addLogger(StdOutSqlLogger)
            listOf(Apps, Users, Roles, UserRoles).forEach {
                SchemaUtils.create(it)
            }
        }

        Dispatch.IO.launch {
            createApp(Walnut)
        }
    }

    fun readSomeData() {

    }

    suspend fun createApp(app: RBACApp) {
        app.setup()
    }
}