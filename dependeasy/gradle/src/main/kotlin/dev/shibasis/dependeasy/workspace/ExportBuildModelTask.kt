package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class ExportBuildModelTask : DefaultTask() {
    @get:Input abstract val buildName: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val nodes: ListProperty<String>
    @get:Input abstract val edges: ListProperty<String>
    @get:Input abstract val omissions: ListProperty<String>
    @get:Input abstract val taskFamilies: MapProperty<String, String>
    @get:Input abstract val directories: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val declarationFiles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val includedModels: ConfigurableFileCollection
    @get:Internal abstract val workspaceDirectory: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:OutputFile abstract val remoteHostsFile: RegularFileProperty

    @TaskAction fun write() {
        val root = workspaceDirectory.get().asFile
        val own = buildName.get()
        val included = includedModels.files.sortedBy { it.path }.map { BuildModelReader.decode(it.readText()) }
        val owners = directories.get()
        val builds = (owners.map { (name, directory) -> BuildIdentity(name, directory) } + included.flatMap { model ->
            model.builds.map { build ->
                val base = root.resolve(owners.getValue(model.builds.single { it.directory == "." }.name))
                build.copy(directory = base.resolve(build.directory).canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath)
            }
        }).groupBy { it.name }.map { (name, copies) ->
            require(copies.distinct().size == 1) { "Conflicting build directories for $name" }
            copies.first()
        }.sortedBy { it.name }
        val declarations = declarationFiles.files.filter { it.isFile }.map {
            DeclarationInput(own, it.relativeTo(root).invariantSeparatorsPath, BuildModelReader.digest(it))
        } + DeclarationInput(own, ".", BuildDeclarationInputs.membership(root), DeclarationInputKind.Membership) + included.flatMap { it.declarations }
        val declared = BuildModel(producer = Producer(version = version.get()), builds = builds,
            declarations = declarations.distinct().sortedWith(compareBy({ it.build }, { it.path })),
            nodes = (nodes.get().map(BuildModelReader::decodeNode) + included.flatMap { it.nodes }).groupBy { it.id }.map { (id, copies) ->
                val facts = copies.map { it.copy(label = it.id.name, declaredAt = null, inputs = emptyList(), outputs = emptyList(), additionalDeclarations = emptyList()) }.distinct()
                require(facts.size == 1) { "Conflicting kernel or invocation for $id" }
                val sites = copies.flatMap { listOfNotNull(it.declaredAt) + it.additionalDeclarations }.distinct().sortedWith(compareBy({ it.build }, { it.file }, { it.line }))
                copies.first().copy(label = id.name, declaredAt = sites.firstOrNull(), additionalDeclarations = sites.drop(1),
                    inputs = copies.flatMap { it.inputs }.distinct(), outputs = copies.flatMap { it.outputs }.distinct())
            }.sortedBy { it.id.toString() },
            edges = (edges.get().map { BuildModelReader.json.decodeFromString<Edge>(it) } + included.flatMap { it.edges }).distinct().sortedBy { it.toString() },
            omissions = (omissions.get().map { BuildModelReader.json.decodeFromString<Omission>(it) } + included.flatMap { it.omissions }).distinct().sortedBy { it.toString() })
        val identities = declared.nodes.map { it.id }.toSet()
        val model = declared.copy(omissions = (declared.omissions + declared.edges.filter { it.to !in identities }.map {
            Omission(it.from, "Referenced producer has no declaration model", it.to.toString())
        }).distinct().sortedBy { it.toString() })
        val text = BuildModelReader.encode(model)
        BuildModelReader.decode(text)
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(text) }
        val routing = RemoteHostsContract(builds = builds.associate { it.name to it.directory }.toSortedMap(),
            taskHosts = model.nodes.filter { it.id.build == own }.associate { (it.id.project.trimEnd(':') + ":" + it.id.name) to it.host }.toSortedMap(),
            taskFamilies = (taskFamilies.get() + model.nodes.filter { it.id.build == own && it.artifactFamily != null }.associate {
                (it.id.project.trimEnd(':') + ":" + it.id.name) to requireNotNull(it.artifactFamily) }).toSortedMap())
        remoteHostsFile.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(routing) + "\n") }
    }
}
