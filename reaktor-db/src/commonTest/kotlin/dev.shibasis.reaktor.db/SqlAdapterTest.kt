package dev.shibasis.reaktor.db

import dev.shibasis.reaktor.db.adapters.testDrive
import kotlin.test.Test
import kotlin.test.assertTrue


class SqlAdapterTest {
    @Test
    fun check() {
        testDrive()
        assertTrue { true }
    }
}