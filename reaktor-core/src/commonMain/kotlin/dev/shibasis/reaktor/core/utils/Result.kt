package dev.shibasis.reaktor.core.utils

import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmName

// covariance:out allows assignment to subtypes of A,B,C,D
@Serializable
data class Quad<out A, out B, out C, out D>(
    public val first: A,
    public val second: B,
    public val third: C,
    public val fourth: D
) {
    override fun toString(): String = "($first, $second, $third, $fourth)"
}
inline fun<T> Result<T>.read(): T? = fold(
    { it },
    {
        Logger.e("Result<T>.read()") { it.message ?: "Unknown Error" }
        null
    }
)

inline fun <T, R : Any> Result<T>.chain(transform: (T) -> Result<R>): Result<R> =
    fold(transform) { fail(it) }

inline fun<T> Result<T>.onFailure(
    recover: (Throwable) -> Result<T>
): Result<T> = fold({ this }, { recover(it) })


fun<T> fail(message: String) = Result.failure<T>(Throwable(message))
fun<T> fail(throwable: Throwable) = Result.failure<T>(throwable)

fun<T> succeed(value: T) = Result.success(value)
fun<T1, T2> succeed(value1: T1, value2: T2) = Result.success(value1 to value2)
fun<T1, T2, T3> succeed(value1: T1, value2: T2, value3: T3) = Result.success(Triple(value1, value2, value3))
fun<T1, T2, T3, T4> succeed(value1: T1, value2: T2, value3: T3, value4: T4) = Result.success(Quad(value1, value2, value3, value4))

@JvmName("then")
inline fun <T, R : Any> Result<T>.then(
    transform: (T) -> Result<R>
): Result<Pair<T, R>> =
    chain { transform(it).map { itR -> it to itR } }

@JvmName("thenSecond")
inline fun <T1, T2, R : Any> Result<Pair<T1, T2>>.then(
    transform: (Pair<T1, T2>) -> Result<R>
): Result<Triple<T1, T2, R>> =
    chain { transform(it).map { itR -> Triple(it.first, it.second, itR) } }

@JvmName("thenThird")
inline fun <T1, T2, T3, R : Any> Result<Triple<T1, T2, T3>>.then(
    transform: (Triple<T1, T2, T3>) -> Result<R>
): Result<Quad<T1, T2, T3, R>> =
    chain { transform(it).map { itR -> Quad(it.first, it.second, it.third, itR) } }


inline fun<reified T1, reified T2> combine(
    result1: Result<T1>,
    result2: Result<T2>
): Result<Pair<T1, T2>> =
    result1.chain { it1 -> result2.map {
        it2 -> it1 to it2
    }}

inline fun<reified T1, reified T2, reified T3> combine(
    result1: Result<T1>,
    result2: Result<T2>,
    result3: Result<T3>
): Result<Triple<T1, T2, T3>> =
    combine(result1, result2).chain { it12 -> result3.map {
        Triple(it12.first, it12.second, it)
    }}

inline fun<reified T1, reified T2, reified T3, reified T4> combine(
    result1: Result<T1>,
    result2: Result<T2>,
    result3: Result<T3>,
    result4: Result<T4>
): Result<Quad<T1, T2, T3, T4>> =
    combine(result1, result2, result3).chain { it123 -> result4.map {
        Quad(it123.first, it123.second, it123.third, it)
    }}





