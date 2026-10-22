package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable enum class Domain { Mobile, Web, Desktop, Site, Server, Worker, Shared, Tooling, Framework }

@Serializable enum class Aspect(val label: String) {
    Static("Static"), Unit("Unit"), Integration("Integration"), EndToEnd("End-to-end"), Performance("Performance"),
    EndToEndPerformance("End-to-end performance"), Load("Load"), Accessibility("Accessibility"), Security("Security"), Packaging("Packaging"),
}

@Serializable
data class Coverage(val domains: Set<Domain> = emptySet(), val aspects: Set<Aspect> = emptySet(), val system: String? = null) {
    constructor(domain: Domain, vararg aspects: Aspect, system: String? = null) : this(setOf(domain), aspects.toSet(), system)

    operator fun plus(other: Coverage) = Coverage(domains + other.domains, aspects + other.aspects, system ?: other.system)
}

@Serializable enum class ProductPart { Shared, Client, Service, Site, Tool, Package, Native }

@Serializable data class ProductModel(val build: String, val parts: Map<ProductPart, List<String>> = emptyMap())

val Verification.aspect: Aspect get() = when (this) {
    Verification.Jvm, Verification.AndroidUnit, Verification.JavaScript -> Aspect.Unit
    Verification.TypeScript, Verification.InteropTypeScript, Verification.KernelBoundary -> Aspect.Static
    Verification.InteropHost, Verification.NativeHost, Verification.WebBridge, Verification.Plugin -> Aspect.Integration
    Verification.Hangar, Verification.Window -> Aspect.EndToEnd
}

object CoverageKernel {
    private const val VerificationOutput = "outputVerificationResults"
    private val UnitKernels = setOf("KotlinJvmTest", "KotlinJsTest", "AndroidUnitTest", "Test")

    fun apply(model: BuildModel, build: String): BuildModel {
        val covered = of(model, build)
        return model.copy(nodes = model.nodes.map { node -> covered[node.id]?.let { node.copy(coverage = it) } ?: node })
    }

    fun of(model: BuildModel, build: String): Map<NodeId, Coverage> {
        val checks = model.nodes.filter { it.effect == Effect.Verify }.associateBy { it.id }
        val children = model.edges.filter { (it is Edge.Includes || it is Edge.Consumes) && it.from != it.to && it.from in checks && it.to in checks }
            .groupBy({ it.from }, { it.to }).mapValues { (_, ids) -> ids.distinct() }
        val parents = children.flatMap { (parent, ids) -> ids.map { it to parent } }.groupBy({ it.first }, { it.second })
        val owners = Owners(model)
        val declared = checks.mapValues { (_, node) -> node.coverage ?: Coverage() }
        val domains = checks.mapValues { (id, node) -> declared.getValue(id).domains.ifEmpty { if (id.build == build) owners.domains(node) else emptySet() } }
        val aspects = checks.mapValues { (id, node) -> declared.getValue(id).aspects.ifEmpty { if (id.build == build) aspects(node) else emptySet() } }
        val resolved = HashMap<NodeId, Coverage>()
        fun cover(id: NodeId, path: MutableSet<NodeId>): Coverage = resolved[id] ?: if (id.build != build) declared.getValue(id) else {
            path.add(id)
            val below = children[id].orEmpty().filter { it !in path }.map { cover(it, path) }
            path.remove(id)
            val named = declared.getValue(id).system
            val covered = if (aspects.getValue(id).isEmpty() && below.isNotEmpty()) Coverage(
                domains.getValue(id).ifEmpty { below.flatMapTo(LinkedHashSet()) { it.domains } },
                below.flatMapTo(LinkedHashSet()) { it.aspects },
                named ?: below.map { it.system }.distinct().singleOrNull())
            else Coverage(
                domains.getValue(id).ifEmpty { nearest(id, parents, domains) },
                aspects.getValue(id).ifEmpty { nearest(id, parents, aspects) },
                named ?: system(checks.getValue(id)))
            covered.also { resolved[id] = it }
        }
        return checks.keys.filter { it.build == build }.associateWith { cover(it, HashSet()) }
    }

