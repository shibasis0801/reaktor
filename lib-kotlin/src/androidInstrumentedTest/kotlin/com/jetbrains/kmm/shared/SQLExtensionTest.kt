package com.jetbrains.kmm.shared

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.time.measureTime

class SQLExtensionTest {
    private lateinit var sqlHelper: SqlHelper
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sqlHelper = SqlHelper(context)
    }

    @Test
    fun checkExtension() {
        assert(sqlHelper.checkLoadExtensionEnabled())
    }

    @Test
    fun checkDBSize() {
        var size = 0L
        var avg = 0.0
        for (i in 1..100) {
            val time = measureTimeMillis { size = sqlHelper.checkDBSize() }
            avg += time
            println("DB Size: $size : $time")
        }

        avg /= 100
        assert(size > 0)
    }
}