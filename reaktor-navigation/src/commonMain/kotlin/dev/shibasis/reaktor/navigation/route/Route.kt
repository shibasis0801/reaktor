package dev.shibasis.reaktor.navigation.route

import dev.shibasis.reaktor.navigation.screen.ErrorScreen
import dev.shibasis.reaktor.navigation.screen.ErrorScreenProps
import dev.shibasis.reaktor.navigation.screen.Props
import kotlin.js.JsExport

/*
Every route has a name
Once attached to a junction, it also has a path
You use this path to navigate
*/
sealed class Route(val name: String, var path: String? = null)

@Suppress("UNCHECKED_CAST")
fun getErrorRoute(
    errorScreenProps: ErrorScreenProps = ErrorScreenProps()
): Destination<Props> {
  return Destination("error", errorScreenProps) { props -> ErrorScreen(props) } as Destination<Props>
}