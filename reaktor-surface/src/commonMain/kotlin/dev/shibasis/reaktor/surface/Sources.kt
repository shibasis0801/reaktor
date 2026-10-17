package dev.shibasis.reaktor.surface

interface ItemSource<out T> : CollectionItems {
    operator fun get(index: Int): T
    fun originalKey(index: Int): String = key(index)
}

interface TreeSource<out T> : ItemSource<T>, TreeItems

fun interface SurfaceDiagnostics {
    fun duplicateKeys(keys: Set<String>)

    companion object {
        val None = SurfaceDiagnostics {}
    }
}

fun <T> listSource(
    items: List<T>,
    key: (T) -> String,
    text: (T) -> String? = { null },
    enabled: (T) -> Boolean = { true },
    selectable: (T) -> Boolean = { true },
    checked: (T) -> Boolean? = { null },
    diagnostics: SurfaceDiagnostics = SurfaceDiagnostics.None,
    activateOnPress: (T) -> Boolean = { false },
): ItemSource<T> = ListSource(items, key, text, enabled, selectable, checked, diagnostics, activateOnPress)

fun <T> treeSource(
    roots: List<T>,
    key: (T) -> String,
    children: (T) -> List<T>,
    expanded: Set<String>,
    text: (T) -> String? = { null },
    enabled: (T) -> Boolean = { true },
    selectable: (T) -> Boolean = { true },
    diagnostics: SurfaceDiagnostics = SurfaceDiagnostics.None,
    activateOnPress: (T) -> Boolean = { false },
): TreeSource<T> {
    val items = mutableListOf<T>()
    val depths = mutableListOf<Int>()
    val parents = mutableListOf<Int>()
    fun visit(item: T, depth: Int, parent: Int) {
        val index = items.size
        items += item
        depths += depth
        parents += parent
        if (key(item) in expanded) children(item).forEach { visit(it, depth + 1, index) }
    }
    roots.forEach { visit(it, 0, -1) }
    return TreeRows(listSource(items, key, text, enabled, selectable, diagnostics = diagnostics, activateOnPress = activateOnPress), depths, parents, children, expanded)
}

private class ListSource<T>(
    private val items: List<T>,
    private val keyOf: (T) -> String,
    private val textOf: (T) -> String?,
    private val enabledOf: (T) -> Boolean,
    private val selectableOf: (T) -> Boolean,
    private val checkedOf: (T) -> Boolean?,
    private val diagnostics: SurfaceDiagnostics,
    private val activateOnPressOf: (T) -> Boolean,
) : ItemSource<T> {
    private val originals by lazy { items.map(keyOf) }
    private val keys by lazy {
        val occupied = originals.toMutableSet()
        val occurrences = mutableMapOf<String, Int>()
        val repeated = mutableSetOf<String>()
        val unique = originals.map { key ->
            val occurrence = occurrences.getOrElse(key) { 0 } + 1
            occurrences[key] = occurrence
            if (occurrence == 1) key else {
                repeated += key
                var suffix = occurrence
                var candidate = "$key#$suffix"
                while (!occupied.add(candidate)) candidate = "$key#${++suffix}"
                candidate
            }
        }
        if (repeated.isNotEmpty()) diagnostics.duplicateKeys(repeated)
        unique
    }
    private val positions by lazy { keys.withIndex().associate { it.value to it.index } }

    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
    override fun key(index: Int): String = keys[index]
    override fun originalKey(index: Int): String = originals[index]
    override fun indexOf(key: String): Int = positions[key] ?: -1
    override fun enabled(index: Int): Boolean = enabledOf(items[index])
    override fun text(index: Int): String? = textOf(items[index])
    override fun selectable(index: Int): Boolean = selectableOf(items[index])
    override fun checked(index: Int): Boolean? = checkedOf(items[index])
    override fun activateOnPress(index: Int): Boolean = activateOnPressOf(items[index])
}

private class TreeRows<T>(
    rows: ItemSource<T>,
    private val depths: List<Int>,
    private val parents: List<Int>,
    private val children: (T) -> List<T>,
    private val open: Set<String>,
) : TreeSource<T>, ItemSource<T> by rows {
    override fun depth(index: Int): Int = depths[index]
    override fun parent(index: Int): Int = parents[index]
    override fun expandable(index: Int): Boolean = children(get(index)).isNotEmpty()
    override fun expanded(index: Int): Boolean = originalKey(index) in open
}
