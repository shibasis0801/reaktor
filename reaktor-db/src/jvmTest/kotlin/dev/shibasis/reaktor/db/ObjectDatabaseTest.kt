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
    fun `raw export and import round-trips a store holding mixed types`() = runTest {
        val db = createDb()
        val store = db.store("backup")

        store.put("user", TestUser("Alice", 30))
        store.put("config", TestConfig(debug = true, version = 7))

        val exported = db.exportRaw("backup")
        assertEquals(2, exported.size)

        store.clear()
        assertNull(store.get<TestUser>("user"))

        db.importRaw(exported)

        assertEquals(TestUser("Alice", 30), store.get<TestUser>("user")?.value)
        assertEquals(TestConfig(debug = true, version = 7), store.get<TestConfig>("config")?.value)
    }

    @Test
    fun `raw export covers every store when no store name is given`() = runTest {
        val db = createDb()
        db.store("users").put("alice", TestUser("Alice", 30))
        db.store("config").put("app", TestConfig(debug = false, version = 1))

        assertEquals(2, db.exportRaw().size)
        assertEquals(1, db.exportRaw("users").size)
        assertEquals(emptyList(), db.exportRaw("nothing-here"))
    }

    @Test
    fun `raw export preserves timestamps`() = runTest {
        val clock = FixedTimestampProvider(now = 1_000L)
        val db = createDb(clock)
        db.store("users").put("alice", TestUser("Alice", 30))
        clock.now = 2_000L
        db.store("users").put("alice", TestUser("Alice", 31))

        val exported = db.exportRaw("users").single()

        assertEquals(1_000L, exported.createdAt)
        assertEquals(2_000L, exported.updatedAt)
        assertEquals("users", exported.storeName)
        assertEquals("alice", exported.key)
    }

    @Test
    fun `import overwrites existing keys and refreshes live state`() = runTest {
        val db = createDb()
        val users = db.store("users")
        val state = users.state<TestUser>("alice")

        state.set(TestUser("Alice", 30))
        val snapshot = db.exportRaw("users")

        state.set(TestUser("Alice", 31))
        assertEquals(31, state.value?.age)

        // A restore has to be visible without restarting the app — the open state must not keep
        // serving what it cached before the import.
        db.importRaw(snapshot)

        assertEquals(30, state.value?.age)
    }

    @Test
    fun `import of an empty backup leaves the database untouched`() = runTest {
        val db = createDb()
        db.store("users").put("alice", TestUser("Alice", 30))

        db.importRaw(emptyList())

        assertEquals(TestUser("Alice", 30), db.store("users").get<TestUser>("alice")?.value)
    }

    @Test
    fun `databases without raw support say so instead of silently doing nothing`() = runTest {
        val db = MapObjectDatabase()

        assertFailsWith<UnsupportedOperationException> { db.exportRaw() }
        assertFailsWith<UnsupportedOperationException> {
            db.importRaw(listOf(RawObject("k", "s", "{}", 1L, 1L)))
        }
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
