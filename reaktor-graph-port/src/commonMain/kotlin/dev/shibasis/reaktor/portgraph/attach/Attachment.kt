package dev.shibasis.reaktor.portgraph.attach

import kotlinx.atomicfu.atomic
/**
 * K2 — typed attachments on a graph entity.
 *
 * Subsystems extend the graph by attaching to it rather than by amending it. Telemetry policy,
 * port semantics, observation overlays, evidence, capture state and port interceptors are all
 * attachments, so none of them needs a field of its own on [dev.shibasis.reaktor.portgraph.port.Port],
 * `PortNode`, `PortGraph` or `Edge`.
 *
 * The raw surface is string-keyed so it stays exportable to JS. Callers use the typed
 * [AttachmentKey] extensions below.
 *
 * [hasAttachments] is the hot-path guard: an entity that has never been attached to pays a
 * single volatile read.
 */
interface Attachable {
    /** True once anything has been attached. Stays true after every attachment is removed. */
    val hasAttachments: Boolean

    fun attachRaw(key: String, value: Any)

    fun attachmentRaw(key: String): Any?

    fun detachRaw(key: String): Any?

    /** Applies [transform] to the current value until it commits. [transform] may be re-run. */
    fun updateRaw(key: String, transform: (Any?) -> Any?): Any?

    /** Every attached key, for tooling that enumerates what a subsystem left behind. */
    fun attachmentKeys(): Set<String>
}

/**
 * A typed handle to one attachment slot. Construct one per subsystem and keep it
 * in a `val`; the [name] is the wire key and must be globally unique.
 */
class AttachmentKey<T : Any>(val name: String) {
    override fun toString() = "AttachmentKey($name)"
}

fun <T : Any> Attachable.attach(key: AttachmentKey<T>, value: T) = attachRaw(key.name, value)

@Suppress("UNCHECKED_CAST")
fun <T : Any> Attachable.attachment(key: AttachmentKey<T>): T? = attachmentRaw(key.name) as T?

@Suppress("UNCHECKED_CAST")
fun <T : Any> Attachable.detach(key: AttachmentKey<T>): T? = detachRaw(key.name) as T?

@Suppress("UNCHECKED_CAST")
fun <T : Any> Attachable.update(key: AttachmentKey<T>, transform: (T?) -> T?): T? =
    updateRaw(key.name) { current -> transform(current as T?) } as T?

/**
 * Lock-free attachment store. Immutable map swapped under compare-and-set, so a reader never
 * observes a partially written map and a concurrent writer never loses an entry — the failure
 * mode a mutable concurrent map produced elsewhere in this codebase.
 */
class Attachments : Attachable {
    private val store = atomic<Map<String, Any>?>(null)

    override val hasAttachments: Boolean get() = store.value != null

    override fun attachRaw(key: String, value: Any) {
        while (true) {
            val current = store.value
            val next = (current ?: emptyMap()) + (key to value)
            if (store.compareAndSet(current, next)) return
        }
    }

    override fun attachmentRaw(key: String): Any? = store.value?.get(key)

    override fun detachRaw(key: String): Any? {
        while (true) {
            val current = store.value ?: return null
            val existing = current[key] ?: return null
            if (store.compareAndSet(current, current - key)) return existing
        }
    }

    override fun updateRaw(key: String, transform: (Any?) -> Any?): Any? {
        while (true) {
            val current = store.value
            val updated = transform(current?.get(key))
            val next = when {
                updated == null -> (current ?: emptyMap()) - key
                else -> (current ?: emptyMap()) + (key to updated)
            }
            if (store.compareAndSet(current, next)) return updated
        }
    }

    override fun attachmentKeys(): Set<String> = store.value?.keys ?: emptySet()
}
