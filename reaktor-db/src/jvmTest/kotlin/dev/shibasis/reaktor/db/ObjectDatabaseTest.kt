package dev.shibasis.reaktor.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.db.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@Serializable
private data class TestUser(val name: String, val age: Int)

@Serializable
private data class TestConfig(val debug: Boolean, val version: Int)

class ObjectDatabaseTest {

    private fun createDb(): SqliteObjectDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return SqliteObjectDatabase(driver, "test")
    }

    private fun now() = System.currentTimeMillis()

    // ── Basic CRUD ──

    @Test
    fun `put and get round-trips a value`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        val result = store.get<TestUser>("alice")

        assertNotNull(result)
        assertEquals("Alice", result.value.name)
        assertEquals(30, result.value.age)
        assertEquals("alice", result.key)
        assertEquals("users", result.storeName)
    }

    @Test
    fun `get returns null for missing key`() = runTest {
        val db = createDb()
        val store = db.store("users")
        assertNull(store.get<TestUser>("nobody"))
    }

    @Test
    fun `put overwrites existing value`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        store.put("alice", TestUser("Alice", 31))

        assertEquals(31, store.get<TestUser>("alice")?.value?.age)
    }

    @Test
    fun `delete removes a key`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        store.delete("alice")
        assertNull(store.get<TestUser>("alice"))
    }

    @Test
    fun `clear removes all keys in a store`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        store.put("bob", TestUser("Bob", 25))
        store.clear()

        assertNull(store.get<TestUser>("alice"))
        assertNull(store.get<TestUser>("bob"))
    }

    @Test
    fun `getAll returns all entries in a store`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        store.put("bob", TestUser("Bob", 25))

        val all = store.getAll<TestUser>()
        assertEquals(2, all.size)
        assertTrue(all.any { it.value.name == "Alice" })
        assertTrue(all.any { it.value.name == "Bob" })
    }

    // ── Store isolation ──

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
    fun `store is a singleton per name`() {
        val db = createDb()
        assertSame(db.store("x"), db.store("x"))
    }

    // ── L1 memory cache ──

    @Test
    fun `cache serves reads without hitting db`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))

        val r1 = store.get<TestUser>("alice")
        val r2 = store.get<TestUser>("alice")
        assertEquals(r1?.value, r2?.value)
    }

    @Test
    fun `delete invalidates L1 cache`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        store.get<TestUser>("alice")
        store.delete("alice")
        assertNull(store.get<TestUser>("alice"))
    }

    @Test
    fun `clear invalidates L1 cache`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        store.get<TestUser>("alice")
        store.clear()
        assertNull(store.get<TestUser>("alice"))
    }

    @Test
    fun `NoEviction policy always allows reads`() = runTest {
        val db = createDb()
        val store = db.store("nocache").apply { eviction = NoEviction }

        store.put("alice", TestUser("Alice", 30))
        val result = store.get<TestUser>("alice")
        assertNotNull(result)
        assertEquals("Alice", result.value.name)
    }

    // ── L2 SQLite eviction (TTL) ──

    @Test
    fun `stale SQLite data is evicted on read`() = runTest {
        val db = createDb()
        val store = db.store("users").apply {
            eviction = LruEviction(ttl = 100.milliseconds)
        }

        store.put("alice", TestUser("Alice", 30))
        store.memory.clear()
        Thread.sleep(150)

        assertNull(store.get<TestUser>("alice"))
    }

    @Test
    fun `NoEviction always returns true - no SQLite eviction`() = runTest {
        val db = createDb()
        val store = db.store("auth").apply { eviction = NoEviction }

        store.put("token", "abc123")
        val result = store.get<String>("token")
        assertNotNull(result)
    }

    // ── MemoryCache unit tests ──

    @Test
    fun `MemoryCache evicts oldest entry when capacity exceeded`() {
        val t = now()
        val cache = MemoryCache(capacity = 2)

        cache.put(StoredObject("a", "va", "s", t, t))
        cache.put(StoredObject("b", "vb", "s", t, t))
        cache.put(StoredObject("c", "vc", "s", t, t))

        assertNull(cache.get<String>("a"))
        assertNotNull(cache.get<String>("b"))
        assertNotNull(cache.get<String>("c"))
    }

    @Test
    fun `MemoryCache access promotes entry`() {
        val t = now()
        val cache = MemoryCache(capacity = 2)

        cache.put(StoredObject("a", "va", "s", t, t))
        cache.put(StoredObject("b", "vb", "s", t, t))

        cache.get<String>("a") // promote "a"
        cache.put(StoredObject("c", "vc", "s", t, t)) // evicts "b"

        assertNotNull(cache.get<String>("a"))
        assertNull(cache.get<String>("b"))
        assertNotNull(cache.get<String>("c"))
    }

    @Test
    fun `MemoryCache remove and clear work`() {
        val t = now()
        val cache = MemoryCache()
        cache.put(StoredObject("a", "v", "s", t, t))
        cache.put(StoredObject("b", "v", "s", t, t))

        cache.remove("a")
        assertNull(cache.get<String>("a"))
        assertNotNull(cache.get<String>("b"))

        cache.clear()
        assertNull(cache.get<String>("b"))
    }

    // ── LruEviction unit tests ──

    @Test
    fun `LruEviction isValid checks TTL`() {
        val t = now()
        val eviction = LruEviction(ttl = 100.milliseconds)

        assertTrue(eviction.isValid(StoredObject("k", "v", "s", t, t)))
        assertFalse(eviction.isValid(StoredObject("k", "v", "s", t - 200, t - 200)))
    }

    @Test
    fun `LruEviction evicts when maxEntries exceeded`() {
        val eviction = LruEviction(maxEntries = 2)

        assertTrue(eviction.onWrite("a", 10).isEmpty())
        assertTrue(eviction.onWrite("b", 10).isEmpty())
        val evicted = eviction.onWrite("c", 10)

        assertEquals(listOf("a"), evicted)
    }

    @Test
    fun `LruEviction evicts when maxBytes exceeded`() {
        val eviction = LruEviction(maxBytes = 100)

        assertTrue(eviction.onWrite("a", 40).isEmpty())
        assertTrue(eviction.onWrite("b", 40).isEmpty())
        val evicted = eviction.onWrite("c", 40)

        assertEquals(listOf("a"), evicted)
    }

    @Test
    fun `LruEviction onDelete frees tracked bytes`() {
        val eviction = LruEviction(maxBytes = 100)

        eviction.onWrite("a", 60)
        eviction.onWrite("b", 30)
        eviction.onDelete("a")
        // totalBytes now 30, adding 60 should fit
        val evicted = eviction.onWrite("c", 60)
        assertTrue(evicted.isEmpty())
    }

    // ── ObjectFlow ──

    @Test
    fun `flow reflects puts`() = runTest {
        val db = createDb()
        val store = db.store("users")

        val flow = store.flow<TestUser>("alice")
        assertNull(flow.value)

        store.put("alice", TestUser("Alice", 30))
        assertEquals("Alice", flow.value?.name)

        store.put("alice", TestUser("Alice", 31))
        assertEquals(31, flow.value?.age)
    }

    @Test
    fun `flow StateFlow emits updates`() = runTest {
        val db = createDb()
        val store = db.store("users")
        val flow = store.flow<TestUser>("alice")

        store.put("alice", TestUser("Alice", 30))

        val emitted = flow.flow.first { it != null }
        assertEquals("Alice", emitted?.value?.name)
    }

    @Test
    fun `flow load populates from database`() = runTest {
        val db = createDb()
        val store = db.store("users")

        store.put("alice", TestUser("Alice", 30))
        store.memory.clear()

        val flow = store.flow<TestUser>("alice")
        assertNull(flow.value)

        flow.load()
        assertEquals("Alice", flow.value?.name)
    }

    @Test
    fun `flow is singleton per key`() {
        val db = createDb()
        val store = db.store("users")
        assertSame(store.flow<TestUser>("alice"), store.flow<TestUser>("alice"))
    }

    // ── Events ──

    @Test
    fun `events are emitted after successful operations`() = kotlinx.coroutines.runBlocking {
        val db = createDb()
        val store = db.store("users")
        val events = mutableListOf<DatabaseEvent>()

        val job = launch {
            db.events.collect { events.add(it) }
        }
        kotlinx.coroutines.yield()

        store.put("alice", TestUser("Alice", 30))
        store.memory.clear()
        store.get<TestUser>("alice")
        store.delete("alice")
        store.clear()
        kotlinx.coroutines.yield()

        assertTrue(events.any { it is DatabaseEvent.Put }, "Expected Put event")
        assertTrue(events.any { it is DatabaseEvent.Get }, "Expected Get event")
        assertTrue(events.any { it is DatabaseEvent.Delete }, "Expected Delete event")
        assertTrue(events.any { it is DatabaseEvent.Clear }, "Expected Clear event")

        job.cancel()
    }

    // ── String values ──

    @Test
    fun `stores and retrieves simple string values`() = runTest {
        val db = createDb()
        val store = db.store("kv")

        store.put("token", "abc123")
        assertEquals("abc123", store.get<String>("token")?.value)
    }

    // ── Database-level clear ──

    @Test
    fun `database clear wipes all stores`() = runTest {
        val db = createDb()
        val s1 = db.store("a")
        val s2 = db.store("b")

        s1.put("x", "v1")
        s2.put("y", "v2")

        s1.memory.clear()
        s2.memory.clear()

        db.clear()

        assertNull(s1.get<String>("x"))
        assertNull(s2.get<String>("y"))
    }
}
