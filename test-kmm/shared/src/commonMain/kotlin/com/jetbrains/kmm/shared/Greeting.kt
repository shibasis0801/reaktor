package dev.shibasis.reaktor.flatinvoker

class Greeting {
    fun greeting(): String {
        return "Hello, ${Platform().platform}!"
    }
}
