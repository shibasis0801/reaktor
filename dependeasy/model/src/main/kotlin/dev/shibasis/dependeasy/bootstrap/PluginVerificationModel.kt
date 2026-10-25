@file:JvmName(BootstrapModelEntry.SimpleName)

package dev.shibasis.dependeasy.bootstrap

import dev.shibasis.dependeasy.model.*
import java.io.File

fun main(args: Array<String>) {
    require(args.size == 9)
    val (rootPath, build, version, source, line) = args.toList()
    val root = File(rootPath)
    val task = NodeId.task(build, args[5])
    val result = FileSet(listOf(args[7]), OutputKind.VerificationResults.name)
    val site = SourceSite(build, source, line.toInt())
    val verification = Node(task, NodeKind.Verification, task.name, Effect.Verify, HostRequirement(), site,
        KernelRef(args[6]), Invocation.Gradle(listOf(args[5])), outputs = listOf(result), artifactFamily = "verification")
    val output = verification.copy(id = NodeId(build, ":", "outputVerificationResultsPlugin"),
        kind = NodeKind.Kernel, label = "outputVerificationResultsPlugin")
    val declarations = BuildDeclarationInputs.files(root)
    val file = File(args[8])
    val spec = KernelSpec.BuildModelExport(build, version, mapOf(build to "."),
        FileSet(declarations.map { it.relativeTo(root).invariantSeparatorsPath }), FileSet(emptyList()),
        FileSet(listOf(file.relativeTo(root).invariantSeparatorsPath)), FileSet(emptyList()))
    val model = BuildModelKernel.build(spec, root, listOf(verification, output),
        listOf(Edge.Includes(output.id, verification.id)), emptyList(), emptyList(), declarations, emptyList())
    file.parentFile.mkdirs()
    file.writeText(BuildModelReader.encode(model))
}
