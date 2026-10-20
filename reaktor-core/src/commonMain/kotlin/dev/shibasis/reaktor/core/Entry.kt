package dev.shibasis.reaktor.core.build

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Entry

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class WindowTest

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class HangarFlow
