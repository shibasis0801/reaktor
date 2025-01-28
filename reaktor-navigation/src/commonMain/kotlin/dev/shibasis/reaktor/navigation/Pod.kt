package dev.shibasis.reaktor.navigation

import dev.shibasis.reaktor.navigation.route.Container
import org.koin.core.module.Module

interface Pod {
    fun injectables(): Module
    fun container(): Container
}