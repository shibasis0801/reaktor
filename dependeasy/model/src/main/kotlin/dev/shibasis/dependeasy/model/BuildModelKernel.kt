package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString

object BuildModelKernel {
    fun build(spec: KernelSpec.BuildModelExport, root: File, declaredNodes: List<Node>, edges: List<Edge>, omissions: List<Omission>,
        resolvedOptions: List<Map<String, String>>, declarationFiles: Collection<File>, included: List<BuildModel>): BuildModel {
        val own = spec.build
        val owners = spec.directories
        val builds = (owners.map { (name, directory) -> BuildIdentity(name, directory) } + included.flatMap { model ->
            model.builds.map { build ->
                val base = root.resolve(owners.getValue(model.builds.single { it.directory == "." }.name))
                build.copy(directory = base.resolve(build.directory).canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath)
            }
        }).groupBy { it.name }.map { (name, copies) ->
            require(copies.distinct().size == 1) { "Conflicting build directories for $name" }
            copies.first()
        }.sortedBy { it.name }
        val options = resolvedOptions.distinct().map { fields ->
            val value = OptionValue(fields.getValue("name"), OptionType.valueOf(fields.getValue("type")),
                fields.getValue("value"), OptionSource.valueOf(fields.getValue("source")), fields["file"],
                fields.getValue("sensitive").toBooleanStrict(), fields["environment"], scope = fields.getValue("scope"),
                defaultValue = fields["defaultValue"], resolutionOrder = fields["resolutionOrder"]?.split(',')
                    ?.filter(String::isNotEmpty)?.map(OptionSource::valueOf).orEmpty())
            val text = BuildModelReader.json.encodeToString(value)
            val hash = BuildModelReader.digest(text)
            DeclarationInput(own, "option:${fields.getValue("scope")}:${value.name}:${hash.take(16)}", hash, DeclarationInputKind.Option, value)
        }
        val declarations = options + declarationFiles.filter { it.isFile }.map {
            DeclarationInput(own, it.relativeTo(root).invariantSeparatorsPath, BuildModelReader.digest(it))
        } + DeclarationInput(own, ".", BuildDeclarationInputs.membership(root), DeclarationInputKind.Membership) + included.flatMap { it.declarations }
        val declared = BuildModel(producer = Producer(version = spec.version), builds = builds,
            declarations = declarations.distinct().sortedWith(compareBy({ it.build }, { it.path })),
            nodes = (declaredNodes + included.flatMap { it.nodes }).groupBy { it.id }.map { (id, copies) ->
                val facts = copies.map { it.copy(label = it.id.name, declaredAt = null, inputs = emptyList(), outputs = emptyList(), additionalDeclarations = emptyList()) }.distinct()
                require(facts.size == 1) { "Conflicting kernel or invocation for $id" }
                val sites = copies.flatMap { listOfNotNull(it.declaredAt) + it.additionalDeclarations }.distinct().sortedWith(compareBy({ it.build }, { it.file }, { it.line }))
                copies.first().copy(label = id.name, declaredAt = sites.firstOrNull(), additionalDeclarations = sites.drop(1),
                    inputs = copies.flatMap { it.inputs }.distinct(), outputs = copies.flatMap { it.outputs }.distinct())
            }.sortedBy { it.id.toString() },
            edges = (edges + included.flatMap { it.edges }).distinct().sortedBy { it.toString() },
            omissions = (omissions + included.flatMap { it.omissions }).distinct().sortedBy { it.toString() })
        val identities = declared.nodes.map { it.id }.toSet()
        val model = declared.copy(nodes = declared.nodes.map { node ->
            node.copy(tools = (node.tools + node.kernel?.spec?.tools().orEmpty()).distinct())
        }, omissions = (declared.omissions + declared.edges.filter { it.to !in identities }.map {
            Omission(it.from, "Referenced producer has no declaration model", it.to.toString())
        }).distinct().sortedBy { it.toString() })
        val owned = model.copy(omissions = (model.omissions + unownedInputs(model, root)).distinct().sortedBy { it.toString() })
        return BuildModelReader.decode(BuildModelReader.encode(owned))
    }

