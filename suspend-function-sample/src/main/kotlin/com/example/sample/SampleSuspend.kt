package com.example.sample

suspend fun topLevelSuspend() {}

class Sample {
    suspend fun memberSuspend() {}

    fun regularFunction() {}
}
