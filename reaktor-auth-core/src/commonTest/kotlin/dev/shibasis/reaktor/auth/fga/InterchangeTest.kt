package dev.shibasis.reaktor.auth.fga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterchangeTest {
    private val model = """
        model
          schema 1.1

        type user

        type circle
          relations
            define member: [user]
            define blocked: [user]
            define viewer: member but not blocked
    """.trimIndent() + "\n"

    private val store = FgaStore("BestBuds", "bestbuds.fga", app = "bestbuds",
        tuples = listOf(TupleKey("user:ada", "member", "circle:alpha"), TupleKey("user:bob", "member", "circle:alpha"), TupleKey("user:bob", "blocked", "circle:alpha")),
        tests = listOf(FgaTest("viewers", "A block wins over membership", tuples = listOf(TupleKey("user:cy", "member", "circle:beta")),
            check = listOf(CheckAssertion("user:ada", "circle:alpha", mapOf("viewer" to true, "blocked" to false)), CheckAssertion("user:bob", "circle:alpha", mapOf("viewer" to false))),
            listObjects = listOf(ListObjectsAssertion("user:cy", "circle", mapOf("viewer" to listOf("circle:beta"), "blocked" to emptyList()))))),
        modes = mapOf(FgaStore.EveryObject to AccessMode.Shadow))

    @Test
    fun aStoreIsWrittenAsAnOpenFgaTestFile() {
        assertEquals("""
            name: "BestBuds"
            model_file: "./bestbuds.fga"
            tuples:
              - user: "user:ada"
                relation: "member"
                object: "circle:alpha"
              - user: "user:bob"
                relation: "member"
                object: "circle:alpha"
              - user: "user:bob"
                relation: "blocked"
                object: "circle:alpha"
            tests:
              - name: "viewers"
                description: "A block wins over membership"
                tuples:
                  - user: "user:cy"
                    relation: "member"
                    object: "circle:beta"
                check:
                  - user: "user:ada"
                    object: "circle:alpha"
                    assertions:
                      blocked: false
                      viewer: true
                  - user: "user:bob"
                    object: "circle:alpha"
                    assertions:
                      viewer: false
                list_objects:
                  - user: "user:cy"
                    type: "circle"
                    assertions:
                      blocked: []
                      viewer:
                        - "circle:beta"

        """.trimIndent(), FgaInterchange.store(store.name, "./bestbuds.fga", store.tuples, store.tests))
    }

    @Test
    fun theModelPrintsBackToTheSameMeaningAndTheEmbeddedEnginePassesTheExportedTests() {
        val parsed = FgaDsl.parse(model).model!!
        assertEquals(parsed, FgaDsl.parse(FgaDsl.print(parsed)).model)
        assertTrue(FgaTests.run(parsed, store).all { it.passed })
    }

    @Test
    fun reaktorExtensionsTravelBesideTheStandardFile() {
        val extensions = FgaStore.decode(FgaInterchange.extensions(store))
        assertEquals("bestbuds", extensions.app)
        assertEquals(mapOf(FgaStore.EveryObject to AccessMode.Shadow), extensions.modes)
        assertEquals(emptyList(), extensions.tuples)
        assertEquals(emptyList(), extensions.tests)
    }
}
