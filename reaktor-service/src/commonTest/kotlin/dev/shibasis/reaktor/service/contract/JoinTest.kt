package dev.shibasis.reaktor.service.contract

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private data class Order(val id: String, val at: Instant)
private data class Payment(val order: String, val at: Instant, val amount: Int)

private fun at(seconds: Int) = Instant.fromEpochSeconds(1_800_000_000L + seconds)

class JoinTest {
    @Test
    fun matchesByKeyInsideTheWindowAndDropsWhatArrivesLate() {
        val join = TemporalJoin<String, Order, Payment, String>(within = 10.seconds, lateness = 5.seconds) { order, payment -> "${order.id}:${payment.amount}" }

        assertEquals(emptyList(), join.left("a", at(0), Order("a", at(0))))
        assertEquals(listOf("a:5"), join.right("a", at(4), Payment("a", at(4), 5)))
        assertEquals(emptyList(), join.right("b", at(4), Payment("b", at(4), 7)))
        assertEquals(emptyList(), join.right("a", at(30), Payment("a", at(30), 9)))
        assertEquals(emptyList(), join.left("b", at(10), Order("b", at(10))))
        assertEquals(1, join.late)
        assertEquals(listOf("a:9"), join.left("a", at(26), Order("a", at(26))))
        assertEquals(at(25), join.watermark)
        assertEquals(2, join.pending)
    }

    @Test
    fun flowsJoinOnEventTime() = runTest {
        val orders = flow {
            emit(Order("a", at(0)))
            delay(10.milliseconds)
            emit(Order("b", at(1)))
        }
        val payments = flow {
            delay(5.milliseconds)
            emit(Payment("a", at(2), 3))
            delay(10.milliseconds)
            emit(Payment("b", at(40), 4))
        }

        val paid = orders.joinWithin(payments, 10.seconds, keyed({ it.at }) { it.id }, keyed({ it.at }) { it.order }) { order, payment ->
            order.id to payment.amount
        }.toList()

        assertEquals(listOf("a" to 3), paid)
    }

    @Test
    fun quorumsWaitForEnoughAnswersAndFailWhenTheyCannotArrive() = runTest {
        val slow: suspend () -> Int = { delay(50.milliseconds); 1 }
        val fast: suspend () -> Int = { delay(5.milliseconds); 2 }
        val broken: suspend () -> Int = { delay(1.milliseconds); error("down") }

        assertEquals(2, firstSuccess(slow, fast, broken))
        assertEquals(listOf(2, 1), quorum(2, slow, fast, broken))
        assertFailsWith<IllegalStateException> { quorum(2, broken, broken, fast) }
    }
}
