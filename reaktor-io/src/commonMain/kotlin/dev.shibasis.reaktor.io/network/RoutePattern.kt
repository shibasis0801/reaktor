package dev.shibasis.reaktor.io.network

import co.touchlab.kermit.Logger

/* Use https://regexr.com/ to understand, and GPT */
object RegexCommon {
    val surroundedByBraces =  """\{([^}]+)\}""".toRegex()
    const val beforeSlash = "([^/]+)"
}


data class RoutePattern(
    val original: String = "/",
    val regex: Regex = Regex(""),
    val paramNames: List<String> = listOf()
) {
    fun params(matchResult: MatchResult): Map<String, String> {
        val values = matchResult.groupValues.drop(1)
        return paramNames.zip(values).toMap()
    }

    fun fill(params: Map<String, String>): String {
        return paramNames.fold(original) { acc, paramName ->
            val value = params[paramName]
            if (value == null) {
                Logger.e("RoutePattern.fill: Missing parameter $paramName")
                return acc
            }

            acc.replace("{$paramName}", value)
        }
    }

    companion object {
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
        fun from(route: String): RoutePattern {
            val paramRegex = RegexCommon.surroundedByBraces
            val paramNames = mutableListOf<String>()

            val pattern = route.replace(paramRegex) { match ->
                paramNames.add(match.groups[1]!!.value)
                return@replace RegexCommon.beforeSlash
            }

            return RoutePattern(
                original = route,
                regex = Regex("^$pattern$"),
                paramNames = paramNames
            )
        }
    }
}

