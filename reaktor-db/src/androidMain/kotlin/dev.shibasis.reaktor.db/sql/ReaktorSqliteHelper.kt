package dev.shibasis.reaktor.db.sql

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper


/**
 * Leaves what to *do* about a corrupt file to the framework, which deletes it and starts over.
 * Overriding that to rethrow would leave the app unable to launch at all — see DarwinSqlAdapter,
 * which reproduces the behaviour by hand because SQLiter has no equivalent.
 *
 * What it adds is that somebody hears about it. The framework's recovery is silent, so an app
 * whose user just lost everything in the file opens looking exactly like a first install, with
 * nothing to tell the two apart. [onUnreadable] runs after the file has been replaced.
 */
class ReaktorSqliteHelper(
    version: Int,
    private val onUnreadable: () -> Unit = {},
): SupportSQLiteOpenHelper.Callback(version) {
    override fun onCorruption(db: SupportSQLiteDatabase) {
        super.onCorruption(db)
        onUnreadable()
    }

    override fun onCreate(db: SupportSQLiteDatabase) {
//        // Create your tables here
//        db.execSQL(
//            "CREATE TABLE contacts (" +
//                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
//                    "name TEXT NOT NULL," +
//                    "phone TEXT," +
//                    "email TEXT UNIQUE" +
//                    ")"
//        )
//
//        // Create indices (optional)
//        db.execSQL("CREATE INDEX idx_contacts_name ON contacts (name)")
//
//        // You can add more table creation statements here...
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
//        // Handle database schema upgrades here
//        // Example: Add a new column to the "contacts" table
//        if (oldVersion < 2) {
//            db.execSQL("ALTER TABLE contacts ADD COLUMN address TEXT")
//        }
//        // Example: Add a new table
//        if (oldVersion < 3) {
//            db.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, price REAL)")
//        }
//        // More migration steps as needed for other version changes...
//        for (version in oldVersion + 1..newVersion) {
//            migrateToVersion(db, version)
//        }
    }

    private fun migrateToVersion(db: SupportSQLiteDatabase, version: Int) {
        try {
//            when (version) {
//                2 ->                     // Example: Add a new column to the "contacts" table
//                    db.execSQL("ALTER TABLE contacts ADD COLUMN address TEXT")
//
//                3 ->                     // Example: Add a new table
//                    db.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, price REAL)")
//
//                else -> throw IllegalStateException("Unknown upgrade step to version $version")
//            }
        } catch (e: SQLiteException) {
            // Handle migration errors appropriately (e.g., log, rollback, or rethrow)
            throw RuntimeException("Migration failed at version $version", e)
        }
    }
}