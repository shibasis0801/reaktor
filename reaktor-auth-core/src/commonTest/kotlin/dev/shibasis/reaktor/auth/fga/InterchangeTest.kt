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

    private val rolesModel = """
        model
          schema 1.1

        type user

        type role
          relations
            define assignee: [user]

        type permission
          relations
            define holder: [user, role#assignee]
    """.trimIndent() + "\n"

    private val roles = FgaStore("Roles", "roles.fga",
        tuples = listOf(TupleKey("role:companyos:superadmin#assignee", "holder", "permission:auth:pat:mint"), TupleKey("user:ada", "assignee", "role:companyos:superadmin"), TupleKey("user:ada", "assignee", "role:companyos:superadmin")),
        tests = listOf(FgaTest("holders", tuples = listOf(TupleKey("user:ada", "assignee", "role:companyos:superadmin"), TupleKey("user:bob", "holder", "permission:auth:pat:mint")),
            check = listOf(CheckAssertion("user:ada", "permission:auth:pat:mint", mapOf("holder" to true)), CheckAssertion("user:cy", "permission:auth:pat:mint", mapOf("holder" to false))),
            listObjects = listOf(ListObjectsAssertion("user:bob", "permission", mapOf("holder" to listOf("permission:auth:pat:mint")))))))

    private val openFgaId = Regex("""[^:#\s]+:[^#:\s]+(#[^:#\s]+)?""")

    private fun ids(yaml: String): List<String> =
        Regex("""^\s*(?:- )?(?:user|object): "(.*)"$|^\s*- "(.*)"$""", RegexOption.MULTILINE).findAll(yaml).map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }.toList()

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

    @Test
    fun colonsInIdsArePercentEncodedAndTestTuplesTheStoreHasAreLeftOut() {
        val yaml = FgaInterchange.store(roles.name, "./roles.fga", roles.tuples, roles.tests)
        assertEquals("""
            name: "Roles"
            model_file: "./roles.fga"
            tuples:
              - user: "role:companyos%3Asuperadmin#assignee"
                relation: "holder"
                object: "permission:auth%3Apat%3Amint"
              - user: "user:ada"
                relation: "assignee"
                object: "role:companyos%3Asuperadmin"
            tests:
              - name: "holders"
                tuples:
                  - user: "user:bob"
                    relation: "holder"
                    object: "permission:auth%3Apat%3Amint"
                check:
                  - user: "user:ada"
                    object: "permission:auth%3Apat%3Amint"
                    assertions:
                      holder: true
                  - user: "user:cy"
                    object: "permission:auth%3Apat%3Amint"
                    assertions:
                      holder: false
                list_objects:
                  - user: "user:bob"
                    type: "permission"
                    assertions:
                      holder:
                        - "permission:auth%3Apat%3Amint"

        """.trimIndent(), yaml)
        assertEquals(emptyList(), ids(yaml).filterNot(openFgaId::matches))
    }

    @Test
    fun theEmbeddedEngineAnswersTheEncodedTestsAsTheOriginals() {
        val parsed = FgaDsl.parse(rolesModel).model!!
        val encoded = roles.copy(tuples = roles.tuples.map(FgaIds::encode), tests = roles.tests.map(FgaIds::encode))
        assertTrue(FgaTests.run(parsed, roles).all { it.passed })
        assertEquals(FgaTests.run(parsed, roles).map { it.passed }, FgaTests.run(parsed, encoded).map { it.passed })
    }

    @Test
    fun encodedIdsHaveOpenFgasFormatAndDecodeToTheOriginal() {
        listOf("role:companyos:superadmin#assignee", "permission:auth:pat:mint", "user:*", "graph:demo/edge", "role:50%:off#assignee", "circle:a%3Ab").forEach { reference ->
            val encoded = FgaIds.encode(reference)
            assertTrue(openFgaId.matches(encoded), encoded)
            assertEquals(reference, FgaIds.decode(encoded))
        }
        assertEquals(emptyList(), ids(FgaInterchange.store(store.name, "./bestbuds.fga", store.tuples, store.tests)).filterNot(openFgaId::matches))
    }
}
