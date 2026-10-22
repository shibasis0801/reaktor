package dev.shibasis.dependeasy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverageKernelTest {
    private val execution = ProcessExecution(".", "tool", emptyList(), emptyList(), ToolRef("tool", "1"), emptyList(), emptyList())

    private fun node(id: String, kernel: String, effect: Effect = Effect.Verify, spec: KernelSpec? = null, family: String? = null, coverage: Coverage? = null): Node {
        val identity = NodeId.parse(id)
        return Node(identity, NodeKind.Kernel, identity.name, effect, HostRequirement(), null, KernelRef(kernel, spec = spec),
            Invocation.Gradle(listOf(identity.project.trimEnd(':') + ":" + identity.name)), artifactFamily = family, coverage = coverage)
    }

    private fun edge(from: String, to: String) = Edge.Includes(NodeId.parse(from), NodeId.parse(to))

    private val model = BuildModel(producer = Producer(version = "fixture"),
        builds = listOf(BuildIdentity("product", "."), BuildIdentity("framework", "../framework")),
        products = listOf(ProductModel("product", mapOf(
            ProductPart.Shared to listOf(":app"),
            ProductPart.Client to listOf(":android", ":desktop"),
            ProductPart.Service to listOf(":workers", ":server")))),
        nodes = listOf(
            node("product:android:assembleDebug", "AndroidAssemble", Effect.Build, family = "android"),
            node("product:android:testDebugUnitTest", "AndroidUnitTest"),
            node("product:android:maestro", "DeviceFlowKernel", spec = KernelSpec.DeviceFlow("android", "app.id", "product:android:outputApkDebug",
                listOf("flows"), "contract", emptyList(), ToolRef("maestro", "1"))),
            node("product:desktop:outputVerificationResultsHangar", "VerificationKernel"),
            node("product:workers:deployWorkers", "WorkerFleetKernel", Effect.Deploy,
                spec = KernelSpec.WorkerFleet(Delivery(listOf(Environment("prod")), Environment("prod"), Delivery.Cloudflare("fixture-account", emptyList(), true)), emptyList(), FileSet(emptyList()))),
            node("product:workers:jsTest", "KotlinJsTest"),
            node("product:workers:karateConfigServerAuthDev", "KarateSuiteKernel",
                spec = KernelSpec.KarateSuite(execution, emptyList(), emptyList(), "karate.jar", "report", emptyList()),
                coverage = Coverage(Domain.Worker, system = "configServer")),
            node("product:server:k6Load", "K6SuiteKernel", spec = KernelSpec.K6Suite(execution, "load.js", "summary.json", emptyList())),
            node("product:webChecks", "ExternalKernel", coverage = Coverage(Domain.Web, Aspect.EndToEnd)),
            node("product:webChecksCommand", "ProcessKernel"),
            node("product:prepareDevice", "ProcessKernel"),
            node("product:app:requireDevToolsDisabledForRelease", "VerificationKernel", spec = KernelSpec.WorkspaceNode(
                NodeId.parse("product:app:requireDevToolsDisabledForRelease"), NodeKind.Verification, Run.Default, emptyList(), emptyList(), emptyList(),
                listOf(OptionRequirement(OptionValue("product.devTools", OptionType.Boolean, "false", OptionSource.Default), "false")))),
            node("product:pr", "VerificationKernel"),
            node("framework:core:jvmTest", "KotlinJvmTest", coverage = Coverage(Domain.Framework, Aspect.Unit, system = "core"))),
        edges = listOf(
            edge("product:webChecks", "product:webChecksCommand"),
            edge("product:pr", "product:android:testDebugUnitTest"),
            Edge.Consumes(NodeId.parse("product:pr"), NodeId.parse("product:workers:karateConfigServerAuthDev"), "VerificationResults"),
            edge("product:pr", "framework:core:jvmTest")))

    @Test fun checksTakeTheirDomainFromTheProductPartAndTheirAspectFromTheirKernel() {
        val covered = CoverageKernel.of(model, "product").mapKeys { it.key.toString() }
        assertEquals(Coverage(Domain.Mobile, Aspect.Unit, system = "android"), covered["product:android:testDebugUnitTest"])
        assertEquals(Coverage(Domain.Mobile, Aspect.EndToEnd, system = "android"), covered["product:android:maestro"])
        assertEquals(Coverage(Domain.Desktop, Aspect.EndToEnd, system = "desktop"), covered["product:desktop:outputVerificationResultsHangar"])
        assertEquals(Coverage(Domain.Worker, Aspect.Unit, system = "workers"), covered["product:workers:jsTest"])
        assertEquals(Coverage(Domain.Server, Aspect.Load, system = "server"), covered["product:server:k6Load"])
    }

    @Test fun declarationsWinAndUnclassifiedChecksInheritFromTheirNearestDeclaredParent() {
        val covered = CoverageKernel.of(model, "product").mapKeys { it.key.toString() }
        assertEquals(Coverage(Domain.Worker, Aspect.EndToEnd, system = "configServer"), covered["product:workers:karateConfigServerAuthDev"])
        assertEquals(Coverage(Domain.Web, Aspect.EndToEnd), covered["product:webChecksCommand"])
        assertEquals(Coverage(), covered["product:prepareDevice"])
        assertEquals(Coverage(Domain.Shared, Aspect.Static, system = "app"), covered["product:app:requireDevToolsDisabledForRelease"])
    }

    @Test fun suitesUniteWhatTheyRunAndOtherBuildsKeepTheirOwnCoverage() {
        val covered = CoverageKernel.of(model, "product").mapKeys { it.key.toString() }
        assertEquals(Coverage(setOf(Domain.Mobile, Domain.Worker, Domain.Framework), setOf(Aspect.Unit, Aspect.EndToEnd)), covered["product:pr"])
        assertNull(covered["framework:core:jvmTest"])
        val decoded = BuildModelReader.decode(BuildModelReader.encode(CoverageKernel.apply(model, "product")))
        assertEquals(model.products, decoded.products)
        assertEquals(covered["product:pr"], decoded.nodes.single { it.id.toString() == "product:pr" }.coverage)
        assertEquals(Coverage(Domain.Framework, Aspect.Unit, system = "core"), decoded.nodes.single { it.id.build == "framework" }.coverage)
        assertNull(decoded.nodes.single { it.id.name == "assembleDebug" }.coverage)
    }
}
