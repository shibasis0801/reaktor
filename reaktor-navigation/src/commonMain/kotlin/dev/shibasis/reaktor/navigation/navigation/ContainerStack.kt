package dev.shibasis.reaktor.navigation.navigation

import androidx.compose.runtime.mutableStateOf
import dev.shibasis.reaktor.navigation.route.Container


/*
todo
screens/switches are stateless, a container is not.
So while naive push/pop worked for screens, it wont here
we need a copy/factory mechanism
*/
class ContainerStack(root: Container) {
    val current = mutableStateOf(root)
    val handlesBack = mutableStateOf(false)

    private val stack = ArrayDeque(listOf(current.value))

    fun push(container: Container) {
        stack.add(container)
        current.value = container
        handlesBack.value = true
    }

    fun replace(container: Container) {
        stack.removeLast()
        stack.add(container)
        current.value = container
    }

    fun pop() {
        stack.removeLast()
        current.value = stack.last()
        if (stack.size == 1) handlesBack.value = false
    }
}