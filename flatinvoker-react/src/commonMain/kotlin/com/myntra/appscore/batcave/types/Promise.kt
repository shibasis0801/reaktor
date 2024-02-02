package com.myntra.appscore.batcave.types

typealias Resolver = ((Any) -> Unit)?
typealias Rejecter = ((Error) -> Unit)?

// Also make it usable intra-kotlin by using a flow underneath
// Or reimplement it using FlowHandle ?
expect class Promise(executor: suspend Promise.() -> Unit) {
    fun resolve(value: Any)
    fun reject(error: Error)
}
