package dev.shibasis.reaktor.core.utils

import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig

inline fun String.logger() = Logger(StaticConfig(), this)

inline fun Logger.info(any: Any) = i { any.toString() }
inline fun Logger.info(fn: () -> Any) = info(fn())

inline fun Logger.debug(any: Any) = d { any.toString() }
inline fun Logger.debug(fn: () -> Any) = debug(fn())

inline fun Logger.verbose(any: Any) = v { any.toString() }
inline fun Logger.verbose(fn: () -> Any) = verbose(fn())

inline fun Logger.warn(any: Any) = w { any.toString() }
inline fun Logger.warn(fn: () -> Any) = warn(fn())

inline operator fun Logger.invoke(crossinline fn: () -> Any) = debug(fn())
