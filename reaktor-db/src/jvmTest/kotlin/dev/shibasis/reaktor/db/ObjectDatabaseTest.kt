package dev.shibasis.reaktor.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.db.core.ExpireAfter
import dev.shibasis.reaktor.db.core.KeepForever
import dev.shibasis.reaktor.db.core.LruObjectCache
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.db.core.TimestampProvider
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Serializable
private data class TestUser(val name: String, val age: Int)

@Serializable
private data class TestConfig(val debug: Boolean, val version: Int)

private class FixedTimestampProvider(
    var now: Long = 1_000L,
) : TimestampProvider {
    override fun getTimestamp(): Long = now
}

private class MapObjectDatabase : ObjectDatabase(TextSerializer()) {
    private val rows = mutableMapOf<ObjectAddress, StoredObject<*>>()
    private var now = 1_000L

    fun <T : Any> mutateRaw(
        storeName: String,
        key: String,
        value: T,
    ) {
        val address = ObjectAddress(storeName, key)
        val previous = rows[address]
        rows[address] = StoredObject(
            key = key,
            value = value,
            storeName = storeName,
            createdAt = previous?.createdAt ?: ++now,
            updatedAt = ++now,
        )
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> putRaw(
        storeName: String,
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): StoredObject<T> {
        mutateRaw(storeName, key, value)
        return getRaw(storeName, key, value::class as KClass<T>, serializer)!!
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> getRaw(
        storeName: String,
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): StoredObject<T>? {
        return rows[ObjectAddress(storeName, key)] as? StoredObject<T>
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> getAllRaw(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): List<StoredObject<T>> {
        return rows.values
            .filter { it.storeName == storeName && type.isInstance(it.value) }
            .map { it as StoredObject<T> }
    }

    override suspend fun deleteRaw(storeName: String, key: String) {
        rows.remove(ObjectAddress(storeName, key))
    }

    override suspend fun clearRaw(storeName: String) {
        rows.keys
            .filter { it.storeName == storeName }
            .forEach { rows.remove(it) }
    }

    override suspend fun clearRaw() {
        rows.clear()
    }
}

class ObjectDatabaseTest {

    private fun createDb(
        timestampProvider: TimestampProvider = FixedTimestampProvider(),
    ): SqliteObjectDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return SqliteObjectDatabase(driver, "test", timestampProvider = timestampProvider)
    }

    @Test
    fun `store is a singleton per name and state is a singleton per key and type`() {
        val db = createDb()
        val users = db.store("users")

        assertSame(users, db.store("users"))
        assertSame(users.state<TestUser>("me"), users.state<TestUser>("me"))
    }

    @Test
    fun `same store key cannot be opened as incompatible types`() {
        val db = createDb()
        val users = db.store("users")

        users.state<TestUser>("me")

        assertFailsWith<IllegalArgumentException> {
            users.state<TestConfig>("me")
        }
    }

    @Test
    fun `state set load update and delete keep StateFlow consistent`() = runTest {
        val db = createDb()
        val state = db.store("users").state<TestUser>("alice")

        assertNull(state.value)

        state.set(TestUser("Alice", 30))
        assertEquals(TestUser("Alice", 30), state.value)
        assertEquals(TestUser("Alice", 30), state.load()?.value)

        state.update { it.copy(age = it.age + 1) }
        assertEquals(31, state.value?.age)

        state.delete()
        assertNull(state.value)

        state.set(TestUser("Alice", 32))
        assertEquals(32, state.value?.age)
    }

    @Test
    fun `store convenience methods delegate through object state`() = runTest {
        val db = createDb()
        val users = db.store("users")

        users.put("alice", TestUser("Alice", 30))
        users.update<TestUser>("alice") { it.copy(age = 31) }

        val result = users.get<TestUser>("alice")

        assertNotNull(result)
        assertEquals("Alice", result.value.name)
        assertEquals(31, result.value.age)
        assertEquals("alice", result.key)
        assertEquals("users", result.storeName)
    }

    @Test
    fun `get returns null for missing key`() = runTest {
        val db = createDb()
        assertNull(db.store("users").get<TestUser>("nobody"))
    }

    @Test
    fun `getAll returns all entries in a store`() = runTest {
        val db = createDb()
        val users = db.store("users")

        users.put("alice", TestUser("Alice", 30))
        users.put("bob", TestUser("Bob", 25))

        val all = users.getAll<TestUser>()

        assertEquals(2, all.size)
        assertTrue(all.any { it.value.name == "Alice" })
        assertTrue(all.any { it.value.name == "Bob" })
    }

    @Test
    fun `stores are isolated from each other`() = runTest {
        val db = createDb()
        val users = db.store("users")
        val config = db.store("config")

        users.put("key", TestUser("Alice", 30))
        config.put("key", TestConfig(debug = true, version = 1))

        assertEquals("Alice", users.get<TestUser>("key")?.value?.name)
        assertEquals(true, config.get<TestConfig>("key")?.value?.debug)
    }

    @Test
    fun `clear one store does not affect another`() = runTest {
        val db = createDb()
        val users = db.store("users")
        val config = db.store("config")

        users.put("a", TestUser("A", 1))
        config.put("b", TestConfig(debug = false, version = 2))

        users.clear()

        assertNull(users.get<TestUser>("a"))
        assertNotNull(config.get<TestConfig>("b"))
    }

    @Test
    fun `database clear wipes all stores and live states`() = runTest {
        val db = createDb()
        val a = db.store("a").state<String>("x")
        val b = db.store("b").state<String>("y")

        a.set("v1")
        b.set("v2")

        db.clear()

        assertNull(a.value)
        assertNull(b.value)
        assertNull(a.refresh())
        assertNull(b.refresh())
    }

    @Test
    fun `LruObjectCache evicts memory only`() = runTest {
        val db = createDb()
        val users = db.store("users") {
            cache = LruObjectCache(maxEntries = 1)
        }

        users.put("alice", TestUser("Alice", 30))
        users.put("bob", TestUser("Bob", 25))

        assertNull(users.cache.get(ObjectAddress("users", "alice"), TestUser::class))
        assertNotNull(users.cache.get(ObjectAddress("users", "bob"), TestUser::class))
        assertEquals("Alice", users.state<TestUser>("alice").refresh()?.value?.name)
    }

    @Test
    fun `cache access promotes entry`() {
        val t = 1_000L
        val cache = LruObjectCache(maxEntries = 2)

        cache.put(StoredObject("a", "va", "s", t, t))
        cache.put(StoredObject("b", "vb", "s", t, t))

        cache.get(ObjectAddress("s", "a"), String::class)
        cache.put(StoredObject("c", "vc", "s", t, t))

        assertNotNull(cache.get(ObjectAddress("s", "a"), String::class))
        assertNull(cache.get(ObjectAddress("s", "b"), String::class))
        assertNotNull(cache.get(ObjectAddress("s", "c"), String::class))
    }

    @Test
    fun `KeepForever always allows reads`() = runTest {
        val db = createDb()
        val store = db.store("auth") {
            retention = KeepForever
        }

        store.put("token", "abc123")

        assertEquals("abc123", store.get<String>("token")?.value)
    }

    @Test
    fun `ExpireAfter deletes stale persisted data on read`() = runTest {
        val clock = FixedTimestampProvider(now = 1_000L)
        val db = createDb(clock)
        val sessions = db.store("sessions") {
            retention = ExpireAfter(100.milliseconds) { clock.now }
        }
        val token = sessions.state<String>("token")

        token.set("abc123")
        clock.now = 1_200L

        assertNull(token.refresh())
        assertNull(token.value)
        assertNull(token.refresh())
    }

    @Test
    fun `delete does not detach live state`() = runTest {
        val db = createDb()
        val users = db.store("users")
        val state = users.state<TestUser>("alice")

        state.set(TestUser("Alice", 30))
        users.delete("alice")
        assertNull(state.value)

        users.put("alice", TestUser("Alice", 31))
        assertEquals(31, state.value?.age)
        assertSame(state, users.state<TestUser>("alice"))
    }

    @Test
    fun `invalidate refreshes live state from backend`() = runTest {
        val db = MapObjectDatabase()
        val users = db.store("users")
        val state = users.state<TestUser>("alice")

        state.set(TestUser("Alice", 30))
        db.mutateRaw("users", "alice", TestUser("Alicia", 31))

        assertEquals("Alice", state.value?.name)

        db.invalidate("users", "alice")

        assertEquals("Alicia", state.value?.name)
        assertEquals(31, state.value?.age)
    }

    @Test
    fun `events are emitted after successful operations`() = runTest {
        val db = createDb()
        val users = db.store("users")
        val state = users.state<TestUser>("alice")
        val events = mutableListOf<DatabaseEvent>()

        val job = launch {
            db.events.collect { events.add(it) }
        }
        yield()

        state.set(TestUser("Alice", 30))
        state.refresh()
        state.delete()
        users.clear()
        yield()

        assertTrue(events.any { it is DatabaseEvent.Put<*> && it.stored.value == TestUser("Alice", 30) })
        assertTrue(events.any { it is DatabaseEvent.Get }, "Expected Get event")
        assertTrue(events.any { it is DatabaseEvent.Delete }, "Expected Delete event")
        assertTrue(events.any { it is DatabaseEvent.Clear }, "Expected Clear event")

        job.cancel()
    }

    @Test
    fun `sqlite upsert returns preserved createdAt and latest updatedAt`() = runTest {
        val clock = FixedTimestampProvider(now = 1_000L)
        val db = createDb(clock)
        val user = db.store("users").state<TestUser>("alice")

        val first = user.set(TestUser("Alice", 30))
        clock.now = 2_000L
        val second = user.set(TestUser("Alice", 31))

        assertEquals(first.createdAt, second.createdAt)
        assertEquals(2_000L, second.updatedAt)
        assertFalse(second.createdAt == second.updatedAt)
    }
}
