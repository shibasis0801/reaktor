package app.mehmaan.navigation.route

import app.mehmaan.navigation.pod.Pod
import app.mehmaan.navigation.screen.Props

/*
All route additions must be through junctions.
The root junction has the name "/"
Every junction has an Error Destination
todo solve if junction attached to multiple parents
todo critical -> when junctions / destinations are added, the full tree must be updated
*/
class Junction(
    name: String,
    parentPath: String? = null,
    val errorDestination: Destination<Props>? = null,
    junctionBuilder: Junction.() -> Unit
): Route(name) {
    // not thread safe, just want something like an optional val
    var Index: Destination<Props>? = null

    internal val routes = hashMapOf<String, Route>()


    // todo critical wrong implementation
    init {
        if (name == "/") {
            path = "/"
        }
        else {
            if (parentPath == "/")
                path = "/$name"
            else
                path = "$parentPath/$name"
        }
    }

    fun<T: Props> destination(destination: Destination<T>): Destination<T> {
        val destinationPath = "$path/${destination.name}"
        destination.path = destinationPath
        routes[destination.name] = destination
        return destination
    }

    fun<T: Props> index(destination: Destination<T>) = destination(destination).also {
        Index = destination as Destination<Props>
    }


    fun junction(junction: Junction): Junction {
        routes[junction.name] = junction
        return junction
    }
    fun junction(pod: Pod<*>) = junction(pod.junction)
    fun junction(name: String, errorDestination: Destination<Props>? = null, junctionBuilder: Junction.() -> Unit): Junction {
        return junction(Junction(name, path, errorDestination, junctionBuilder))
    }

    init { junctionBuilder() }
}


































