package dev.shibasis.reaktor.navigation.common

data class RoutePattern(
    val original: String,
    val regex: Regex,
    val paramNames: List<String>
)

/*
"/{id}/something/{members}"
    becomes
RoutePattern(
    original=/{id}/something/{members},
    regex=^/([^/]+)/something/([^/]+)$,
    paramNames=[id, members]
)

/something
    becomes
null

*/
fun buildRoutePattern(route: String): RoutePattern? {
    /* Use https://regexr.com/ to understand, and GPT */
    val paramRegex = """\{([^}]+)\}""".toRegex()
    val paramNames = mutableListOf<String>()

    val pattern = route.replace(paramRegex) { match ->
        paramNames.add(match.groups[1]!!.value)
        "([^/]+)"
    }

    if (paramNames.isEmpty()) return null

    return RoutePattern(
        original = route,
        regex = Regex("^$pattern$"),
        paramNames = paramNames
    )
}
