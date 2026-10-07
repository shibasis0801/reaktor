package dev.shibasis.reaktor.db.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import dev.shibasis.reaktor.io.serialization.TextSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException

/**
 * A database file SQLite would not read, and where it went.
 *
 * Handed to the caller rather than only logged, because what it means is the caller's to say: to a
 * cache it is nothing, and to an app that kept a person's keys in there it is the reason they are
 * about to be asked to sign in again.
 */
class UnreadableDatabase(
    /** The damaged file, moved rather than deleted. The next one to be set aside replaces it. */
    val setAside: File,
    /** What SQLite said about it. For a log; it is not written for a person. */
    val reason: String,
)

/**
 * Opens `name.db`, starting a new one if the file that is there cannot be read.
 *
 * Android's SQLite deletes a corrupt file and recreates it, and `DarwinSqlAdapter` does the same by
 * hand. Without the equivalent here a damaged file threw from this call on every launch, and since
 * a desktop app opens its storage before it opens a window, that was an app that could not start
 * and could not say why.
 *
 * The file is checked in full before it is used, not just opened: a file whose header survived
 * answers questions about its schema perfectly well and fails at whichever later read reaches the
 * damage, by which time nothing is in a position to start over. That costs one sequential read of
 * the file per launch — measured at 11 ms for a 59 MB file already in the page cache.
 *
 * It is moved aside as `name.db.unreadable`, not deleted — the bytes are the only copy of whatever
 * was in there, and a desktop is somewhere a person can go and look. Only damage does this: a file
 * that is locked, or that this process may not read, still throws, because neither says anything
 * about the bytes and setting a healthy database aside would be the data loss this exists to avoid.
 *
 * The rows live in a table named for the file, not for the path to it. [name] is a path here, and
 * naming the table after all of it wrote the user's home directory into the schema — so a file
 * copied to another machine, or to another account on the same one, opened as an empty database
 * with everything still inside it. A table an earlier build named that way is renamed on open.
 */
fun createSqliteObjectDatabase(
    name: String,
    objectSerializer: ObjectSerializer<*> = TextSerializer(),
    timestampProvider: TimestampProvider = DefaultTimestampProvider(),
    onUnreadable: (UnreadableDatabase) -> Unit = {},
): SqliteObjectDatabase {
    val dbFile = File("$name.db")
    damageIn(dbFile)?.let { reason -> onUnreadable(UnreadableDatabase(setAside(dbFile), reason)) }
    val label = File(name).name
    adoptTableNamedForAPath(dbFile, objectTableName(label))
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    return SqliteObjectDatabase(driver, label, objectSerializer, timestampProvider)
}

/**
 * Renames the table an earlier build named after the whole path, so its rows are found again.
 *
 * The path it was named for is not necessarily this one — the file may have been moved since,
 * which is the case this exists for — so the old table is recognised by how its name ends. When
 * more than one table could be it, none is touched: guessing would hide one set of rows behind
 * another, and both are still in the file for whoever comes to look.
 */
private fun adoptTableNamedForAPath(file: File, table: String) {
    if (file.length() == 0L) return

    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
        val tables = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
        if (table in tables) return

        val ending = "_" + table.removePrefix(OBJECT_TABLE_PREFIX)
        val earlier = tables.singleOrNull { candidate ->
            candidate.startsWith(OBJECT_TABLE_PREFIX) && candidate.endsWith(ending) &&
                candidate.all { it.isLetterOrDigit() || it == '_' }
        } ?: return
        connection.createStatement().use { it.execute("ALTER TABLE $earlier RENAME TO $table") }
    }
}

private const val OBJECT_TABLE_PREFIX = "object_db_"

/** What is wrong with [file] as SQLite tells it, or null when nothing is. */
private fun damageIn(file: File): String? {
    // No file and an empty file are both a database with nothing in it yet.
    if (file.length() == 0L) return null

    return try {
        // A connection of its own, closed before anything is moved: the driver keeps one per
        // thread and gives no way to be sure they have all let go of the file.
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check(1)").use { rows ->
                    val verdict = if (rows.next()) rows.getString(1) else null
                    verdict.takeUnless { it == "ok" }
                }
            }
        }
    } catch (exception: SQLException) {
        // The vendor code is SQLite's own result code. Read from the standard exception so this
        // module does not have to compile against the JDBC driver it only uses at runtime. The
        // low byte is the primary code; the rest says which kind of corrupt, which is not a
        // distinction that changes what to do about it.
        when (exception.errorCode and 0xFF) {
            SQLITE_CORRUPT, SQLITE_NOTADB -> exception.message ?: "SQLite error ${exception.errorCode}"
            else -> throw exception
        }
    }
}

private const val SQLITE_CORRUPT = 11
private const val SQLITE_NOTADB = 26

/**
 * Moves [file] and the files that belong to it out of the way.
 *
 * The journal and the write-ahead log go too. Left behind, a hot journal is replayed into whatever
 * database next appears under that name — the replacement, corrupted by the leftovers of the file
 * it replaced.
 */
private fun setAside(file: File): File {
    val target = File(file.path + ".unreadable")
    for (suffix in listOf("", "-journal", "-wal", "-shm")) {
        val from = File(file.path + suffix)
        val to = File(target.path + suffix)
        // Cleared first, so a journal from an earlier damaged file cannot end up beside this one.
        to.delete()
        if (from.exists()) Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    return target
}
