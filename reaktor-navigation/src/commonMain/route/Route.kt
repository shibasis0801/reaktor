package app.mehmaan.navigation.route

import app.mehmaan.navigation.screen.ErrorScreen
import app.mehmaan.navigation.screen.ErrorScreenProps
import app.mehmaan.navigation.screen.Props

/*
Every route has a name
Once attached to a junction, it also has a path
You use this path to navigate
 */
sealed class Route(val name: String, var path: String? = null)

// todo figure how to avoid this
@Suppress("UNCHECKED_CAST")
fun getErrorRoute(
    errorScreenProps: ErrorScreenProps = ErrorScreenProps()
): Destination<Props> {
  return Destination<ErrorScreenProps>("error", errorScreenProps) { props -> ErrorScreen(props) } as Destination<Props>
}