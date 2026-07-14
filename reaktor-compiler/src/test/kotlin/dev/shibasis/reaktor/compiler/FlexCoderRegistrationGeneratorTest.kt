package dev.shibasis.reaktor.compiler

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlexCoderRegistrationGeneratorTest {
    @Test
    fun aggregateUsesConfiguredIdentityAndDeterministicFqnOrder() {
        val file = buildFlexCoderRegistrarFile(
            packageName = "example.generated",
            objectName = "ExampleModuleFlexCoders",
            registrations = listOf(
                registration("zeta.models", "ZetaPayload"),
                registration("alpha.models", "AlphaPayload"),
                registration("middle.models", "Record"),
            ),
        ).toString()

        assertTrue(file.startsWith("package example.generated"))
        assertTrue(file.contains("public object ExampleModuleFlexCoders"))
        assertFalse(file.contains("registerGeneratedFlexCoders"))

        val registerBody = file.substringAfter("public fun register()")
        val alpha = registerBody.indexOf("AlphaPayloadFlexCoder.register()")
        val middle = registerBody.indexOf("RecordFlexCoder.register()")
        val zeta = registerBody.indexOf("ZetaPayloadFlexCoder.register()")
        assertTrue(alpha >= 0, file)
        assertTrue(middle > alpha, file)
        assertTrue(zeta > middle, file)
    }

    @Test
    fun sameSimpleCoderNamesFromDifferentPackagesDoNotCollide() {
        val file = buildFlexCoderRegistrarFile(
            packageName = "example.generated",
            objectName = "CollisionSafeFlexCoders",
            registrations = listOf(
                registration("first.models", "Message"),
                registration("second.models", "Message"),
            ),
        ).toString()

        assertEquals(2, Regex("^\\s+.*\\.register\\(\\)$", RegexOption.MULTILINE).findAll(file).count(), file)
        assertTrue(file.contains("import first.models.MessageFlexCoder"), file)
        assertTrue(file.contains("import second.models.MessageFlexCoder"), file)
    }

    @Test
    fun repeatedRoundEntryIsEmittedOnlyOnce() {
        val registration = registration("example.models", "Message")
        val file = buildFlexCoderRegistrarFile(
            packageName = "example.generated",
            objectName = "IncrementalSafeFlexCoders",
            registrations = listOf(registration, registration),
        ).toString()

        assertEquals(1, Regex("^\\s+.*MessageFlexCoder\\.register\\(\\)$", RegexOption.MULTILINE).findAll(file).count(), file)
    }

    @Test
    fun perCoderRegisterUsesActualConfiguredSerialNameAndIsRepeatable() {
        val method = buildFlexCoderRegisterMethod(
            className = ClassName("example.models", "RenamedPayload"),
            serialName = "wire.v2.payload",
        ).toString()

        assertTrue(
            method.contains("FlexCoderRegistry.register(example.models.RenamedPayload::class, this)"),
            method,
        )
        assertTrue(method.contains("FlexCoderRegistry.registerBySerialName(\"wire.v2.payload\", this)"), method)
        assertFalse(method.contains("example.models.RenamedPayload\""), method)
    }

    private fun registration(packageName: String, simpleName: String) =
        GeneratedFlexCoderRegistration(
            className = ClassName(packageName, simpleName),
            coderName = ClassName(packageName, "${simpleName}FlexCoder"),
            serialName = "$packageName.$simpleName",
        )
}
