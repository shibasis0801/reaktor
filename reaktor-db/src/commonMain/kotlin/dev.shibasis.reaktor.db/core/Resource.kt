package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.framework.Async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration

class Resource<T : Any>(
    private val state: ObjectState<T>?,
    private val maxAge: Duration,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val fetch: suspend () -> T?,
) {
    private val held = MutableStateFlow<T?>(null)
    private val gate = Mutex()
    private var fetchedAt = 0L
    private var loaded = false

    val value: StateFlow<T?> = held.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun values(): Flow<T?> = value.onStart { restore() }.distinctUntilChanged()

    suspend fun restore(): T? {
        if (loaded) return held.value
        val stored = withContext(Dispatchers.Async) { runCatching { state?.load() }.getOrNull() }
        if (!loaded) {
            loaded = true
            if (held.value == null && stored != null) {
                held.value = stored.value
                fetchedAt = stored.updatedAt
            }
        }
        return held.value
    }

    fun stale(): Boolean = now() - fetchedAt > maxAge.inWholeMilliseconds

    suspend fun ensure(): T? {
        restore()
        return if (held.value == null || stale()) refresh() ?: held.value else held.value
    }

    suspend fun refresh(): T? {
        val asked = now()
        return gate.withLock {
            if (fetchedAt >= asked && held.value != null) return@withLock held.value
            val fresh = try {
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            _failed.value = fresh == null
            if (fresh != null) {
                held.value = fresh
                fetchedAt = now()
                withContext(Dispatchers.Async) { runCatching { state?.set(fresh) } }
            }
            fresh
        }
    }

    suspend fun put(value: T) {
        gate.withLock {
            held.value = value
            fetchedAt = now()
            withContext(Dispatchers.Async) { runCatching { state?.set(value) } }
        }
    }

    suspend fun forget() {
        gate.withLock {
            held.value = null
            fetchedAt = 0L
            withContext(Dispatchers.Async) { runCatching { state?.delete() } }
        }
    }
}
