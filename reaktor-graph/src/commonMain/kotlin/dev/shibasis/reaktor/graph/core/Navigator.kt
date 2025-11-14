package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.core.capabilities.Capability
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import kotlinx.serialization.Serializable
import kotlin.js.JsExport


@JsExport
@Serializable
open class Parameters(
    val routeParams: HashMap<String, String> = hashMapOf()
)


@JsExport
data class BackStackEntry(
    val route: RouteNode<out Parameters>,
    val props: Parameters
): Unique by UniqueImpl()


@JsExport
sealed class NavCommand {
    data class Push(
        val target: RouteNode<out Parameters>,
        val props: Parameters
    ): NavCommand()

    data class Replace(
        val target: RouteNode<out Parameters>,
        val props: Parameters
    ): NavCommand()

    object Pop: NavCommand()
    object PopToRoot: NavCommand()
//    data class PopTo(val predicate: (BackStackEntry) -> Boolean) : NavCommand()
}

interface NavigationCapability: Capability {
    // back stack, etc
    fun dispatch(navCommand: NavCommand)
}

class NavigationCapabilityImpl: NavigationCapability {
    override fun dispatch(navCommand: NavCommand) {
        TODO("Not yet implemented")
    }

    override fun close() {

    }
}