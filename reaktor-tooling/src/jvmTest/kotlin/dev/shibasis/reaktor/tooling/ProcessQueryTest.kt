package dev.shibasis.reaktor.tooling

import kotlin.test.*

class ProcessQueryTest {
    @Test fun structuredReadsPreserveOutputAndRejectOverflow() {
        val read = ProcessQuery.read(listOf("/bin/sh", "-c", "printf 'one\\ntwo\\n'"), maxOutputChars = 100)
        assertTrue(read.succeeded)
        assertEquals("one\ntwo\n", read.output)
        val overflow = ProcessQuery.read(listOf("/bin/sh", "-c", "printf '123456789'"), maxOutputChars = 4)
        assertFalse(overflow.succeeded)
        assertEquals("", overflow.output)
    }

    @Test fun timeoutCompletesWithNoPartialPayload() {
        val read = ProcessQuery.read(listOf("/bin/sh", "-c", "printf partial; sleep 30"), timeoutMillis = 100)
        assertEquals(RunStatus.TimedOut, read.status)
        assertEquals("", read.output)
    }
}