    private fun <T> nearest(id: NodeId, parents: Map<NodeId, List<NodeId>>, own: Map<NodeId, Set<T>>): Set<T> {
        val seen = hashSetOf(id)
        var level = parents[id].orEmpty().filter(seen::add)
        while (level.isNotEmpty()) {
            val found = level.flatMapTo(LinkedHashSet()) { own[it].orEmpty() }
            if (found.isNotEmpty()) return found
            level = level.flatMap { parents[it].orEmpty() }.filter(seen::add)
        }
        return emptySet()
    }

    private fun aspects(node: Node): Set<Aspect> {
        val spec = node.kernel?.spec
        val kernel = node.kernel?.className?.substringAfterLast('.')
        val verification = Verification.entries.firstOrNull { node.id.name == VerificationOutput + it.name }
        return setOfNotNull(when {
            verification != null -> verification.aspect
            spec is KernelSpec.WorkspaceNode && spec.requirements.isNotEmpty() && spec.includes.isEmpty() && spec.consumes.isEmpty() -> Aspect.Static
            spec is KernelSpec.TestSuite && (spec.suite == Suite.Hangar || TestTag.Window in spec.only) -> Aspect.EndToEnd
            kernel == "TestEnvironmentKernel" -> Aspect.EndToEnd
            spec is KernelSpec.TestSuite || kernel in UnitKernels -> Aspect.Unit
            spec is KernelSpec.DeviceFlow || spec is KernelSpec.KarateSuite -> Aspect.EndToEnd
            spec is KernelSpec.K6Suite -> Aspect.Load
            spec is KernelSpec.Jmh || spec is KernelSpec.Profile || spec is KernelSpec.ArtifactSize -> Aspect.Performance
            spec is KernelSpec.TypeScriptCheck || spec is KernelSpec.DependencyBoundary -> Aspect.Static
            spec is KernelSpec.DarwinArchive || spec is KernelSpec.XcodeHost -> Aspect.Packaging
            else -> null
        })
    }

    private fun system(node: Node): String? {
        val project = (node.kernel?.spec as? KernelSpec.DeviceFlow)?.let { runCatching { NodeId.parse(it.appOutput).project }.getOrNull() } ?: node.id.project
        return project.substringAfterLast(':').takeIf { it.isNotEmpty() }
    }

    private class Owners(model: BuildModel) {
        private val products = model.products.mapTo(HashSet()) { it.build }
        private val parts = model.products.flatMap { product -> product.parts.flatMap { (part, projects) -> projects.map { (product.build to it) to part } } }.toMap()
        private val families = model.nodes.groupBy({ it.id.build to it.id.project }, { it.artifactFamily }).mapValues { (_, families) -> families.filterNotNull().toSet() }
        private val fleets = model.nodes.filter { it.kernel?.spec is KernelSpec.WorkerFleet }.mapTo(HashSet()) { it.id.build to it.id.project }

        fun domains(node: Node): Set<Domain> {
            if (node.kernel?.spec is KernelSpec.DeviceFlow) return setOf(Domain.Mobile)
            if (node.id.build !in products) return setOf(Domain.Framework)
            val project = node.id.build to node.id.project
            val family = families[project].orEmpty()
            return setOfNotNull(when (parts[project]) {
                ProductPart.Shared, ProductPart.Package, ProductPart.Native -> Domain.Shared
                ProductPart.Client -> when {
                    "android" in family || "apple" in family -> Domain.Mobile
                    "web" in family -> Domain.Web
                    else -> Domain.Desktop
                }
                ProductPart.Service -> if (project in fleets) Domain.Worker else Domain.Server
                ProductPart.Site -> Domain.Site
                ProductPart.Tool -> Domain.Tooling
                null -> null
            })
        }
    }
}
