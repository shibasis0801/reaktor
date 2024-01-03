package dev.shibasis.reaktor.flatinvoker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Parcel
import android.os.Parcelable

const val DATABASE_VERSION = 1
const val DATABASE_NAME = "ExampleDatabase.db"

class SqlHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION),
    Parcelable {

       constructor(parcel: Parcel) : this(parcel.readParcelable(Context::class.java.classLoader)!!)

    override fun onCreate(db: SQLiteDatabase) {
        // Here you can create tables if needed
        // Create a table and insert tons of data using a for loop

        db.execSQL("CREATE TABLE IF NOT EXISTS example_table (id INTEGER PRIMARY KEY, name TEXT)")
        for (i in 0..1000000) {
            db.execSQL("INSERT INTO example_table (name) VALUES ('Example $i')")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This method is for upgrading the database if needed
    }

    // write a function to check the size of the DB

    fun checkDBSize(): Long {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT page_count * page_size as Size FROM pragma_page_count(), pragma_page_size()", null)
        var size = 0L
        if (cursor.moveToFirst()) {
            size = cursor.getLong(0)
        }
        cursor.close()
        return size
    }

    fun checkLoadExtensionEnabled(): Boolean {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT sqlite_compileoption_used('ENABLE_LOAD_EXTENSION')", null)
        var isEnabled = false
        if (cursor.moveToFirst()) {
            isEnabled = cursor.getInt(0) > 0
        }
        cursor.close()
        return isEnabled
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {

    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<SqlHelper> {
        override fun createFromParcel(parcel: Parcel): SqlHelper {
            return SqlHelper(parcel)
        }

        override fun newArray(size: Int): Array<SqlHelper?> {
            return arrayOfNulls(size)
        }
    }
}