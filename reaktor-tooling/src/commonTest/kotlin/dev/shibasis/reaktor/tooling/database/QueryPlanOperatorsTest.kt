package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.json.Json
import kotlin.test.*

class QueryPlanOperatorsTest {
    private fun plan(json: String, analyzed: Boolean = true) = QueryPlan("postgres-json", Json.parseToJsonElement(json), analyzed)

    @Test fun nestedLoopsKeepPerLoopAndAccumulatedStatisticsDistinct() {
        val operators = plan("""[{"Plan":{"Node Type":"Nested Loop","Plan Rows":20,"Actual Rows":2000,"Actual Loops":1,"Actual Total Time":12,
          "Plans":[{"Node Type":"Index Scan","Plan Rows":2,"Actual Rows":200,"Actual Loops":10,"Actual Total Time":0.6,"Shared Hit Blocks":40,"Temp Written Blocks":0}]}}]""").operators()
        val child = operators[1]
        assertEquals(operators[0].id, child.parentId)
        assertEquals(200.0, child.actualRowsPerLoop)
        assertEquals(2000.0, child.accumulatedRows)
        assertEquals(6.0, child.accumulatedInclusiveMillis)
        assertEquals(100.0, child.rowEstimateErrorFactor)
        assertEquals(0L, child.tempWrittenBlocks)
        assertNull(child.tempReadBlocks)
        assertNull(child.diskBytes)
    }

    @Test fun parallelAndNeverExecutedNodesDoNotInventWallTimeOrEstimateErrors() {
        val operators = plan("""[{"Plan":{"Node Type":"Gather","Actual Total Time":10,"Actual Loops":1,"Plans":[
            {"Node Type":"Seq Scan","Parallel Aware":true,"Actual Total Time":8,"Actual Loops":3,"Actual Rows":20,"Plan Rows":20},
            {"Node Type":"Index Scan","Actual Loops":0,"Actual Rows":0,"Plan Rows":100}]}}]""").operators()
        assertEquals(24.0, operators[1].accumulatedInclusiveMillis, "Worker overlap can exceed elapsed query time")
        assertEquals(true, operators[1].parallelAware)
        assertFalse(operators[2].zeroRowEstimateMismatch)
        assertNull(operators[2].rowEstimateErrorFactor)
        assertNull(operators[2].accumulatedInclusiveMillis, "Missing timing is unknown, even with zero loops")
    }

    @Test fun costsAreNotDurationsAndMissingLoopsAreNotAssumedToBeOne() {
        val document = """[{"Plan":{"Node Type":"Sort","Plan Rows":10,"Total Cost":99,"Actual Rows":0,"Actual Total Time":4}}]"""
        val estimated = plan(document, analyzed = false).operators().single()
        assertEquals(10.0, estimated.estimatedRows)
        assertNull(estimated.inclusiveMillisPerLoop)
        assertNull(estimated.actualRowsPerLoop)
        val actual = plan(document).operators().single()
        assertNull(actual.accumulatedInclusiveMillis)
        assertTrue(actual.zeroRowEstimateMismatch)
        assertNull(actual.rowEstimateErrorFactor, "Zero is a mismatch, not an invented finite ratio")
    }

    @Test fun spillsAndPruningRetainProviderUnitsAndUnknowns() {
        val operator = plan("""[{"Plan":{"Node Type":"Sort","Sort Space Type":"Disk","Sort Space Used":64,"Subplans Removed":3,"Temp Read Blocks":2,"Peak Memory Usage":8}}]""").operators().single()
        assertEquals(65536L, operator.diskBytes)
        assertEquals(8192L, operator.peakMemoryBytes)
        assertEquals(3L, operator.removedSubplans)
        assertEquals(2L, operator.tempReadBlocks)
        assertNull(operator.tempWrittenBlocks)
    }

    @Test fun invalidAndOverflowedStatisticsStayUnknown() {
        val operator = plan("""[{"Plan":{"Node Type":"Scan","Plan Rows":-1,"Actual Rows":"NaN","Actual Loops":2,"Actual Total Time":"Infinity","Peak Memory Usage":9223372036854775807}}]""").operators().single()
        assertNull(operator.estimatedRows); assertNull(operator.actualRowsPerLoop)
        assertNull(operator.accumulatedInclusiveMillis); assertNull(operator.peakMemoryBytes)
    }
}