    private fun unownedInputs(model: BuildModel, root: File): List<Omission> {
        val directories = model.builds.associate { it.name to root.resolve(it.directory).canonicalFile.toPath() }
        val nodes = model.nodes.associateBy { it.id }
        val dependencies = model.edges.filter { it !is Edge.OrderedAfter }.groupBy { it.from }
        fun path(build: String, value: String) = directories.getValue(build).resolve(value).toFile().canonicalFile.toPath()
        return model.nodes.flatMap { node ->
            val visited = mutableSetOf<NodeId>()
            val delivered = mutableListOf<java.nio.file.Path>()
            fun visit(id: NodeId) {
                if (!visited.add(id)) return
                dependencies[id].orEmpty().forEach { edge ->
                    if (edge is Edge.Consumes) nodes[edge.to]?.outputs.orEmpty().flatMap { it.paths }
                        .mapTo(delivered) { path(edge.to.build, it) }
                    visit(edge.to)
                }
            }
            visit(node.id)
            val own = directories.getValue(node.id.build)
            val inputs = node.inputs.flatMap { input -> input.paths + input.declarations
                .filter { it.kind == FileInputKind.File || it.kind == FileInputKind.Tree }.map { it.identity } }.distinct()
            inputs.filter { input ->
                val actual = path(node.id.build, input)
                !actual.startsWith(own) && delivered.none(actual::startsWith)
            }.map { Omission(node.id, "Input outside owning build has no resolved output variant", it) }
        }
    }

    fun routing(model: BuildModel, own: String): RemoteHostsContract {
        val owned = model.nodes.filter { it.id.build == own }
        val public = owned.flatMap { node ->
            val paths = when (val spec = node.kernel?.spec) {
                is KernelSpec.BuildModelExport -> spec.output.paths + spec.routing.paths
                is KernelSpec.WebContract -> spec.output.paths + spec.typeScriptConfiguration.paths
                is KernelSpec.KotlinExportCatalog -> spec.output.paths
                is KernelSpec.ReleasePlan -> listOf(spec.output)
                is KernelSpec.XcodeHost -> if (spec.checking) emptyList() else spec.files.drop(1) +
                    AppleHostKernel.files(spec).keys.map { File(spec.files.first(), it).invariantSeparatorsPath }
                is KernelSpec.WorkerFleet, is KernelSpec.PackageScripts, is KernelSpec.DaggerPipeline -> node.outputs.flatMap { it.paths }
                is KernelSpec.ContainerNode -> spec.output.paths
                else -> emptyList()
            }.distinct().sorted()
            paths.forEach { path -> require(path.isNotBlank() && !File(path).isAbsolute && ".." !in path.split('/')) { "Public transport output escapes $own: $path" } }
            val tasks = (listOf(node.id.project.trimEnd(':') + ":" + node.id.name) +
                (node.invocation as? Invocation.Gradle)?.tasks.orEmpty()).distinct()
            tasks.map { it to paths }
        }.filter { it.second.isNotEmpty() }.groupBy({ it.first }, { it.second })
            .mapValues { (_, paths) -> paths.flatten().distinct().sorted() }.toSortedMap()
        val exports = owned.mapNotNull { it.kernel?.spec as? KernelSpec.KotlinExportCatalog }
            .flatMap { it.directories.paths }.distinct().sorted()
        val routing = RemoteHostsContract(builds = model.builds.associate { it.name to it.directory }.toSortedMap(),
            taskHosts = model.nodes.filter { it.id.build == own }.associate { (it.id.project.trimEnd(':') + ":" + it.id.name) to model.requiredHost(it.id) }.toSortedMap(),
            taskFamilies = taskFamilies(model, own), publicOutputs = public, kotlinExports = exports,
            environment = model.declarations.mapNotNull { it.option?.environment }.distinct().sorted())
        val appleVariables = model.nodes.mapNotNull { (it.kernel?.spec as? KernelSpec.XcodeHost)?.host }
            .map { host -> "${host.targets.single { it.name == host.applicationTarget }.settingsPrefix}_GRADLE_ARGS" }
        return routing.copy(xcodeEnvironment = (routing.xcodeEnvironment + appleVariables).distinct().sorted())
    }

    fun taskFamilies(model: BuildModel, own: String): Map<String, String> {
        fun path(build: String, task: String) = (if (build == own) "" else ":$build") + task
        val declared = model.nodes.filter { it.artifactFamily != null }
        val invoked = declared.flatMap { node ->
            (node.invocation as? Invocation.Gradle)?.tasks.orEmpty().map { path(node.id.build, it) to requireNotNull(node.artifactFamily) }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (task, families) -> families.distinct().singleOrNull()?.let { task to it } }
        val named = declared.map { path(it.id.build, it.id.project.trimEnd(':') + ":" + it.id.name) to requireNotNull(it.artifactFamily) }
        return (invoked + named).toMap().toSortedMap()
    }

}
