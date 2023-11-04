package com.jetbrains.kmm.shared

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import kotlin.test.Test
import kotlin.test.assertEquals


private fun String.toFlexBuffer(): ReadBuffer {
    val buffer = FlexBuffersBuilder()
    buffer.put(this)
    return buffer.finish()
}

private fun flexBufToString(readBuffer: ReadBuffer): String {
    return getRoot(readBuffer).toString()
}

class FlatInvokerTestSuite {
    @Test
    fun stringIdentityKotlin() {
        val helloWorld = "Hello Flatbuffers"
        val identity = flexBufToString(helloWorld.toFlexBuffer())
        assertEquals(helloWorld, identity)
    }

    @Test
    fun stringIdentityCppKotlin() {
        val helloWorld = "Hello Flatbuffers"
        val buffer = helloWorld.toFlexBuffer()
        // send buffer to cpp
        // receive buffer from cpp
        // check equivalence
    }

    // Does not work :(
    @Test
    fun multiplatformIntCppFunction() {
//        assertEquals(42, Platform().data)
    }
}

/*
Java
public class Request {
    int num;
}


class Request()

def predictor(request: Request):



 */