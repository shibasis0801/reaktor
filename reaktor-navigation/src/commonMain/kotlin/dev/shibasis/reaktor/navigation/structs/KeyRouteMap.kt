package dev.shibasis.reaktor.navigation.structs

class KeyRouteMap {
    val routeToKeyMap = linkedMapOf<String, String>()
    val keyToRouteMap = linkedMapOf<String, String>()


    fun storeMapping(route: String, key: String) {
        routeToKeyMap[route] = key
        keyToRouteMap[key] = route
    }

    fun routeFor(key: String) = keyToRouteMap[key]
    fun keyFor(route: String) = routeToKeyMap[route]
}