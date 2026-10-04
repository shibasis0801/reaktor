package dev.shibasis.reaktor.surface

interface ItemSource<out T> : CollectionItems {
    operator fun get(index: Int): T
}

interface TreeSource<out T> : ItemSource<T>, TreeItems

fun <T> listSource(
    items: List<T>,
    key: (T) -> String,
    text: (T) -> String? = { null },
    enabled: (T) -> Boolean = { true },
): ItemSource<T> = ListSource(items, key, text, enabled)

fun <T> treeSource(
    roots: List<T>,
    key: (T) -> String,
    children: (T) -> List<T>,
    expanded: Set<String>,
    text: (T) -> String? = { null },
    enabled: (T) -> Boolean = { true },
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
    return TreeRows(listSource(items, key, text, enabled), depths, parents, children, expanded)
}

private class ListSource<T>(
    private val items: List<T>,
    private val keyOf: (T) -> String,
    private val textOf: (T) -> String?,
    private val enabledOf: (T) -> Boolean,
) : ItemSource<T> {
    private val positions by lazy { items.indices.associateBy { keyOf(items[it]) } }

    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
    override fun key(index: Int): String = keyOf(items[index])
    override fun indexOf(key: String): Int = positions[key] ?: -1
    override fun enabled(index: Int): Boolean = enabledOf(items[index])
    override fun text(index: Int): String? = textOf(items[index])
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
    override fun expanded(index: Int): Boolean = key(index) in open
}
