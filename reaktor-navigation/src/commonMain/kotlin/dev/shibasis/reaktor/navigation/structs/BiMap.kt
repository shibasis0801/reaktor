package dev.shibasis.reaktor.navigation.structs

class BiMap<Key, Value>{
    private val map = hashMapOf<Key, Value>()
    private val reverseMap = hashMapOf<Value, Key>()

    fun put(key: Key, value: Value) {
        map[key] = value
        reverseMap[value] = key
    }

    fun get(key: Key) = map[key]
    fun reverseGet(value: Value) = reverseMap[value]

    fun containsKey(key: Key) = map.containsKey(key)
    fun containsValue(value: Value) = reverseMap.containsKey(value)

    fun remove(key: Key) {
        val value = map[key]
        if (value != null) {
            reverseMap.remove(value)
            map.remove(key)
            }
    }

    fun removeValue(value: Value) {
        val key = reverseMap[value]
        if (key != null) {
            map.remove(key)
            reverseMap.remove(value)
        }
    }

    fun clear() {
        map.clear()
        reverseMap.clear()
    }

    fun size() = map.size

    fun isEmpty() = map.isEmpty()

    fun isNotEmpty() = map.isNotEmpty()
}

