package dev.shibasis.flatinvoker.ffi.switch

import dev.shibasis.flatinvoker.ffi.Invokable

// Must use concurrent map (atomicfu/stately/own)
class MapModuleHolder: MutableMap<String, Invokable> by hashMapOf() {
}