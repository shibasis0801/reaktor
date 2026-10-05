@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.*
import kotlin.js.Promise
import kotlin.test.*

class PostgresTransactionTest {
    private class Fixture {
        var commits=0;var rollbacks=0
        val rolledBack=CompletableDeferred<Unit>()
        val connection: dynamic=js("({})")
        val client: dynamic=js("({})")
        init {
            connection.unsafe={ _: String, _: dynamic -> Promise.resolve(emptyArray<dynamic>()) }
            client.begin={ block: dynamic ->
                block(connection).unsafeCast<Promise<dynamic>>().then({ value: dynamic -> commits++;value },{ error: Throwable -> rollbacks++;rolledBack.complete(Unit);throw error })
            }
        }
        val database=PostgresDatabase(client)
    }
    @Test fun commitOnlyAfterTheWholeSuspendingBlockCompletes()=GlobalScope.promise {
        val f=Fixture()
        val value=f.database.transaction { rawRows("SELECT 1");yield();123 }
        assertEquals(123,value);assertEquals(1,f.commits);assertEquals(0,f.rollbacks)
    }
    @Test fun failureRollsBackAndPreservesTheError()=GlobalScope.promise {
        val f=Fixture()
        try { f.database.transaction { rawRows("SELECT 1");error("creation failed") };fail("Expected rollback") }
        catch(e: IllegalStateException) { assertEquals("creation failed",e.message) }
        assertEquals(0,f.commits);assertEquals(1,f.rollbacks)
    }
    @Test fun cancellationReachesTheTransactionCallbackAndRollsBack()=GlobalScope.promise {
        val f=Fixture();val entered=CompletableDeferred<Unit>()
        coroutineScope {
            val job=launch { f.database.transaction { entered.complete(Unit);awaitCancellation() } }
            entered.await();job.cancelAndJoin()
        }
        withTimeout(1000) { f.rolledBack.await() }
        assertEquals(0,f.commits);assertEquals(1,f.rollbacks)
    }
    @Test fun nestingIsRefusedAndRollsBackTheOuterTransaction()=GlobalScope.promise {
        val f=Fixture()
        try { f.database.transaction { transaction { 123 } };fail("Expected refusal") } catch(_: IllegalStateException) {}
        assertEquals(0,f.commits);assertEquals(1,f.rollbacks)
    }
}
