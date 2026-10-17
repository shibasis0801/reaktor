@file:JsModule("react")

package dev.shibasis.reaktor.surface.react

import react.Context

internal external fun <T> useContext(context: Context<T>): T
