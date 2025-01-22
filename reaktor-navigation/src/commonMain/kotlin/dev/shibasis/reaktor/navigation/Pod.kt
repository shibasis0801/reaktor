package dev.shibasis.reaktor.navigation

import dev.shibasis.reaktor.navigation.route.Switch
import org.koin.core.module.Module

interface Pod {
    fun injectables(): Module
    fun switch(): Switch
}