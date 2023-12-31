package dev.shibasis.reaktor

import platform.UIKit.UIDevice
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
//import co.touchlab.sqliter.DatabaseConfiguration
//import co.touchlab.sqliter.createDatabaseManager
import platform.UIKit.UIViewController

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()



class DarwinDatabase(uiViewController: UIViewController): Database<UIViewController>(uiViewController) {
    override suspend fun getDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver {
        return NativeSqliteDriver(schema.synchronous(), name)
    }
}