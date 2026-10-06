package dev.shibasis.reaktor.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.adapters.SqlAdapter
import dev.shibasis.reaktor.io.adapters.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SqlAdapterHeadlessTest {
    @Test fun driverBootstrapDoesNotRequireFileAdapterButBackupDoes() = runTest {
        val previous = Feature.File
        Feature.File = null
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val sql = object : SqlAdapter<Unit>(Unit) { override fun createDriver() = driver }
            assertSame(driver, sql.getDriver())
            assertTrue(sql.checkSize() >= 0)
            assertFailsWith<Error> { sql.backup("backup.db") }
        } finally { driver.close(); Feature.File = previous }
    }
}
